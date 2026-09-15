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
import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.ModifyDataTypeStatement;

/**
 * Generates {@code ALTER TABLE ... MODIFY COLUMN} for a type change.
 *
 * <p>{@link ModifyDataTypeStatement} carries no nullability information, so the new type is always
 * wrapped as {@code Nullable}: assuming {@code NOT NULL} could silently reject existing null values
 * the column already holds.
 */
public class ModifyDataTypeGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<ModifyDataTypeStatement> {

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

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " MODIFY COLUMN "
            + Identifiers.quote(statement.getColumnName())
            + " "
            + ClickHouseTypes.nullable(type));
  }
}
