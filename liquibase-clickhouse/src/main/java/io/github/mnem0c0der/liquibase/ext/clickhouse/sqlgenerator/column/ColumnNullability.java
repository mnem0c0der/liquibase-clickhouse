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

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.executor.Executor;
import liquibase.executor.ExecutorService;
import liquibase.statement.core.RawSqlStatement;

/** Reads a column's declared nullability back from {@code system.columns}. */
final class ColumnNullability {

  private static final String NULLABLE_PREFIX = "Nullable(";

  private ColumnNullability() {}

  /**
   * Returns whether the column is currently nullable, or empty when that cannot be established — no
   * connection, or no such column yet.
   */
  static Optional<Boolean> currentlyNullable(
      Database database, String catalogName, String tableName, String columnName) {

    String catalog =
        catalogName == null || catalogName.isBlank()
            ? database.getDefaultCatalogName()
            : catalogName;

    String sql =
        "SELECT type FROM system.columns WHERE database = "
            + (catalog == null || catalog.isBlank()
                ? "currentDatabase()"
                : Identifiers.literal(catalog))
            + " AND table = "
            + Identifiers.literal(tableName)
            + " AND name = "
            + Identifiers.literal(columnName);

    try {
      Executor executor =
          Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database);
      List<Map<String, ?>> rows = executor.queryForList(new RawSqlStatement(sql));

      if (rows.isEmpty() || rows.get(0).isEmpty()) {
        return Optional.empty();
      }
      Object type = rows.get(0).values().iterator().next();
      return Optional.of(String.valueOf(type).startsWith(NULLABLE_PREFIX));

    } catch (DatabaseException | RuntimeException unavailable) {
      return Optional.empty();
    }
  }
}
