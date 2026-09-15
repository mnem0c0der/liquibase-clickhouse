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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import io.github.mnem0c0der.liquibase.ext.clickhouse.exception.UnsupportedClickHouseFeatureException;
import java.util.Arrays;
import java.util.List;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.AutoIncrementConstraint;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.AddColumnStatement;
import liquibase.statement.core.DropColumnStatement;
import liquibase.statement.core.ModifyDataTypeStatement;
import liquibase.statement.core.RenameColumnStatement;
import org.junit.jupiter.api.Test;

class ColumnGeneratorsTest {

  private final Database database = new ClickHouseDatabase();

  private List<String> generate(SqlStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return Arrays.stream(sql).map(Sql::toSql).toList();
  }

  @Test
  void addsANullableColumn() {
    AddColumnStatement statement =
        new AddColumnStatement("analytics", null, "events", "country", "varchar(2)", null);

    assertThat(generate(statement))
        .containsExactly("ALTER TABLE `analytics`.`events` ADD COLUMN `country` Nullable(String)");
  }

  @Test
  void addsANotNullColumnWithoutTheNullableWrapper() {
    AddColumnStatement statement =
        new AddColumnStatement(
            "analytics",
            null,
            "events",
            "country",
            "varchar(2)",
            null,
            new liquibase.statement.NotNullConstraint("country"));

    assertThat(generate(statement))
        .containsExactly("ALTER TABLE `analytics`.`events` ADD COLUMN `country` String");
  }

  @Test
  void expandsACompositeAddColumnIntoSeparateStatements() {
    AddColumnStatement first =
        new AddColumnStatement("analytics", null, "events", "a", "int", null);
    AddColumnStatement second =
        new AddColumnStatement("analytics", null, "events", "b", "int", null);

    assertThat(generate(new AddColumnStatement(first, second)))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` ADD COLUMN `a` Nullable(Int32)",
            "ALTER TABLE `analytics`.`events` ADD COLUMN `b` Nullable(Int32)");
  }

  @Test
  void refusesAnAutoIncrementColumn() {
    AddColumnStatement statement =
        new AddColumnStatement(
            "analytics", null, "events", "id", "bigint", null, new AutoIncrementConstraint("id"));

    assertThatThrownBy(() -> generate(statement))
        .isInstanceOf(UnsupportedClickHouseFeatureException.class)
        .hasMessageContaining("generateUUIDv4");
  }

  @Test
  void refusesAnAutoIncrementColumnEvenAsTheSecondColumnOfAComposite() {
    AddColumnStatement first =
        new AddColumnStatement("analytics", null, "events", "a", "int", null);
    AddColumnStatement second =
        new AddColumnStatement(
            "analytics", null, "events", "id", "bigint", null, new AutoIncrementConstraint("id"));

    assertThatThrownBy(() -> generate(new AddColumnStatement(first, second)))
        .isInstanceOf(UnsupportedClickHouseFeatureException.class)
        .hasMessageContaining("generateUUIDv4");
  }

  @Test
  void dropsAColumn() {
    assertThat(generate(new DropColumnStatement("analytics", null, "events", "country")))
        .containsExactly("ALTER TABLE `analytics`.`events` DROP COLUMN `country`");
  }

  @Test
  void expandsACompositeDropColumnIntoSeparateStatements() {
    DropColumnStatement first = new DropColumnStatement("analytics", null, "events", "a");
    DropColumnStatement second = new DropColumnStatement("analytics", null, "events", "b");

    assertThat(generate(new DropColumnStatement(List.of(first, second))))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` DROP COLUMN `a`",
            "ALTER TABLE `analytics`.`events` DROP COLUMN `b`");
  }

  @Test
  void renamesAColumn() {
    assertThat(
            generate(
                new RenameColumnStatement(
                    "analytics", null, "events", "country", "country_code", null)))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` RENAME COLUMN `country` TO `country_code`");
  }

  @Test
  void modifiesAColumnType() {
    // ColumnNullability needs a live ClickHouse connection to read the column's current
    // nullability back; here the lookup finds nothing and the generator falls back to
    // Nullable. The not-null-preserving path is covered by the integration tests.
    assertThat(
            generate(
                new ModifyDataTypeStatement("analytics", null, "events", "country", "varchar(8)")))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` MODIFY COLUMN `country` Nullable(String)");
  }
}
