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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link LockStore} for tests. One row per lockId, overwritten on every write, so {@link
 * #readAll()} always reflects the latest claim/renew/release for each contender without needing to
 * reproduce ReplacingMergeTree's version-based collapse.
 *
 * <p>{@link #armReadAllGate(CountDownLatch)} lets a test make exactly one {@code readAll()} call
 * block until released, to park the heartbeat mid-renewal on demand.
 */
final class FakeLockStore implements LockStore {

  /** One completed write, in call order, for assertions. */
  record Write(String kind, LockCandidate row) {}

  private final Map<String, LockCandidate> rowsByLockId = new ConcurrentHashMap<>();
  private final List<Write> writes = new CopyOnWriteArrayList<>();
  private final AtomicLong versionCounter = new AtomicLong();
  private final Instant now;

  private volatile CountDownLatch gateEntered;
  private volatile CountDownLatch gateRelease;

  FakeLockStore(Instant now) {
    this.now = now;
  }

  void seedLocked(
      String lockId, Instant claimedAt, Instant renewedAt, long version, String lockedBy) {
    rowsByLockId.put(
        lockId, new LockCandidate(lockId, true, claimedAt, renewedAt, version, lockedBy));
  }

  List<Write> writes() {
    return List.copyOf(writes);
  }

  /**
   * Makes the next {@code readAll()} call block until the test counts down the returned latch. The
   * block ignores interrupts, the way a real blocking socket read does, so it exercises the
   * join-timeout branch of {@code stopHeartbeat()} rather than being cut short by the interrupt.
   * {@code entered} is counted down once the call is actually parked, so the test knows it is safe
   * to proceed.
   */
  CountDownLatch armReadAllGate(CountDownLatch entered) {
    CountDownLatch release = new CountDownLatch(1);
    this.gateEntered = entered;
    this.gateRelease = release;
    return release;
  }

  @Override
  public void createTableIfMissing() {
    // No table to create.
  }

  @Override
  public void claim(String lockId, long version, String lockedBy) {
    LockCandidate row = new LockCandidate(lockId, true, now, now, version, lockedBy);
    rowsByLockId.put(lockId, row);
    writes.add(new Write("claim", row));
  }

  @Override
  public void renew(String lockId, Instant claimedAt, long version, String lockedBy) {
    LockCandidate row = new LockCandidate(lockId, true, claimedAt, now, version, lockedBy);
    rowsByLockId.put(lockId, row);
    writes.add(new Write("renew", row));
  }

  @Override
  public void release(String lockId, Instant claimedAt, long version, String lockedBy) {
    LockCandidate row = new LockCandidate(lockId, false, claimedAt, now, version, lockedBy);
    rowsByLockId.put(lockId, row);
    writes.add(new Write("release", row));
  }

  @Override
  public LockSnapshot readAll() {
    blockIfGated();
    return new LockSnapshot(List.copyOf(rowsByLockId.values()), now);
  }

  @Override
  public long nextVersion(List<LockCandidate> rows) {
    return versionCounter.incrementAndGet();
  }

  @Override
  public long nextVersion() {
    return nextVersion(readAll().rows());
  }

  @Override
  public void dropTable() {
    rowsByLockId.clear();
  }

  private void blockIfGated() {
    CountDownLatch entered = gateEntered;
    CountDownLatch release = gateRelease;
    gateEntered = null;
    gateRelease = null;
    if (release == null) {
      return;
    }
    if (entered != null) {
      entered.countDown();
    }
    boolean waiting = true;
    while (waiting) {
      try {
        release.await();
        waiting = false;
      } catch (InterruptedException ignored) {
        // A real blocking socket read typically does not respond to interrupt(); simulate that so
        // this drives stopHeartbeat()'s join-timeout branch instead of the interrupt path.
      }
    }
  }
}
