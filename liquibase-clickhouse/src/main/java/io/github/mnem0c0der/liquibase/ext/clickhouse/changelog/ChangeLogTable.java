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

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import liquibase.database.Database;

/**
 * Shared description of the changelog tracking table, used by every generator that touches it.
 *
 * <p>{@link #COLUMN_NAMES} is the single source of truth for the column list. Both the CREATE TABLE
 * generator and the generators that reinsert a row (tag, and eventually clearCheckSums) build their
 * SQL from it, so an INSERT never depends on the order columns were declared in.
 */
public final class ChangeLogTable {

  public static final String ROW_VERSION_COLUMN = "ROWVERSION";

  /** Column names in declaration order. */
  public static final List<String> COLUMN_NAMES =
      List.of(
          "ID",
          "AUTHOR",
          "FILENAME",
          "DATEEXECUTED",
          "ORDEREXECUTED",
          "EXECTYPE",
          "MD5SUM",
          "DESCRIPTION",
          "COMMENTS",
          "LIQUIBASE",
          "CONTEXTS",
          "LABELS",
          "DEPLOYMENT_ID",
          "TAG",
          ROW_VERSION_COLUMN);

  private ChangeLogTable() {}

  public static String qualifiedName(Database database) {
    String catalog =
        database.getLiquibaseCatalogName() == null
            ? database.getDefaultCatalogName()
            : database.getLiquibaseCatalogName();

    String table = Identifiers.quote(database.getDatabaseChangeLogTableName());
    return catalog == null || catalog.isBlank() ? table : Identifiers.quote(catalog) + "." + table;
  }

  /** Quoted, comma-separated column list for an explicit INSERT. */
  public static String quotedColumnList() {
    return COLUMN_NAMES.stream().map(Identifiers::quote).collect(Collectors.joining(", "));
  }

  /**
   * Select list copying every column, with the named columns replaced by the given expressions. The
   * order always matches {@link #COLUMN_NAMES}, so the INSERT never depends on declaration order.
   */
  public static String selectListWith(Map<String, String> overrides) {
    for (String column : overrides.keySet()) {
      if (!COLUMN_NAMES.contains(column)) {
        throw new IllegalArgumentException(
            "Unknown changelog column: " + column + ", expected one of " + COLUMN_NAMES);
      }
    }

    return COLUMN_NAMES.stream()
        .map(
            column ->
                overrides.containsKey(column)
                    ? overrides.get(column) + " AS " + Identifiers.quote(column)
                    : Identifiers.quote(column))
        .collect(Collectors.joining(", "));
  }
}
