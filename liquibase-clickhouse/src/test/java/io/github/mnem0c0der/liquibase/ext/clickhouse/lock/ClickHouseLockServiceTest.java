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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import liquibase.Scope;
import liquibase.database.core.PostgresDatabase;
import liquibase.lockservice.LockService;
import org.junit.jupiter.api.Test;

class ClickHouseLockServiceTest {

  @Test
  void isRegisteredAsAServiceProvider() {
    assertThat(ServiceLoader.load(LockService.class))
        .anyMatch(ClickHouseLockService.class::isInstance);
  }

  @Test
  void supportsOnlyClickHouse() {
    ClickHouseLockService service = new ClickHouseLockService();

    assertThat(service.supports(new ClickHouseDatabase())).isTrue();
    assertThat(service.supports(new PostgresDatabase())).isFalse();
  }

  @Test
  void outranksTheStandardLockService() {
    assertThat(new ClickHouseLockService().getPriority())
        .isGreaterThan(new liquibase.lockservice.StandardLockService().getPriority());
  }

  @Test
  void acquiresImmediatelyAndWithoutTouchingTheDatabaseWhenLockingIsDisabled() throws Exception {
    ClickHouseLockService service = new ClickHouseLockService();
    service.setDatabase(new ClickHouseDatabase());

    Boolean acquired =
        Scope.child(
            Map.of(ClickHouseConfiguration.LOCK_ENABLED.getKey(), "false"),
            () -> service.acquireLock());

    assertThat(acquired).isTrue();
    assertThat(service.hasChangeLogLock()).isTrue();
  }

  @Test
  void stopHeartbeatLeavesNoLiveHeartbeatThreadBehind() throws Exception {
    ClickHouseLockService service = new ClickHouseLockService();
    service.setDatabase(new ClickHouseDatabase());

    // The default lock.timeoutSeconds (300s) gives the heartbeat a ~100s sleep before its first
    // renewal round trip, so stopping it immediately below exercises the interrupt-while-sleeping
    // path only, without ever touching the (absent) database.
    service.startHeartbeat("test-lock-id", Instant.now());
    Thread heartbeat = service.currentHeartbeatThread();
    assertThat(heartbeat).isNotNull();
    assertThat(heartbeat.isAlive()).isTrue();

    service.stopHeartbeat();

    // stopHeartbeat() already joins internally; this join is a second, independent check that the
    // thread has actually terminated rather than merely been asked to.
    heartbeat.join(Duration.ofSeconds(2).toMillis());
    assertThat(heartbeat.getState()).isEqualTo(Thread.State.TERMINATED);
    assertThat(heartbeat.isAlive()).isFalse();
    assertThat(service.currentHeartbeatThread()).isNull();
  }

  /**
   * Reproduces the race in Finding 1: a renewal stuck inside {@code readAll()} (an unresponsive
   * socket read, which {@code interrupt()} cannot break) outlives {@code stopHeartbeat()}'s join
   * timeout. If {@code heartbeatStopping} were cleared once that timeout elapses (the previous
   * round's bug), the zombie renewal would see it as false once {@code readAll()} finally returns
   * and would write a fresh, higher-versioned lock row on top of a release that has already been
   * computed. The fix keeps the flag set until a new heartbeat cycle actually starts, so the zombie
   * must back out without writing anything.
   */
  @Test
  void stoppedHeartbeatThatOutlivesItsJoinTimeoutStillSuppressesTheZombieRenewal()
      throws Exception {
    ClickHouseLockService service = new ClickHouseLockService();
    service.setDatabase(new ClickHouseDatabase());
    service.setHeartbeatStopTimeoutMillisForTesting(200);

    Instant claimedAt = Instant.now();
    String lockId = "zombie-lock";
    FakeLockStore store = new FakeLockStore(claimedAt);
    store.seedLocked(lockId, claimedAt, claimedAt, 1, "host");
    service.setLockStoreForTesting(store);

    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = store.armReadAllGate(entered);

    // lock.timeoutSeconds = 1 makes the heartbeat period the computed 1000ms floor, so the first
    // renewal round trip starts almost immediately instead of after the production ~100s default.
    Scope.child(
        Map.of(ClickHouseConfiguration.LOCK_TIMEOUT_SECONDS.getKey(), "1"),
        () -> service.startHeartbeat(lockId, claimedAt));

    assertThat(entered.await(5, TimeUnit.SECONDS))
        .as("the heartbeat's renewal must have entered readAll()")
        .isTrue();
    Thread heartbeat = service.currentHeartbeatThread();

    // The zombie is still stuck in readAll(), so this join cannot succeed within the shortened
    // timeout above; stopHeartbeat() must give up and return with the thread still alive.
    service.stopHeartbeat();

    // Let the zombie's readAll() return, as if the socket read had finally completed.
    release.countDown();

    long deadline = System.currentTimeMillis() + 2000;
    while (store.writes().isEmpty()
        && heartbeat.isAlive()
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }

    try {
      assertThat(store.writes())
          .as("the zombie renewal must perform no write once it resumes after the timed-out join")
          .isEmpty();
    } finally {
      // Cleanup: interrupt the now-sleeping loop (in the buggy case it survives and keeps
      // renewing) so it doesn't linger for the rest of the suite.
      service.stopHeartbeat();
    }
  }

  @Test
  void acquireLockWinsWhenNoOtherContenderExists() throws Exception {
    ClickHouseLockService service = new ClickHouseLockService();
    service.setDatabase(new ClickHouseDatabase());
    service.setChangeLogLockRecheckTime(0);
    FakeLockStore store = new FakeLockStore(Instant.now());
    service.setLockStoreForTesting(store);

    try {
      assertThat(service.acquireLock()).isTrue();
      assertThat(service.hasChangeLogLock()).isTrue();
    } finally {
      service.stopHeartbeat();
    }
  }

  @Test
  void acquireLockLosesToAnEarlierClaimAndWithdraws() throws Exception {
    ClickHouseLockService service = new ClickHouseLockService();
    service.setDatabase(new ClickHouseDatabase());
    service.setChangeLogLockRecheckTime(0);

    Instant now = Instant.now();
    FakeLockStore store = new FakeLockStore(now);
    store.seedLocked("earlier-contender", now.minusSeconds(60), now, 1, "other-host");
    service.setLockStoreForTesting(store);

    assertThat(service.acquireLock()).isFalse();
    assertThat(service.hasChangeLogLock()).isFalse();

    List<FakeLockStore.Write> writes = store.writes();
    String ourLockId =
        writes.stream()
            .filter(write -> write.kind().equals("claim"))
            .map(write -> write.row().lockId())
            .findFirst()
            .orElseThrow();

    assertThat(writes)
        .as("the losing contender's own claim must have been retracted")
        .anyMatch(
            write -> write.kind().equals("release") && write.row().lockId().equals(ourLockId));
  }

  @Test
  void releaseLockWritesAReleaseRowAndClearsLocalState() throws Exception {
    ClickHouseLockService service = new ClickHouseLockService();
    service.setDatabase(new ClickHouseDatabase());
    service.setChangeLogLockRecheckTime(0);
    FakeLockStore store = new FakeLockStore(Instant.now());
    service.setLockStoreForTesting(store);

    assertThat(service.acquireLock()).isTrue();
    String heldLockId = store.writes().get(0).row().lockId();

    service.releaseLock();

    assertThat(service.hasChangeLogLock()).isFalse();
    assertThat(store.writes())
        .anyMatch(
            write ->
                write.kind().equals("release")
                    && !write.row().locked()
                    && write.row().lockId().equals(heldLockId));
  }

  @Test
  void releaseLockWhenNoLongerTheHolderStillWritesItsOwnReleaseRow() throws Exception {
    ClickHouseLockService service = new ClickHouseLockService();
    service.setDatabase(new ClickHouseDatabase());
    service.setChangeLogLockRecheckTime(0);

    Instant now = Instant.now();
    FakeLockStore store = new FakeLockStore(now);
    service.setLockStoreForTesting(store);

    assertThat(service.acquireLock()).isTrue();
    String heldLockId = store.writes().get(0).row().lockId();

    // Another contender takes over: an earlier claimedAt always outranks ours.
    store.seedLocked("takeover", now.minusSeconds(120), now, 1, "other-host");

    service.releaseLock();

    assertThat(service.hasChangeLogLock()).isFalse();
    assertThat(store.writes())
        .as("releasing must still write this host's own release row even after losing the lock")
        .anyMatch(
            write ->
                write.kind().equals("release")
                    && !write.row().locked()
                    && write.row().lockId().equals(heldLockId));
  }
}
