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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.changelog;

import io.github.mnem0c0der.liquibase.ext.clickhouse.changelog.ChangeLogTable;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.TagDatabaseStatement;

/**
 * Tags the most recently applied changelog row.
 *
 * <p>The default generator issues an UPDATE, which ClickHouse does not have. Instead the latest row
 * is reinserted with a new tag and a higher version; FINAL keeps only that reinsert on read.
 */
public class TagDatabaseGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<TagDatabaseStatement> {

  @Override
  public Sql[] generateSql(
      TagDatabaseStatement statement,
      Database database,
      SqlGeneratorChain<TagDatabaseStatement> chain) {

    String table = ChangeLogTable.qualifiedName(database);
    String versionColumn = "`" + ChangeLogTable.ROW_VERSION_COLUMN + "`";

    return sql(
        "INSERT INTO "
            + table
            + " SELECT * EXCEPT ("
            + versionColumn
            + ", `TAG`), "
            + Identifiers.literal(statement.getTag())
            + " AS `TAG`, toUnixTimestamp64Milli(now64(3)) AS "
            + versionColumn
            + " FROM "
            + table
            + " FINAL ORDER BY `ORDEREXECUTED` DESC LIMIT 1");
  }
}
