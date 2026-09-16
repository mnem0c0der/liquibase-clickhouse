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

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.SqlValues;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.stream.Collectors;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.UpdateStatement;

/**
 * Updates rows through an {@code ALTER TABLE ... UPDATE} mutation, the only way ClickHouse changes
 * existing data. The mutation runs asynchronously, so it carries {@code mutations_sync} to make
 * Liquibase wait for it to actually apply.
 */
public class UpdateGeneratorClickHouse extends AbstractClickHouseSqlGenerator<UpdateStatement> {

  @Override
  public Sql[] generateSql(
      UpdateStatement statement, Database database, SqlGeneratorChain<UpdateStatement> chain) {

    String assignments =
        statement.getNewColumnValues().entrySet().stream()
            .map(
                entry ->
                    Identifiers.quote(entry.getKey())
                        + " = "
                        + SqlValues.render(entry.getValue(), database))
            .collect(Collectors.joining(", "));

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " UPDATE "
            + assignments
            + " WHERE "
            + Mutations.whereOrTautology(
                database,
                statement.getWhereClause(),
                statement.getWhereColumnNames(),
                statement.getWhereParameters())
            + Mutations.synchronousSettings());
  }
}
