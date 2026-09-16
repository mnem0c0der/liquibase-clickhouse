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
package io.github.mnem0c0der.liquibase.ext.clickhouse.changelog;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.List;
import java.util.Map;
import liquibase.Scope;
import liquibase.changelog.StandardChangeLogHistoryService;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.executor.Executor;
import liquibase.executor.ExecutorService;
import liquibase.statement.core.RawSqlStatement;

/**
 * Changelog history backed by a ReplacingMergeTree instead of a normally updatable table.
 *
 * <p>ClickHouse never updates a row in place, so anything the base class would do with an {@code
 * UPDATE} has to become an insert of a new, higher-versioned row instead, and any plain read has to
 * ask for {@code FINAL} or it will see rows that a later insert already superseded.
 *
 * <p>{@code tag()} is deliberately left un-overridden: the base implementation already mutates the
 * table through a {@link liquibase.statement.core.TagDatabaseStatement}, which {@code
 * SqlGeneratorFactory} routes to {@code TagDatabaseGeneratorClickHouse} — the same insert-a-new-
 * version approach this class would otherwise have to duplicate. Only {@link
 * #queryDatabaseChangeLogTable} and {@link #clearAllCheckSums} need to be overridden here, because
 * neither has a dedicated {@code SqlGenerator} to carry the ClickHouse-specific behaviour.
 */
public class ClickHouseChangeLogHistoryService extends StandardChangeLogHistoryService {

  @Override
  public int getPriority() {
    return PRIORITY_DATABASE;
  }

  @Override
  public boolean supports(Database database) {
    return database instanceof ClickHouseDatabase;
  }

  /**
   * Reads with {@code FINAL} so merges the ReplacingMergeTree has not gotten around to yet don't
   * surface superseded row versions.
   */
  @Override
  public List<Map<String, ?>> queryDatabaseChangeLogTable(Database database)
      throws DatabaseException {
    return executor(database)
        .queryForList(
            new RawSqlStatement(
                "SELECT * FROM "
                    + ChangeLogTable.qualifiedName(database)
                    + " FINAL ORDER BY `DATEEXECUTED` ASC, `ORDEREXECUTED` ASC"));
  }

  /**
   * Clears every row's checksum by inserting a new version of each row with {@code MD5SUM} set to
   * {@code NULL}, rather than the heavy {@code ALTER TABLE ... UPDATE} mutation the base class's
   * plain {@code UPDATE} would otherwise trigger.
   */
  @Override
  public void clearAllCheckSums() throws LiquibaseException {
    Database database = getDatabase();
    String table = ChangeLogTable.qualifiedName(database);

    String selectList =
        ChangeLogTable.selectListWith(
            Map.of(
                "MD5SUM",
                "NULL",
                ChangeLogTable.ROW_VERSION_COLUMN,
                "toUnixTimestamp64Milli(now64(3))"));

    executor(database)
        .execute(
            new RawSqlStatement(
                "INSERT INTO "
                    + table
                    + " ("
                    + ChangeLogTable.quotedColumnList()
                    + ") SELECT "
                    + selectList
                    + " FROM "
                    + table
                    + " FINAL"));

    reset();
  }

  private static Executor executor(Database database) {
    return Scope.getCurrentScope()
        .getSingleton(ExecutorService.class)
        .getExecutor("jdbc", database);
  }
}
