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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.core.CreateTableStatement;
import liquibase.statement.core.DropTableStatement;
import org.junit.jupiter.api.Test;

class CreateTableGeneratorClickHouseTest {

  private final Database database = new ClickHouseDatabase();

  private String generate(liquibase.statement.SqlStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return String.join("; ", Arrays.stream(sql).map(Sql::toSql).toList());
  }

  private CreateTableStatement events() {
    CreateTableStatement statement = new CreateTableStatement("analytics", null, "events");
    statement.addColumn(
        "id",
        liquibase.datatype.DataTypeFactory.getInstance().fromDescription("bigint", database),
        null,
        new liquibase.statement.ColumnConstraint[] {
          new liquibase.statement.NotNullConstraint("id")
        });
    statement.addColumn(
        "name",
        liquibase.datatype.DataTypeFactory.getInstance().fromDescription("varchar(50)", database));
    return statement;
  }

  @Test
  void createsAMergeTreeTableOrderedByTuple() {
    assertThat(generate(events()))
        .isEqualTo(
            "CREATE TABLE `analytics`.`events` "
                + "(`id` Int64, `name` Nullable(String)) "
                + "ENGINE = MergeTree ORDER BY tuple()");
  }

  @Test
  void usesThePrimaryKeyAsTheSortingKeyWithoutDuplicatingTheColumn() {
    CreateTableStatement statement = events();
    statement.addPrimaryKeyColumn(
        "id",
        liquibase.datatype.DataTypeFactory.getInstance().fromDescription("bigint", database),
        null,
        "pk_events",
        null);

    assertThat(generate(statement))
        .isEqualTo(
            "CREATE TABLE `analytics`.`events` "
                + "(`id` Int64, `name` Nullable(String)) "
                + "ENGINE = MergeTree PRIMARY KEY (`id`) ORDER BY (`id`)");
  }

  @Test
  void rendersAColumnDefault() {
    CreateTableStatement statement = new CreateTableStatement("analytics", null, "events");
    statement.addColumn(
        "name",
        liquibase.datatype.DataTypeFactory.getInstance().fromDescription("varchar(50)", database),
        "unknown");

    assertThat(generate(statement)).contains("`name` Nullable(String) DEFAULT 'unknown'");
  }

  @Test
  void dropsATableUnconditionally() {
    assertThat(generate(new DropTableStatement("analytics", null, "events", false)))
        .isEqualTo("DROP TABLE IF EXISTS `analytics`.`events`");
  }

  @Test
  void dropsATableTheSameWayWhetherOrNotCascadeWasRequested() {
    assertThat(generate(new DropTableStatement("analytics", null, "events", true)))
        .isEqualTo(generate(new DropTableStatement("analytics", null, "events", false)));
  }
}
