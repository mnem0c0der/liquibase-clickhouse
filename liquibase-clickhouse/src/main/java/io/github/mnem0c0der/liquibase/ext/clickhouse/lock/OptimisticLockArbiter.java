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

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decides the lock holder from a set of candidate rows.
 *
 * <p>Deliberately independent of Liquibase and JDBC: all the concurrency logic is verified with
 * plain unit tests, without a database and without reproducing races.
 */
public final class OptimisticLockArbiter {

  private static final Comparator<LockCandidate> EARLIEST_THEN_SMALLEST_ID =
      Comparator.comparing(LockCandidate::claimedAt).thenComparing(LockCandidate::lockId);

  private final Duration staleAfter;

  public OptimisticLockArbiter(Duration staleAfter) {
    this.staleAfter = staleAfter;
  }

  public boolean isStale(LockCandidate candidate, Instant now) {
    return candidate.renewedAt().plus(staleAfter).isBefore(now);
  }

  /**
   * Returns the current lock holder, if any.
   *
   * <p>Rows are first collapsed per lockId, keeping the highest version, so a release always
   * supersedes an earlier claim even if the storage engine has not merged them yet.
   */
  public Optional<LockCandidate> currentHolder(Collection<LockCandidate> rows, Instant now) {
    Map<String, LockCandidate> latestPerLockId =
        rows.stream()
            .collect(
                Collectors.toMap(
                    LockCandidate::lockId,
                    Function.identity(),
                    (left, right) -> left.version() >= right.version() ? left : right));

    return latestPerLockId.values().stream()
        .filter(LockCandidate::locked)
        .filter(candidate -> !isStale(candidate, now))
        .min(EARLIEST_THEN_SMALLEST_ID);
  }

  public boolean hasWon(Collection<LockCandidate> rows, String myLockId, Instant now) {
    return currentHolder(rows, now).map(holder -> holder.lockId().equals(myLockId)).orElse(false);
  }
}
