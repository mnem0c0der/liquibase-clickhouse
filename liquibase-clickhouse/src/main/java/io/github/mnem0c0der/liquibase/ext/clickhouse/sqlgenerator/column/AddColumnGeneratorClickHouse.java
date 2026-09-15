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

import io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseTypes;
import io.github.mnem0c0der.liquibase.ext.clickhouse.exception.UnsupportedClickHouseFeatureException;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.ArrayList;
import java.util.List;
import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.AddColumnStatement;

/**
 * Generates {@code ALTER TABLE ... ADD COLUMN} for one or more columns.
 *
 * <p>A composite {@link AddColumnStatement} (non-empty {@code getColumns()}) is expanded into one
 * statement per column, since ClickHouse rejects several {@code ADD COLUMN} clauses in a single
 * {@code ALTER TABLE}. Nullability is read from {@link AddColumnStatement#isNullable()} directly,
 * not derived from constraints the way {@code CREATE TABLE} does, and columns are {@code Nullable}
 * unless a {@code NOT NULL} constraint says otherwise.
 */
public class AddColumnGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<AddColumnStatement> {

  @Override
  public Sql[] generateSql(
      AddColumnStatement statement,
      Database database,
      SqlGeneratorChain<AddColumnStatement> chain) {

    List<AddColumnStatement> columns =
        statement.getColumns().isEmpty() ? List.of(statement) : statement.getColumns();

    List<String> statements = new ArrayList<>(columns.size());
    for (AddColumnStatement column : columns) {
      if (column.isAutoIncrement()) {
        throw UnsupportedClickHouseFeatureException.autoIncrement();
      }
      statements.add(
          "ALTER TABLE "
              + qualifiedTableName(database, column.getCatalogName(), column.getTableName())
              + clusterPolicy().onClusterClause()
              + " ADD COLUMN "
              + Identifiers.quote(column.getColumnName())
              + " "
              + renderType(column, database));
    }

    return sql(statements.toArray(String[]::new));
  }

  private static String renderType(AddColumnStatement column, Database database) {
    String type =
        DataTypeFactory.getInstance()
            .fromDescription(column.getColumnType(), database)
            .toDatabaseDataType(database)
            .toSql();

    return column.isNullable() ? ClickHouseTypes.nullable(type) : type;
  }
}
