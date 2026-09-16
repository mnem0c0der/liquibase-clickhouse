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
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>Liquibase's own {@code LockServiceFactory} is a process-wide singleton whose lock-service
 * cache is dropped (via {@code resetAll()}) after every single command, including a successful one.
 * When several {@code Liquibase.update()} calls run concurrently in the same JVM, one thread
 * finishing its command can evict the cache while another thread still holds the lock; that other
 * thread's own {@code releaseLock()} call then lands on a brand-new {@code ClickHouseLockService}
 * instance with no memory of what was claimed. Tracking the held claim and heartbeat thread in
 * {@link #DATABASE_LOCKS}, keyed by the {@link Database} instance rather than by {@code this}, lets
 * whichever instance Liquibase hands back still find and release the real claim.
 */
public class ClickHouseLockService implements LockService {

  private static final int WITHDRAW_MAX_ATTEMPTS = 3;
  private static final long WITHDRAW_RETRY_DELAY_MILLIS = 200L;

  /**
   * Per-database lock state, keyed by object identity ({@link Database} declares no {@code
   * equals}/{@code hashCode}, so the default identity semantics are exactly what is needed: two
   * different connections must never share a slot). Survives {@code ClickHouseLockService}
   * instances being discarded and recreated by Liquibase's own cache, so a claim made by one
   * instance can always be released by whichever instance a later call is dispatched to. Entries
   * are removed once a claim is fully resolved (released, force-released, or destroyed); a claim
   * abandoned by a crashed JVM cannot leak past that JVM's own lifetime.
   */
  private static final ConcurrentHashMap<Database, LockState> DATABASE_LOCKS =
      new ConcurrentHashMap<>();

  /** Mutable state for one database's currently held (or in-flight) claim. */
  private static final class LockState {
    private volatile boolean hasLock;
    private volatile String heldLockId;
    private volatile Instant heldClaimedAt;
    private volatile Thread heartbeatThread;

    /**
     * Set by {@code stopHeartbeat()} before it interrupts the heartbeat thread, so a renewal
     * already past its sleep and about to write can still back out instead of re-locking the table
     * after the release has been computed.
     *
     * <p>Only {@code startHeartbeat()} clears it, when a new heartbeat cycle actually begins.
     * {@code stopHeartbeat()} must never clear it itself: if its join times out, the thread is
     * still running and still needs to see this as true whenever it eventually reaches the check,
     * not just for the duration of one join.
     */
    private volatile boolean heartbeatStopping;
  }

  /**
   * Bound on how long {@code stopHeartbeat()} waits for the heartbeat thread to actually exit. A
   * renewal is at most two lightweight round trips, so this is generous; if it is ever exceeded the
   * thread is abandoned rather than blocking a release forever, and a warning names it. Not final
   * so a test can shrink it instead of forcing the join to actually wait out the real timeout.
   */
  private long heartbeatStopTimeoutMillis = Duration.ofSeconds(10).toMillis();

  private Database database;
  private LockStore repository;
  private OptimisticLockArbiter arbiter;
  private LockState state;

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
    this.state = DATABASE_LOCKS.computeIfAbsent(database, ignored -> new LockState());
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
    return state.hasLock;
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
    if (state.hasLock) {
      return true;
    }
    if (lockingDisabled()) {
      state.hasLock = true;
      return true;
    }

    try {
      repository.createTableIfMissing();

      String lockId = UUID.randomUUID().toString();
      String lockedBy = describeThisProcess();
      List<LockCandidate> rowsBeforeClaim = repository.readAll().rows();
      repository.claim(lockId, repository.nextVersion(rowsBeforeClaim), lockedBy);
      Thread.sleep(changeLogLockRecheckMillis);

      LockSnapshot snapshot = repository.readAll();
      List<LockCandidate> rows = snapshot.rows();
      Instant now = snapshot.serverNow();

      if (arbiter.hasWon(rows, lockId, now)) {
        Instant claimedAt = findRow(rows, lockId).map(LockCandidate::claimedAt).orElse(now);
        state.hasLock = true;
        state.heldLockId = lockId;
        state.heldClaimedAt = claimedAt;
        warnIfPreempting(rows, now);
        // Guards against leaking a previous heartbeat thread if this instance is reused for a
        // second acquisition; ordinarily releaseLock()/reset() already stopped it.
        stopHeartbeat();
        startHeartbeat(lockId, claimedAt);
        return true;
      }

      Instant claimedAt = findRow(rows, lockId).map(LockCandidate::claimedAt).orElse(now);
      withdraw(lockId, claimedAt, lockedBy);
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
    if (!state.hasLock) {
      return;
    }
    if (lockingDisabled()) {
      state.hasLock = false;
      return;
    }

    // Stopping the heartbeat before reading the table means any renewal that was already past its
    // sleep and about to write has either backed out (heartbeatStopping) or is fully done, so the
    // read below always reflects the true last state before this release is computed.
    stopHeartbeat();

    try {
      LockSnapshot snapshot = repository.readAll();
      Optional<LockCandidate> holder = arbiter.currentHolder(snapshot.rows(), snapshot.serverNow());
      if (holder.isEmpty() || !holder.get().lockId().equals(state.heldLockId)) {
        Scope.getCurrentScope()
            .getLog(ClickHouseLockService.class)
            .severe(
                "Releasing the ClickHouse changelog lock (lockId "
                    + state.heldLockId
                    + "), but it is no longer the current holder; the current holder is "
                    + holder.map(LockCandidate::lockedBy).orElse("nobody")
                    + ". This migration most likely overlapped with another one.");
      }

      repository.release(
          state.heldLockId,
          state.heldClaimedAt,
          repository.nextVersion(snapshot.rows()),
          describeThisProcess());
    } catch (DatabaseException failure) {
      throw new LockException(failure);
    } finally {
      // Cleared even if the read above threw: this host's heartbeat is already stopped, so if no
      // release row was written the claim is simply left behind to age out on its own.
      state.hasLock = false;
      state.heldLockId = null;
      state.heldClaimedAt = null;
      DATABASE_LOCKS.remove(database, state);
    }
  }

  @Override
  public DatabaseChangeLogLock[] listLocks() throws LockException {
    if (lockingDisabled()) {
      return new DatabaseChangeLogLock[0];
    }

    try {
      LockSnapshot snapshot = repository.readAll();
      Optional<LockCandidate> holder = arbiter.currentHolder(snapshot.rows(), snapshot.serverNow());

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
      state.hasLock = false;
      state.heldLockId = null;
      state.heldClaimedAt = null;
      return;
    }

    stopHeartbeat();
    repository.createTableIfMissing();

    LockSnapshot snapshot = repository.readAll();
    for (LockCandidate candidate : snapshot.rows()) {
      if (candidate.locked()) {
        repository.release(
            candidate.lockId(),
            candidate.claimedAt(),
            repository.nextVersion(snapshot.rows()),
            describeThisProcess());
      }
    }
    state.hasLock = false;
    state.heldLockId = null;
    state.heldClaimedAt = null;
    DATABASE_LOCKS.remove(database, state);
  }

  @Override
  public void reset() {
    // LockServiceFactory.resetAll() calls reset() on the prototype instances it discovered via
    // the service loader, not on any instance that ever had setDatabase() called on it: those
    // prototypes never got a LockState, and there is nothing of theirs to clear.
    if (state == null) {
      return;
    }
    stopHeartbeat();
    state.hasLock = false;
    state.heldLockId = null;
    state.heldClaimedAt = null;
    DATABASE_LOCKS.remove(database, state);
  }

  @Override
  public void destroy() throws DatabaseException {
    stopHeartbeat();
    repository.dropTable();
    reset();
  }

  private static Optional<LockCandidate> findRow(List<LockCandidate> rows, String lockId) {
    return rows.stream().filter(candidate -> candidate.lockId().equals(lockId)).findFirst();
  }

  /** Package-private for tests: the current heartbeat thread, or null if none is running. */
  Thread currentHeartbeatThread() {
    return state.heartbeatThread;
  }

  /**
   * Package-private for tests: substitutes a fake {@link LockStore} so acquire, release, and
   * heartbeat renewal can be driven deterministically, without a live ClickHouse server. Production
   * wiring is unaffected; {@link #setDatabase(Database)} still builds a real {@link
   * LockRepository}.
   */
  void setLockStore(LockStore repository) {
    this.repository = repository;
  }

  /**
   * Package-private for tests: shrinks {@link #heartbeatStopTimeoutMillis} so a test that forces
   * {@code stopHeartbeat()}'s join to time out does not slow the suite down.
   */
  void setHeartbeatStopTimeoutMillis(long heartbeatStopTimeoutMillis) {
    this.heartbeatStopTimeoutMillis = heartbeatStopTimeoutMillis;
  }

  /**
   * Retries a losing claim's withdrawal a few times, with a short delay between attempts, before
   * giving up. If it never succeeds, the claim row stays behind and every contender treats it as
   * the holder until it goes stale, so an operator needs to be told to clear it by hand.
   */
  private void withdraw(String lockId, Instant claimedAt, String lockedBy) {
    DatabaseException lastFailure = null;
    for (int attempt = 1; attempt <= WITHDRAW_MAX_ATTEMPTS; attempt++) {
      try {
        repository.release(lockId, claimedAt, repository.nextVersion(), lockedBy);
        return;
      } catch (DatabaseException failure) {
        lastFailure = failure;
        if (attempt < WITHDRAW_MAX_ATTEMPTS) {
          try {
            Thread.sleep(WITHDRAW_RETRY_DELAY_MILLIS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            break;
          }
        }
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

  private void warnIfPreempting(List<LockCandidate> rows, Instant now) {
    rows.stream()
        .filter(LockCandidate::locked)
        .filter(candidate -> arbiter.isStale(candidate, now))
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
   *
   * <p>Package-private, not private, so a test can drive the heartbeat's lifecycle directly without
   * a live database.
   */
  void startHeartbeat(String lockId, Instant claimedAt) {
    state.heartbeatStopping = false;

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
    state.heartbeatThread = thread;
    thread.start();
  }

  /**
   * Signals the heartbeat thread to stop, interrupts it, and waits for it to actually exit before
   * returning. Waiting (rather than firing the interrupt and moving on) matters: a renewal that has
   * already finished sleeping and is mid round-trip does not observe the interrupt until its next
   * blocking call, so without a join a caller could proceed to compute a release row before that
   * in-flight renewal has backed out or finished writing.
   *
   * <p>Package-private, not private, so a test can drive the heartbeat's lifecycle directly without
   * a live database.
   */
  void stopHeartbeat() {
    if (state == null) {
      return;
    }
    Thread thread = state.heartbeatThread;
    if (thread == null) {
      return;
    }

    state.heartbeatStopping = true;
    try {
      thread.interrupt();
      thread.join(heartbeatStopTimeoutMillis);
      if (thread.isAlive()) {
        // The thread is still running and still needs heartbeatStopping to read true whenever it
        // eventually reaches the check, so it is not cleared here. heartbeatThread is also left in
        // place, unset only once a join actually observes the thread dead, so a later
        // stopHeartbeat() call (or acquireLock()'s pre-start call) can retry joining it.
        Scope.getCurrentScope()
            .getLog(ClickHouseLockService.class)
            .warning(
                "Heartbeat thread "
                    + thread.getName()
                    + " did not stop within "
                    + heartbeatStopTimeoutMillis
                    + " ms; proceeding without waiting further.");
      } else {
        state.heartbeatThread = null;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
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
   * lockId is no longer the current holder, or once the heartbeat is being stopped, either of which
   * ends the loop: re-inserting past that point would either fight with whoever took the lock over,
   * or land after {@code releaseLock()} has already computed its release row.
   */
  private boolean renew(String lockId, Instant claimedAt) {
    try {
      LockSnapshot snapshot = repository.readAll();
      Optional<LockCandidate> holder = arbiter.currentHolder(snapshot.rows(), snapshot.serverNow());
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

      if (state.heartbeatStopping) {
        return false;
      }

      repository.renew(
          lockId, claimedAt, repository.nextVersion(snapshot.rows()), describeThisProcess());
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
