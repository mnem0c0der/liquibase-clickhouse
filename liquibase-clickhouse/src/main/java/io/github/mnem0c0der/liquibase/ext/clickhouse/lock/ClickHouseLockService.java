/*
 * Copyright the liquibase-clickhouse contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.mnem0c0der.liquibase.ext.clickhouse.lock;

import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterPolicyFactory;
import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.exception.LockException;
import liquibase.lockservice.DatabaseChangeLogLock;
import liquibase.lockservice.LockService;

/**
 * Changelog lock for ClickHouse.
 *
 * <p>ClickHouse has no transactions and no atomic UPDATE with an affected-row count, so this uses
 * an optimistic protocol instead: a claim row is inserted, the table is reread, and a deterministic
 * rule picks the winner. A loser retracts its claim and tries again. The standard Liquibase lock
 * statements express {@code UPDATE ... WHERE LOCKED = 0}, which has no ClickHouse equivalent, so
 * {@link LockRepository} builds its own SQL rather than pretending that statement applies here.
 * While the lock is held, a background heartbeat keeps re-inserting the holder's row so that
 * "stale" means "the holder stopped responding" rather than "the holder is busy".
 *
 * <p>Every override declares the narrow {@code throws DatabaseException}: that keeps this class
 * compiling against Liquibase 4.31.1 while remaining a valid override against 5.0.x, where the same
 * methods are declared with the wider {@code throws LiquibaseException}.
 */
public class ClickHouseLockService implements LockService {

  private static final int WITHDRAW_MAX_ATTEMPTS = 3;

  private Database database;
  private LockRepository repository;
  private OptimisticLockArbiter arbiter;

  private volatile boolean hasLock;
  private volatile String heldLockId;
  private volatile Instant heldClaimedAt;
  private volatile Thread heartbeatThread;

  private long changeLogLockWaitMillis = Duration.ofMinutes(5).toMillis();
  private long changeLogLockRecheckMillis =
      ClickHouseConfiguration.LOCK_POLL_INTERVAL_MILLIS.getCurrentValue();

  @Override
  public int getPriority() {
    return PRIORITY_DATABASE;
  }

  @Override
  public boolean supports(Database database) {
    return database instanceof ClickHouseDatabase;
  }

  @Override
  public void setDatabase(Database database) {
    this.database = database;
    this.repository = new LockRepository(database, ClusterPolicyFactory.fromConfiguration());
    this.arbiter =
        new OptimisticLockArbiter(
            Duration.ofSeconds(ClickHouseConfiguration.LOCK_TIMEOUT_SECONDS.getCurrentValue()));
  }

  @Override
  public void setChangeLogLockWaitTime(long changeLogLockWaitTime) {
    this.changeLogLockWaitMillis = changeLogLockWaitTime;
  }

  @Override
  public void setChangeLogLockRecheckTime(long changeLogLockRecheckTime) {
    this.changeLogLockRecheckMillis = changeLogLockRecheckTime;
  }

  @Override
  public boolean hasChangeLogLock() {
    return hasLock;
  }

  @Override
  public void init() throws DatabaseException {
    if (lockingDisabled()) {
      return;
    }
    repository.createTableIfMissing();
  }

  @Override
  public boolean acquireLock() throws LockException {
    if (hasLock) {
      return true;
    }
    if (lockingDisabled()) {
      hasLock = true;
      return true;
    }

    try {
      repository.createTableIfMissing();

      String lockId = UUID.randomUUID().toString();
      Instant claimedAt = Instant.now();
      LockCandidate claim =
          new LockCandidate(
              lockId, true, claimedAt, claimedAt, repository.nextVersion(), describeThisProcess());

      repository.insert(claim);
      Thread.sleep(changeLogLockRecheckMillis);

      List<LockCandidate> rows = repository.readAll();
      if (arbiter.hasWon(rows, lockId, Instant.now())) {
        hasLock = true;
        heldLockId = lockId;
        heldClaimedAt = claimedAt;
        warnIfPreempting(rows);
        startHeartbeat(lockId, claimedAt);
        return true;
      }

      withdraw(lockId, claimedAt, claim.lockedBy());
      return false;

    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new LockException(
          "Interrupted while acquiring the ClickHouse changelog lock", interrupted);
    } catch (DatabaseException failure) {
      throw new LockException(failure);
    }
  }

  @Override
  public void waitForLock() throws LockException {
    long deadline = System.currentTimeMillis() + changeLogLockWaitMillis;

    while (System.currentTimeMillis() < deadline) {
      if (acquireLock()) {
        return;
      }
      try {
        long recheckMillis = Math.max(changeLogLockRecheckMillis, 0L);
        long jitter = ThreadLocalRandom.current().nextLong(recheckMillis + 1);
        Thread.sleep(recheckMillis + jitter);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new LockException(
            "Interrupted while waiting for the ClickHouse changelog lock", interrupted);
      }
    }

    throw new LockException(
        "Could not acquire the ClickHouse changelog lock within "
            + changeLogLockWaitMillis
            + " ms. Another migration is in progress, or a previous run left a stale lock;"
            + " inspect DATABASECHANGELOGLOCK and use liquibase releaseLocks if needed.");
  }

  @Override
  public void releaseLock() throws LockException {
    if (!hasLock) {
      return;
    }
    if (lockingDisabled()) {
      hasLock = false;
      return;
    }

    stopHeartbeat();

    try {
      Optional<LockCandidate> holder = arbiter.currentHolder(repository.readAll(), Instant.now());
      if (holder.isEmpty() || !holder.get().lockId().equals(heldLockId)) {
        Scope.getCurrentScope()
            .getLog(ClickHouseLockService.class)
            .severe(
                "Releasing the ClickHouse changelog lock (lockId "
                    + heldLockId
                    + "), but it is no longer the current holder; the current holder is "
                    + holder.map(LockCandidate::lockedBy).orElse("nobody")
                    + ". This migration most likely overlapped with another one.");
      }

      repository.insert(
          new LockCandidate(
              heldLockId,
              false,
              heldClaimedAt,
              Instant.now(),
              repository.nextVersion(),
              describeThisProcess()));
    } catch (DatabaseException failure) {
      throw new LockException(failure);
    } finally {
      hasLock = false;
      heldLockId = null;
      heldClaimedAt = null;
    }
  }

  @Override
  public DatabaseChangeLogLock[] listLocks() throws LockException {
    if (lockingDisabled()) {
      return new DatabaseChangeLogLock[0];
    }

    try {
      Optional<LockCandidate> holder = arbiter.currentHolder(repository.readAll(), Instant.now());

      return holder
          .map(
              candidate ->
                  new DatabaseChangeLogLock[] {
                    new DatabaseChangeLogLock(
                        1, Date.from(candidate.claimedAt()), candidate.lockedBy())
                  })
          .orElseGet(() -> new DatabaseChangeLogLock[0]);

    } catch (DatabaseException failure) {
      throw new LockException(failure);
    }
  }

  @Override
  public void forceReleaseLock() throws LockException, DatabaseException {
    if (lockingDisabled()) {
      hasLock = false;
      heldLockId = null;
      heldClaimedAt = null;
      return;
    }

    stopHeartbeat();
    repository.createTableIfMissing();

    for (LockCandidate candidate : repository.readAll()) {
      if (candidate.locked()) {
        repository.insert(
            new LockCandidate(
                candidate.lockId(),
                false,
                candidate.claimedAt(),
                Instant.now(),
                repository.nextVersion(),
                describeThisProcess()));
      }
    }
    hasLock = false;
    heldLockId = null;
    heldClaimedAt = null;
  }

  @Override
  public void reset() {
    stopHeartbeat();
    hasLock = false;
    heldLockId = null;
    heldClaimedAt = null;
  }

  @Override
  public void destroy() throws DatabaseException {
    stopHeartbeat();
    repository.dropTable();
    reset();
  }

  /**
   * Retries a losing claim's withdrawal a few times before giving up. If it never succeeds, the
   * claim row stays behind and every contender treats it as the holder until it goes stale, so an
   * operator needs to be told to clear it by hand.
   */
  private void withdraw(String lockId, Instant claimedAt, String lockedBy) {
    DatabaseException lastFailure = null;
    for (int attempt = 1; attempt <= WITHDRAW_MAX_ATTEMPTS; attempt++) {
      try {
        repository.insert(
            new LockCandidate(
                lockId, false, claimedAt, Instant.now(), repository.nextVersion(), lockedBy));
        return;
      } catch (DatabaseException failure) {
        lastFailure = failure;
      }
    }
    Scope.getCurrentScope()
        .getLog(ClickHouseLockService.class)
        .severe(
            "Failed to withdraw the losing ClickHouse changelog lock claim (lockId "
                + lockId
                + ") after "
                + WITHDRAW_MAX_ATTEMPTS
                + " attempts. It will block every other contender until it goes stale; run"
                + " liquibase releaseLocks to clear it.",
            lastFailure);
  }

  private void warnIfPreempting(List<LockCandidate> rows) {
    rows.stream()
        .filter(LockCandidate::locked)
        .filter(candidate -> arbiter.isStale(candidate, Instant.now()))
        .forEach(
            stale ->
                Scope.getCurrentScope()
                    .getLog(ClickHouseLockService.class)
                    .warning(
                        "Preempting a stale ClickHouse changelog lock held by "
                            + stale.lockedBy()
                            + ", claimed at "
                            + stale.claimedAt()
                            + " and last renewed at "
                            + stale.renewedAt()
                            + ". The previous migration most likely crashed. Verify that the"
                            + " schema is in the state you expect."));
  }

  /**
   * Starts the background thread that keeps this holder's claim from going stale while the
   * migration is still running.
   */
  private void startHeartbeat(String lockId, Instant claimedAt) {
    long periodMillis =
        Math.max(
            1000L,
            Duration.ofSeconds(ClickHouseConfiguration.LOCK_TIMEOUT_SECONDS.getCurrentValue())
                    .toMillis()
                / 3);

    Thread thread =
        new Thread(
            () -> heartbeatLoop(lockId, claimedAt, periodMillis),
            "clickhouse-changelog-lock-heartbeat-" + lockId);
    thread.setDaemon(true);
    heartbeatThread = thread;
    thread.start();
  }

  private void stopHeartbeat() {
    Thread thread = heartbeatThread;
    heartbeatThread = null;
    if (thread != null) {
      thread.interrupt();
    }
  }

  private void heartbeatLoop(String lockId, Instant claimedAt, long periodMillis) {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        Thread.sleep(periodMillis);
        if (!renew(lockId, claimedAt)) {
          return;
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Re-inserts the holder's row with a fresh renewedAt and a new version. Returns false once this
   * lockId is no longer the current holder, which stops the heartbeat: re-inserting past that point
   * would only fight with whoever took the lock over.
   */
  private boolean renew(String lockId, Instant claimedAt) {
    try {
      Instant now = Instant.now();
      Optional<LockCandidate> holder = arbiter.currentHolder(repository.readAll(), now);
      if (holder.isEmpty() || !holder.get().lockId().equals(lockId)) {
        Scope.getCurrentScope()
            .getLog(ClickHouseLockService.class)
            .severe(
                "The ClickHouse changelog lock (lockId "
                    + lockId
                    + ") was taken over by another process while this migration was still"
                    + " running. The migration may have overlapped with another; verify the"
                    + " schema is in the state you expect.");
        return false;
      }

      repository.insert(
          new LockCandidate(
              lockId, true, claimedAt, now, repository.nextVersion(), describeThisProcess()));
      return true;
    } catch (DatabaseException failure) {
      Scope.getCurrentScope()
          .getLog(ClickHouseLockService.class)
          .severe("Failed to renew the ClickHouse changelog lock (lockId " + lockId + ")", failure);
      return true;
    }
  }

  private boolean lockingDisabled() {
    return !Boolean.TRUE.equals(ClickHouseConfiguration.LOCK_ENABLED.getCurrentValue());
  }

  private static String describeThisProcess() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException unknown) {
      host = "unknown-host";
    }
    return host + " (" + ProcessHandle.current().pid() + ")";
  }
}
