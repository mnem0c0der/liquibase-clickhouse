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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.RenameTableStatement;

/** Renames a table. */
public class RenameTableGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<RenameTableStatement> {

  @Override
  public Sql[] generateSql(
      RenameTableStatement statement,
      Database database,
      SqlGeneratorChain<RenameTableStatement> chain) {

    return sql(
        "RENAME TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getOldTableName())
            + " TO "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getNewTableName())
            + clusterPolicy().onClusterClause());
  }
}
