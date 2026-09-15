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
import liquibase.statement.core.SetNullableStatement;

/** Toggles nullability by rewriting the column type, since ClickHouse has no SET/DROP NOT NULL. */
public class SetNullableGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<SetNullableStatement> {

  @Override
  public Sql[] generateSql(
      SetNullableStatement statement,
      Database database,
      SqlGeneratorChain<SetNullableStatement> chain) {

    String type =
        DataTypeFactory.getInstance()
            .fromDescription(statement.getColumnDataType(), database)
            .toDatabaseDataType(database)
            .toSql();

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " MODIFY COLUMN "
            + Identifiers.quote(statement.getColumnName())
            + " "
            + (statement.isNullable() ? ClickHouseTypes.nullable(type) : type));
  }
}
