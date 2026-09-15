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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import java.util.List;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.AddDefaultValueStatement;
import liquibase.statement.core.DropDefaultValueStatement;
import liquibase.statement.core.InsertSetStatement;
import liquibase.statement.core.InsertStatement;
import liquibase.statement.core.SetNullableStatement;
import liquibase.statement.core.TagDatabaseStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemainingGeneratorsTest {

  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    database = new ClickHouseDatabase();
    database.setDefaultCatalogName("analytics");
  }

  private List<String> generate(SqlStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return Arrays.stream(sql).map(Sql::toSql).toList();
  }

  @Test
  void tagsTheLastChangeSetByInsertingANewRowVersion() {
    String sql = generate(new TagDatabaseStatement("v1")).get(0);

    assertThat(sql)
        .startsWith("INSERT INTO `analytics`.`DATABASECHANGELOG` SELECT * EXCEPT")
        .contains("'v1' AS `TAG`")
        .contains("FROM `analytics`.`DATABASECHANGELOG` FINAL")
        .endsWith("ORDER BY `ORDEREXECUTED` DESC LIMIT 1")
        .doesNotContainIgnoringCase("UPDATE");
  }

  @Test
  void makesAColumnNullable() {
    assertThat(
            generate(
                new SetNullableStatement("analytics", null, "events", "name", "varchar(50)", true)))
        .containsExactly("ALTER TABLE `analytics`.`events` MODIFY COLUMN `name` Nullable(String)");
  }

  @Test
  void makesAColumnNotNullable() {
    assertThat(
            generate(
                new SetNullableStatement(
                    "analytics", null, "events", "name", "varchar(50)", false)))
        .containsExactly("ALTER TABLE `analytics`.`events` MODIFY COLUMN `name` String");
  }

  @Test
  void addsAColumnDefault() {
    assertThat(
            generate(
                new AddDefaultValueStatement(
                    "analytics", null, "events", "country", "varchar(2)", "KZ")))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` MODIFY COLUMN `country` Nullable(String)"
                + " DEFAULT 'KZ'");
  }

  @Test
  void removesAColumnDefault() {
    assertThat(
            generate(
                new DropDefaultValueStatement(
                    "analytics", null, "events", "country", "varchar(2)")))
        .containsExactly("ALTER TABLE `analytics`.`events` MODIFY COLUMN `country` REMOVE DEFAULT");
  }

  @Test
  void batchesLoadDataIntoASingleMultiRowInsert() {
    InsertSetStatement set = new InsertSetStatement("analytics", null, "events");

    InsertStatement first = new InsertStatement("analytics", null, "events");
    first.addColumnValue("id", 1L);
    first.addColumnValue("name", "a");

    InsertStatement second = new InsertStatement("analytics", null, "events");
    second.addColumnValue("id", 2L);
    second.addColumnValue("name", "b");

    set.addInsertStatement(first);
    set.addInsertStatement(second);

    assertThat(generate(set))
        .containsExactly(
            "INSERT INTO `analytics`.`events` (`id`, `name`) VALUES (1, 'a'), (2, 'b')");
  }
}
