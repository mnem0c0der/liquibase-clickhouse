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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OptimisticLockArbiterTest {

  private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
  private final OptimisticLockArbiter arbiter = new OptimisticLockArbiter(Duration.ofMinutes(5));

  private static LockCandidate claim(String lockId, long version, Instant grantedAt) {
    return new LockCandidate(lockId, true, grantedAt, version, "host/" + lockId);
  }

  private static LockCandidate release(String lockId, long version) {
    return new LockCandidate(lockId, false, NOW, version, "host/" + lockId);
  }

  @Test
  void noRowsMeansNobodyHoldsTheLock() {
    assertThat(arbiter.currentHolder(List.of(), NOW)).isEmpty();
  }

  @Test
  void aSingleClaimantHoldsTheLock() {
    assertThat(arbiter.currentHolder(List.of(claim("a", 1, NOW)), NOW))
        .map(LockCandidate::lockId)
        .contains("a");
  }

  @Test
  void theEarliestClaimWins() {
    List<LockCandidate> rows = List.of(claim("b", 2, NOW), claim("a", 1, NOW));

    assertThat(arbiter.currentHolder(rows, NOW)).map(LockCandidate::lockId).contains("a");
    assertThat(arbiter.hasWon(rows, "a", NOW)).isTrue();
    assertThat(arbiter.hasWon(rows, "b", NOW)).isFalse();
  }

  @Test
  void identicalVersionsAreBrokenByLockIdSoExactlyOneProcessWins() {
    List<LockCandidate> rows = List.of(claim("zzz", 7, NOW), claim("aaa", 7, NOW));

    assertThat(arbiter.hasWon(rows, "aaa", NOW)).isTrue();
    assertThat(arbiter.hasWon(rows, "zzz", NOW)).isFalse();
  }

  @Test
  void aLaterReleaseRowRetractsAnEarlierClaim() {
    List<LockCandidate> rows = List.of(claim("a", 1, NOW), release("a", 2), claim("b", 3, NOW));

    assertThat(arbiter.currentHolder(rows, NOW)).map(LockCandidate::lockId).contains("b");
  }

  @Test
  void anOlderClaimDoesNotResurrectAReleasedLock() {
    List<LockCandidate> rows = List.of(release("a", 5), claim("a", 4, NOW));

    assertThat(arbiter.currentHolder(rows, NOW)).isEmpty();
  }

  @Test
  void aStaleClaimIsIgnoredSoACrashedMigrationCannotDeadlockTheDatabase() {
    LockCandidate abandoned = claim("crashed", 1, NOW.minus(Duration.ofHours(1)));
    LockCandidate fresh = claim("healthy", 2, NOW);

    assertThat(arbiter.isStale(abandoned, NOW)).isTrue();
    assertThat(arbiter.isStale(fresh, NOW)).isFalse();
    assertThat(arbiter.currentHolder(List.of(abandoned, fresh), NOW))
        .map(LockCandidate::lockId)
        .contains("healthy");
  }

  @Test
  void aClaimExactlyAtTheStalenessBoundaryIsStillValid() {
    LockCandidate borderline = claim("a", 1, NOW.minus(Duration.ofMinutes(5)));

    assertThat(arbiter.isStale(borderline, NOW)).isFalse();
  }
}
