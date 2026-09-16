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

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import liquibase.Scope;
import liquibase.exception.DatabaseException;

/**
 * Retries a lock-table write that fails with ClickHouse error 286
 * (UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE).
 *
 * <p>With {@code insert_quorum_parallel} disabled, ClickHouse allows only one in-flight quorum
 * insert per table and rejects a second one outright with this error instead of queuing it. For the
 * lock table that rejection is the contention signal between racing contenders, not a genuine
 * failure: it is transient by construction, since the next attempt succeeds once the in-flight
 * insert completes.
 */
final class QuorumWriteRetry {

  static final int MAX_ATTEMPTS = 12;
  static final long BASE_DELAY_MILLIS = 100L;
  static final long MAX_DELAY_MILLIS = 3000L;

  /** ClickHouse's numeric error code for UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE. */
  private static final int UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE = 286;

  @FunctionalInterface
  interface Write {
    void run() throws DatabaseException;
  }

  private QuorumWriteRetry() {}

  /**
   * Runs {@code write} up to {@link #MAX_ATTEMPTS} times, retrying only on error 286 with a short,
   * jittered backoff that doubles each attempt up to {@link #MAX_DELAY_MILLIS}. A non-quorum
   * failure, or the last attempt's failure, is rethrown as-is rather than swallowed.
   */
  static void execute(Write write) throws DatabaseException {
    execute(write, MAX_ATTEMPTS, BASE_DELAY_MILLIS);
  }

  /** Package-private for tests: lets a test shrink the attempt count and delay. */
  static void execute(Write write, int maxAttempts, long baseDelayMillis) throws DatabaseException {
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        write.run();
        return;
      } catch (DatabaseException failure) {
        if (attempt == maxAttempts || !isQuorumContention(failure)) {
          throw failure;
        }
        Scope.getCurrentScope()
            .getLog(QuorumWriteRetry.class)
            .fine(
                "Lock-table write hit quorum contention (error 286) on attempt "
                    + attempt
                    + " of "
                    + maxAttempts
                    + "; retrying.");
        sleepWithJitter(attempt, baseDelayMillis);
      }
    }
  }

  /**
   * Unwraps {@code failure}'s cause chain looking for a {@link SQLException} carrying ClickHouse's
   * own vendor code for UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE; confirmed against a real 26.8 server
   * (via the clickhouse-jdbc 0.10.0 driver) that {@link SQLException#getErrorCode()} does carry it.
   */
  static boolean isQuorumContention(DatabaseException failure) {
    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException
          && sqlException.getErrorCode() == UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE) {
        return true;
      }
    }
    return false;
  }

  /** Full jitter: sleeps a random duration between 0 and the exponential-backoff ceiling. */
  private static void sleepWithJitter(int attempt, long baseDelayMillis) {
    long ceilingMillis =
        Math.min(baseDelayMillis * (1L << Math.min(attempt - 1, 20)), MAX_DELAY_MILLIS);
    long delayMillis = ThreadLocalRandom.current().nextLong(ceilingMillis + 1);
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
