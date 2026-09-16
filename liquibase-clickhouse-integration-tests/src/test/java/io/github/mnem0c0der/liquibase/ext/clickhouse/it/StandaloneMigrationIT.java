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
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * Runs a full changelog against a real ClickHouse server started with Testcontainers.
 *
 * <p>One container is shared across the test methods. {@link Liquibase#close()} closes the
 * underlying JDBC connection along with the {@code Database} it wraps, so a migration always runs
 * on its own connection ({@link #runMigration()}) and every verification query opens a fresh one
 * rather than reusing the connection Liquibase already closed. The container's DATABASECHANGELOG
 * persists across connections, so re-running the changelog from a later test is a no-op and the
 * tests stay independent of execution order.
 */
class StandaloneMigrationIT {

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

  private static void runMigration() throws Exception {
    try (Connection connection = ClickHouseTestSupport.connect(clickhouse);
        Liquibase liquibase =
            ClickHouseTestSupport.openLiquibase(connection, "changelogs/full-lifecycle.xml")) {
      liquibase.update(new Contexts(), new LabelExpression());
    }
  }

  @Test
  void appliesAFullChangelogAndRecordsEveryChangeSet() throws Exception {
    runMigration();

    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      List<String> applied =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT ID FROM DATABASECHANGELOG FINAL ORDER BY ORDEREXECUTED");

      assertThat(applied)
          .containsExactly(
              "1-create-events", "2-add-country", "3-seed", "4-rename-column", "5-index", "6-tag");
    }
  }

  @Test
  void createsTheTableWithAMergeTreeEngine() throws Exception {
    runMigration();

    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      List<String> engines =
          ClickHouseTestSupport.queryColumn(
              connection,
              "SELECT engine FROM system.tables WHERE name = 'events' AND database ="
                  + " currentDatabase()");

      assertThat(engines).containsExactly("MergeTree");
    }
  }

  @Test
  void keepsNullableColumnsNullableAndNotNullColumnsPlain() throws Exception {
    runMigration();

    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      List<String> types =
          ClickHouseTestSupport.queryColumn(
              connection,
              "SELECT type FROM system.columns WHERE table = 'events'"
                  + " AND database = currentDatabase() AND name IN ('id', 'name')"
                  + " ORDER BY name");

      // ORDER BY name sorts by the column's own name ('id' before 'name'), not by its type.
      assertThat(types).containsExactly("Int64", "Nullable(String)");
    }
  }

  @Test
  void isIdempotentWhenRunTwice() throws Exception {
    runMigration();
    runMigration();

    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      List<String> applied =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT ID FROM DATABASECHANGELOG FINAL ORDER BY ORDEREXECUTED");

      assertThat(applied).hasSize(6).doesNotHaveDuplicates();
    }
  }

  @Test
  void recordsTheTagOnTheLastChangeSet() throws Exception {
    runMigration();

    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      List<String> tags =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT TAG FROM DATABASECHANGELOG FINAL WHERE TAG IS NOT NULL");

      assertThat(tags).contains("v1");
    }
  }
}
