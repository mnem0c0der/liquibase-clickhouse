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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.unsupported;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.math.BigInteger;
import liquibase.change.ColumnConfig;
import liquibase.database.Database;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.AddAutoIncrementStatement;
import liquibase.statement.core.AddForeignKeyConstraintStatement;
import liquibase.statement.core.AddPrimaryKeyStatement;
import liquibase.statement.core.AddUniqueConstraintStatement;
import liquibase.statement.core.CreateSequenceStatement;
import org.junit.jupiter.api.Test;

class UnsupportedFeatureGeneratorsTest {

  private final Database database = new ClickHouseDatabase();

  private void assertRefused(SqlStatement statement, String feature, String hint) {
    assertThatThrownBy(() -> SqlGeneratorFactory.getInstance().generateSql(statement, database))
        .hasMessageContaining(feature)
        .hasMessageContaining(hint);
  }

  @Test
  void refusesForeignKeys() {
    assertRefused(
        new AddForeignKeyConstraintStatement(
            "fk",
            "analytics",
            null,
            "events",
            new ColumnConfig[] {new ColumnConfig().setName("user_id")},
            "analytics",
            null,
            "users",
            new ColumnConfig[] {new ColumnConfig().setName("id")}),
        "foreign key constraints",
        "application");
  }

  @Test
  void refusesPrimaryKeyConstraints() {
    assertRefused(
        new AddPrimaryKeyStatement("analytics", null, "events", "id", "pk"),
        "adding a primary key to an existing table",
        "ORDER BY");
  }

  @Test
  void refusesAutoIncrement() {
    assertRefused(
        new AddAutoIncrementStatement(
            "analytics",
            null,
            "events",
            "id",
            "bigint",
            BigInteger.ONE,
            BigInteger.ONE,
            Boolean.FALSE,
            null),
        "auto-increment columns",
        "generateUUIDv4");
  }

  @Test
  void refusesUniqueConstraints() {
    assertRefused(
        new AddUniqueConstraintStatement(
            "analytics",
            null,
            "events",
            new ColumnConfig[] {new ColumnConfig().setName("id")},
            "uq"),
        "unique constraints",
        "ReplacingMergeTree");
  }

  @Test
  void refusesSequences() {
    assertRefused(
        new CreateSequenceStatement("analytics", null, "seq"), "sequences", "generateUUIDv4");
  }

  @Test
  void everyRefusalPointsAtTheDocumentedAlternatives() {
    assertThatThrownBy(
            () ->
                SqlGeneratorFactory.getInstance()
                    .generateSql(new CreateSequenceStatement("analytics", null, "seq"), database))
        .hasMessageContaining("#unsupported-features");
  }
}
