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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.core.CreateDatabaseChangeLogTableStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateDatabaseChangeLogTableGeneratorClickHouseTest {

  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    database = new ClickHouseDatabase();
    database.setDefaultCatalogName("analytics");
  }

  private String generate() {
    Sql[] sql =
        SqlGeneratorFactory.getInstance()
            .generateSql(new CreateDatabaseChangeLogTableStatement(), database);
    return String.join("; ", Arrays.stream(sql).map(Sql::toSql).toList());
  }

  @Test
  void createsTheTrackingTableAsAReplacingMergeTree() {
    assertThat(generate())
        .startsWith("CREATE TABLE IF NOT EXISTS `analytics`.`DATABASECHANGELOG` (")
        .contains("`ID` String")
        .contains("`AUTHOR` String")
        .contains("`FILENAME` String")
        .contains("`DATEEXECUTED` DateTime64(3)")
        .contains("`ORDEREXECUTED` Int32")
        .contains("`EXECTYPE` String")
        .contains("`MD5SUM` Nullable(String)")
        .contains("`TAG` Nullable(String)")
        .contains("`DEPLOYMENT_ID` Nullable(String)")
        .contains("`ROWVERSION` UInt64 DEFAULT toUnixTimestamp64Milli(now64(3))")
        .endsWith(
            "ENGINE = ReplacingMergeTree(`ROWVERSION`) ORDER BY (`ID`, `AUTHOR`, `FILENAME`)");
  }

  @Test
  void usesAReplicatedEngineAndOnClusterWhenClustered() throws Exception {
    String sql =
        liquibase.Scope.child(
            java.util.Map.of("liquibase.clickhouse.cluster", "analytics_cluster"), this::generate);

    assertThat(sql)
        .contains("`DATABASECHANGELOG` ON CLUSTER `analytics_cluster` (")
        .contains("ENGINE = ReplicatedReplacingMergeTree(");
  }
}
