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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import liquibase.database.Database;
import liquibase.statement.DatabaseFunction;
import org.junit.jupiter.api.Test;

class SqlValuesTest {

  private final Database database = new ClickHouseDatabase();

  @Test
  void rendersNullAsTheSqlKeyword() {
    assertThat(SqlValues.render(null, database)).isEqualTo("NULL");
  }

  @Test
  void singleQuotesAString() {
    assertThat(SqlValues.render("hello", database)).isEqualTo("'hello'");
  }

  @Test
  void leavesANumberUnquoted() {
    assertThat(SqlValues.render(42, database)).isEqualTo("42");
  }

  @Test
  void rendersADatabaseFunctionAsABareCallWithNoQuotes() {
    assertThat(SqlValues.render(new DatabaseFunction("now()"), database)).isEqualTo("now()");
  }
}
