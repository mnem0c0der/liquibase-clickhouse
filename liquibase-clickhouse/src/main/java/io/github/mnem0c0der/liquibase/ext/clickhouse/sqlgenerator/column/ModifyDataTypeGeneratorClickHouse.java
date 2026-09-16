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
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.ModifyDataTypeStatement;

/**
 * Generates {@code ALTER TABLE ... MODIFY COLUMN} for a type change.
 *
 * <p>{@link ModifyDataTypeStatement} carries no nullability information, and on other databases
 * {@code modifyDataType} changes only the type and leaves nullability alone. In ClickHouse
 * nullability is part of the type string itself, so this generator reads the column's current
 * nullability back from {@code system.columns} and preserves it, rather than guessing.
 */
public class ModifyDataTypeGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<ModifyDataTypeStatement> {

  @Override
  public boolean generateStatementsIsVolatile(Database database) {
    return true;
  }

  @Override
  public Sql[] generateSql(
      ModifyDataTypeStatement statement,
      Database database,
      SqlGeneratorChain<ModifyDataTypeStatement> chain) {

    String type =
        DataTypeFactory.getInstance()
            .fromDescription(statement.getNewDataType(), database)
            .toDatabaseDataType(database)
            .toSql();

    // In ClickHouse nullability is part of the type, so changing the type would rewrite it.
    // Read the current nullability back and keep it.
    boolean nullable =
        ColumnNullability.currentlyNullable(
                database,
                statement.getCatalogName(),
                statement.getTableName(),
                statement.getColumnName())
            .orElseGet(() -> assumeNullable(statement));

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " MODIFY COLUMN "
            + Identifiers.quote(statement.getColumnName())
            + " "
            + (nullable ? ClickHouseTypes.nullable(type) : type));
  }

  private static boolean assumeNullable(ModifyDataTypeStatement statement) {
    Scope.getCurrentScope()
        .getLog(ModifyDataTypeGeneratorClickHouse.class)
        .warning(
            "Could not read the current nullability of "
                + statement.getTableName()
                + "."
                + statement.getColumnName()
                + "; assuming it is nullable. Add an addNotNullConstraint change if the column"
                + " must stay NOT NULL.");
    return true;
  }
}
