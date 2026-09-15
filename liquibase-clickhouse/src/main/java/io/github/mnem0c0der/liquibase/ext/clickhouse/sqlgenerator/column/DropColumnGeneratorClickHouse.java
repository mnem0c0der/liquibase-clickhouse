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

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.ArrayList;
import java.util.List;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.DropColumnStatement;

/**
 * Generates {@code ALTER TABLE ... DROP COLUMN} for one or more columns.
 *
 * <p>A composite {@link DropColumnStatement} (non-empty {@code getColumns()}) is expanded into one
 * statement per column, matching {@code ADD COLUMN}'s restriction to a single clause per {@code
 * ALTER TABLE}.
 */
public class DropColumnGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<DropColumnStatement> {

  @Override
  public Sql[] generateSql(
      DropColumnStatement statement,
      Database database,
      SqlGeneratorChain<DropColumnStatement> chain) {

    List<DropColumnStatement> columns =
        statement.getColumns().isEmpty() ? List.of(statement) : statement.getColumns();

    List<String> statements = new ArrayList<>(columns.size());
    for (DropColumnStatement column : columns) {
      statements.add(
          "ALTER TABLE "
              + qualifiedTableName(database, column.getCatalogName(), column.getTableName())
              + clusterPolicy().onClusterClause()
              + " DROP COLUMN "
              + Identifiers.quote(column.getColumnName()));
    }

    return sql(statements.toArray(String[]::new));
  }
}
