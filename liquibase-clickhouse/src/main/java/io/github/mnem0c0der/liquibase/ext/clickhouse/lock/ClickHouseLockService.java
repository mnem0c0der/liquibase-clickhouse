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
 *
 * <p>Every override declares the narrow {@code throws DatabaseException}: that keeps this class
 * compiling against Liquibase 4.31.1 while remaining a valid override against 5.0.x, where the same
 * methods are declared with the wider {@code throws LiquibaseException}.
 */
public class ClickHouseLockService implements LockService {

  private Database database;
  private LockRepository repository;
  private OptimisticLockArbiter arbiter;

  private volatile boolean hasLock;
  private String heldLockId;

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
      LockCandidate claim =
          new LockCandidate(
              lockId, true, Instant.now(), repository.nextVersion(), describeThisProcess());

      repository.insert(claim);
      Thread.sleep(changeLogLockRecheckMillis);

      List<LockCandidate> rows = repository.readAll();
      if (arbiter.hasWon(rows, lockId, Instant.now())) {
        hasLock = true;
        heldLockId = lockId;
        warnIfPreempting(rows);
        return true;
      }

      retract(claim);
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
        long jitter = ThreadLocalRandom.current().nextLong(changeLogLockRecheckMillis + 1);
        Thread.sleep(changeLogLockRecheckMillis + jitter);
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

    try {
      repository.insert(
          new LockCandidate(
              heldLockId, false, Instant.now(), repository.nextVersion(), describeThisProcess()));
    } catch (DatabaseException failure) {
      throw new LockException(failure);
    } finally {
      hasLock = false;
      heldLockId = null;
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
                        1, Date.from(candidate.grantedAt()), candidate.lockedBy())
                  })
          .orElseGet(() -> new DatabaseChangeLogLock[0]);

    } catch (DatabaseException failure) {
      throw new LockException(failure);
    }
  }

  @Override
  public void forceReleaseLock() throws LockException, DatabaseException {
    repository.createTableIfMissing();

    for (LockCandidate candidate : repository.readAll()) {
      if (candidate.locked()) {
        repository.insert(
            new LockCandidate(
                candidate.lockId(),
                false,
                Instant.now(),
                repository.nextVersion(),
                describeThisProcess()));
      }
    }
    hasLock = false;
    heldLockId = null;
  }

  @Override
  public void reset() {
    hasLock = false;
    heldLockId = null;
  }

  @Override
  public void destroy() throws DatabaseException {
    repository.dropTable();
    reset();
  }

  private void retract(LockCandidate claim) throws DatabaseException {
    repository.insert(
        new LockCandidate(
            claim.lockId(), false, Instant.now(), repository.nextVersion(), claim.lockedBy()));
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
                            + " since "
                            + stale.grantedAt()
                            + ". The previous migration most likely crashed. Verify that the"
                            + " schema is in the state you expect."));
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
