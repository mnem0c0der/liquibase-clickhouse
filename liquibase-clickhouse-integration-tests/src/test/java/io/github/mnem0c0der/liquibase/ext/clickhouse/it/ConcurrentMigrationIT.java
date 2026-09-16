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
package io.github.mnem0c0der.liquibase.ext.clickhouse.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * Proves the one property the whole lock design exists for: several migrations starting at once
 * against the same database each apply every changeset exactly once.
 *
 * <p>{@link #appliesEveryChangeSetExactlyOnceUnderConcurrentMigrations()} is the project's most
 * important test. Eight migrators are released together off a {@link CyclicBarrier} so the lock
 * table is actually contended rather than merely started close in time; without the barrier,
 * threads scheduled apart by the OS could each win an uncontested claim and the race would not
 * occur. Both the changelog and the data it seeds are checked, because a changeset id recorded once
 * while its insert ran twice is a lock failure the changelog table alone cannot reveal.
 */
class ConcurrentMigrationIT {

  private static final int MIGRATORS = 8;

  private static ClickHouseContainer clickhouse;

  @BeforeAll
  static void startClickHouse() {
    clickhouse = ClickHouseTestSupport.startServer();
  }

  @AfterAll
  static void stopClickHouse() {
    if (clickhouse != null) {
      clickhouse.stop();
    }
  }

  @Test
  void appliesEveryChangeSetExactlyOnceUnderConcurrentMigrations() throws Exception {
    CyclicBarrier startLine = new CyclicBarrier(MIGRATORS);
    ExecutorService pool = Executors.newFixedThreadPool(MIGRATORS);

    try {
      List<Callable<Void>> migrators =
          IntStream.range(0, MIGRATORS)
              .<Callable<Void>>mapToObj(
                  index ->
                      () -> {
                        startLine.await(30, TimeUnit.SECONDS);
                        try (Connection connection = ClickHouseTestSupport.connect(clickhouse);
                            Liquibase liquibase =
                                ClickHouseTestSupport.openLiquibase(
                                    connection, "changelogs/full-lifecycle.xml")) {
                          liquibase.update(new Contexts(), new LabelExpression());
                        }
                        return null;
                      })
              .toList();

      for (Future<Void> result : pool.invokeAll(migrators, 5, TimeUnit.MINUTES)) {
        result.get();
      }
    } finally {
      pool.shutdownNow();
    }

    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      List<String> applied =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT ID FROM DATABASECHANGELOG FINAL ORDER BY ORDEREXECUTED");

      assertThat(applied)
          .as("every changeset must be recorded exactly once")
          .hasSize(6)
          .doesNotHaveDuplicates();

      List<String> seededRows =
          ClickHouseTestSupport.queryColumn(connection, "SELECT count() FROM events");

      assertThat(seededRows)
          .as("the seeding changeset must have inserted exactly one row")
          .containsExactly("1");

      List<String> heldLocks =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT count() FROM DATABASECHANGELOGLOCK FINAL WHERE LOCKED = 1");

      assertThat(heldLocks)
          .as(
              "every winning migrator must have released its lock; a phantom held row is exactly"
                  + " what the per-instance lock-state cache eviction used to leave behind")
          .containsExactly("0");
    }
  }

  @Test
  void releasesTheLockSoLaterMigrationsAreNotBlocked() throws Exception {
    // Liquibase#close() also closes the connection it wraps, so the migration runs on its own
    // connection and the verification below opens a fresh one rather than reusing a closed one.
    try (Connection connection = ClickHouseTestSupport.connect(clickhouse);
        Liquibase liquibase =
            ClickHouseTestSupport.openLiquibase(connection, "changelogs/full-lifecycle.xml")) {
      liquibase.update(new Contexts(), new LabelExpression());
    }

    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      List<String> heldLocks =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT count() FROM DATABASECHANGELOGLOCK FINAL WHERE LOCKED = 1");

      assertThat(heldLocks).containsExactly("0");
    }
  }
}
