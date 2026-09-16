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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import liquibase.exception.DatabaseException;
import org.junit.jupiter.api.Test;

class QuorumWriteRetryTest {

  private static final int TEST_MAX_ATTEMPTS = 5;
  private static final long TEST_BASE_DELAY_MILLIS = 1L;

  private static DatabaseException quorumFailure() {
    return new DatabaseException(
        "Error executing SQL",
        new SQLException("Another quorum insert has been already started", "22000", 286));
  }

  private static DatabaseException otherFailure() {
    return new DatabaseException(
        "Error executing SQL", new SQLException("Table does not exist", "22000", 60));
  }

  @Test
  void retriesOnSimulatedCode286AndThenSucceeds() throws DatabaseException {
    AtomicInteger calls = new AtomicInteger();
    QuorumWriteRetry.execute(
        () -> {
          if (calls.incrementAndGet() < 3) {
            throw quorumFailure();
          }
        },
        TEST_MAX_ATTEMPTS,
        TEST_BASE_DELAY_MILLIS);

    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void surfacesTheFailureOnceAttemptsAreExhausted() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                QuorumWriteRetry.execute(
                    () -> {
                      calls.incrementAndGet();
                      throw quorumFailure();
                    },
                    TEST_MAX_ATTEMPTS,
                    TEST_BASE_DELAY_MILLIS))
        .isInstanceOf(DatabaseException.class)
        .hasMessageContaining("Error executing SQL");

    assertThat(calls.get()).isEqualTo(TEST_MAX_ATTEMPTS);
  }

  @Test
  void doesNotRetryASqlExceptionThatIsNotQuorumContention() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                QuorumWriteRetry.execute(
                    () -> {
                      calls.incrementAndGet();
                      throw otherFailure();
                    },
                    TEST_MAX_ATTEMPTS,
                    TEST_BASE_DELAY_MILLIS))
        .isInstanceOf(DatabaseException.class);

    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void isQuorumContentionUnwrapsTheCauseChainForCode286() {
    assertThat(QuorumWriteRetry.isQuorumContention(quorumFailure())).isTrue();
    assertThat(QuorumWriteRetry.isQuorumContention(otherFailure())).isFalse();
  }
}
