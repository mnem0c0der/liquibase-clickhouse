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
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.CreateDatabaseChangeLogTableStatement;

/**
 * Creates DATABASECHANGELOG.
 *
 * <p>ReplacingMergeTree is used because Liquibase updates already-written rows in place (tag,
 * clearCheckSums), and UPDATE in ClickHouse is a heavy asynchronous mutation. Instead, the row is
 * reinserted with a higher version, and FINAL keeps only the latest one on read.
 */
public class CreateDatabaseChangeLogTableGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<CreateDatabaseChangeLogTableStatement> {

  private static final String COLUMNS =
      String.join(
          ", ",
          "`ID` String",
          "`AUTHOR` String",
          "`FILENAME` String",
          "`DATEEXECUTED` DateTime64(3)",
          "`ORDEREXECUTED` Int32",
          "`EXECTYPE` String",
          "`MD5SUM` Nullable(String)",
          "`DESCRIPTION` Nullable(String)",
          "`COMMENTS` Nullable(String)",
          "`LIQUIBASE` Nullable(String)",
          "`CONTEXTS` Nullable(String)",
          "`LABELS` Nullable(String)",
          "`DEPLOYMENT_ID` Nullable(String)",
          // TAG must stay immediately before ROWVERSION: TagDatabaseGeneratorClickHouse rebuilds
          // both columns with `SELECT * EXCEPT (...)`, and ClickHouse appends re-added columns to
          // the end of the select list in the order they are listed, so an INSERT ... SELECT with
          // no explicit column list only lines up positionally if the table declares them the same
          // way.
          "`TAG` Nullable(String)",
          "`"
              + ChangeLogTable.ROW_VERSION_COLUMN
              + "` UInt64"
              + " DEFAULT toUnixTimestamp64Milli(now64(3))");

  @Override
  public Sql[] generateSql(
      CreateDatabaseChangeLogTableStatement statement,
      Database database,
      SqlGeneratorChain<CreateDatabaseChangeLogTableStatement> chain) {

    String engine =
        clusterPolicy()
            .resolveEngine("ReplacingMergeTree(`" + ChangeLogTable.ROW_VERSION_COLUMN + "`)");

    return sql(
        "CREATE TABLE IF NOT EXISTS "
            + ChangeLogTable.qualifiedName(database)
            + clusterPolicy().onClusterClause()
            + " ("
            + COLUMNS
            + ") ENGINE = "
            + engine
            + " ORDER BY (`ID`, `AUTHOR`, `FILENAME`)");
  }
}
