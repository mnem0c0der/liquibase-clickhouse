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
import java.util.Map;
import java.util.stream.Collectors;
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

  private static final Map<String, String> COLUMN_TYPES =
      Map.ofEntries(
          Map.entry("ID", "String"),
          Map.entry("AUTHOR", "String"),
          Map.entry("FILENAME", "String"),
          Map.entry("DATEEXECUTED", "DateTime64(3)"),
          Map.entry("ORDEREXECUTED", "Int32"),
          Map.entry("EXECTYPE", "String"),
          Map.entry("MD5SUM", "Nullable(String)"),
          Map.entry("DESCRIPTION", "Nullable(String)"),
          Map.entry("COMMENTS", "Nullable(String)"),
          Map.entry("LIQUIBASE", "Nullable(String)"),
          Map.entry("CONTEXTS", "Nullable(String)"),
          Map.entry("LABELS", "Nullable(String)"),
          Map.entry("DEPLOYMENT_ID", "Nullable(String)"),
          Map.entry("TAG", "Nullable(String)"),
          Map.entry(
              ChangeLogTable.ROW_VERSION_COLUMN,
              "UInt64 DEFAULT toUnixTimestamp64Milli(now64(3))"));

  // Built from ChangeLogTable.COLUMN_NAMES so the declaration can never drift out of sync with
  // the explicit column list that TagDatabaseGeneratorClickHouse (and future re-insert
  // generators) use to keep values lined up with the right column.
  private static final String COLUMNS =
      ChangeLogTable.COLUMN_NAMES.stream()
          .map(name -> "`" + name + "` " + COLUMN_TYPES.get(name))
          .collect(Collectors.joining(", "));

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
