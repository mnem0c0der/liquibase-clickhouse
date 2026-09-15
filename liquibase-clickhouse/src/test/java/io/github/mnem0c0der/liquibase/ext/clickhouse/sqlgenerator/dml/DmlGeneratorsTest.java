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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import java.util.List;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.DeleteStatement;
import liquibase.statement.core.InsertStatement;
import liquibase.statement.core.UpdateStatement;
import org.junit.jupiter.api.Test;

class DmlGeneratorsTest {

  private final Database database = new ClickHouseDatabase();

  private List<String> generate(SqlStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return Arrays.stream(sql).map(Sql::toSql).toList();
  }

  @Test
  void insertsARow() {
    InsertStatement statement = new InsertStatement("analytics", null, "events");
    statement.addColumnValue("id", 1L);
    statement.addColumnValue("name", "launch");

    assertThat(generate(statement))
        .containsExactly("INSERT INTO `analytics`.`events` (`id`, `name`) VALUES (1, 'launch')");
  }

  @Test
  void updatesThroughASynchronousMutation() {
    UpdateStatement statement = new UpdateStatement("analytics", null, "events");
    statement.addNewColumnValue("name", "renamed");
    statement.setWhereClause("id = 1");

    assertThat(generate(statement))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` UPDATE `name` = 'renamed' WHERE id = 1"
                + " SETTINGS mutations_sync = 2");
  }

  @Test
  void suppliesATautologyWhenAnUpdateHasNoWhereClause() {
    UpdateStatement statement = new UpdateStatement("analytics", null, "events");
    statement.addNewColumnValue("name", "renamed");

    assertThat(generate(statement).get(0)).contains("WHERE 1 = 1");
  }

  @Test
  void deletesThroughASynchronousMutation() {
    DeleteStatement statement = new DeleteStatement("analytics", null, "events");
    statement.setWhere("id = 1");

    assertThat(generate(statement))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` DELETE WHERE id = 1 SETTINGS mutations_sync = 2");
  }

  @Test
  void substitutesWhereParamsPlaceholdersOnUpdate() {
    UpdateStatement statement = new UpdateStatement("analytics", null, "events");
    statement.addNewColumnValue("name", "renamed");
    statement.setWhereClause("id = ? AND name = ?");
    statement.addWhereParameters(1L, "launch");

    String sql = generate(statement).get(0);

    assertThat(sql).contains("WHERE id = 1 AND name = 'launch'").doesNotContain("?");
  }

  @Test
  void substitutesWhereParamsPlaceholdersOnDelete() {
    DeleteStatement statement = new DeleteStatement("analytics", null, "events");
    statement.setWhere("id = ? AND name = ?");
    statement.addWhereParameters(1L, "launch");

    String sql = generate(statement).get(0);

    assertThat(sql).contains("DELETE WHERE id = 1 AND name = 'launch'").doesNotContain("?");
  }
}
