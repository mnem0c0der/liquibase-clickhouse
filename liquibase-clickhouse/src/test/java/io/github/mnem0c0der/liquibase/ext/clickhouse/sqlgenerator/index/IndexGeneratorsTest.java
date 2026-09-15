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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import java.util.List;
import liquibase.change.AddColumnConfig;
import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.CreateIndexStatement;
import liquibase.statement.core.DropIndexStatement;
import liquibase.statement.core.RenameTableStatement;
import org.junit.jupiter.api.Test;

class IndexGeneratorsTest {

  private final Database database = new ClickHouseDatabase();

  private List<String> generate(SqlStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return Arrays.stream(sql).map(Sql::toSql).toList();
  }

  private static AddColumnConfig column(String name) {
    AddColumnConfig config = new AddColumnConfig();
    config.setName(name);
    return config;
  }

  private CreateIndexStatement index(Boolean unique) {
    return new CreateIndexStatement(
        "idx_country", "analytics", null, "events", unique, null, column("country"));
  }

  @Test
  void renamesATable() {
    assertThat(generate(new RenameTableStatement("analytics", null, "events", "events_v2")))
        .containsExactly("RENAME TABLE `analytics`.`events` TO `analytics`.`events_v2`");
  }

  @Test
  void addsAndMaterialisesADataSkippingIndex() {
    assertThat(generate(index(false)))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` ADD INDEX `idx_country` (`country`)"
                + " TYPE minmax GRANULARITY 1",
            "ALTER TABLE `analytics`.`events` MATERIALIZE INDEX `idx_country`");
  }

  @Test
  void refusesToFakeAUniqueIndex() {
    assertThatThrownBy(() -> generate(index(true)))
        .hasMessageContaining("unique indexes")
        .hasMessageContaining("ReplacingMergeTree");
  }

  @Test
  void validationReportsAUniqueIndexBeforeAnySqlIsGenerated() {
    ValidationErrors errors = SqlGeneratorFactory.getInstance().validate(index(true), database);

    assertThat(errors.hasErrors()).isTrue();
    assertThat(errors.getErrorMessages())
        .anyMatch(message -> message.contains("ReplacingMergeTree"));
  }

  @Test
  void validatesAnOrdinaryIndexCleanly() {
    assertThat(SqlGeneratorFactory.getInstance().validate(index(false), database).hasErrors())
        .isFalse();
  }

  @Test
  void dropsAnIndex() {
    assertThat(generate(new DropIndexStatement("idx_country", "analytics", null, "events", null)))
        .containsExactly("ALTER TABLE `analytics`.`events` DROP INDEX `idx_country`");
  }
}
