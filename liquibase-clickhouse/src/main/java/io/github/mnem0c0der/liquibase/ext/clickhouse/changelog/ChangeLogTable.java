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
import liquibase.database.Database;

/** Shared description of the changelog tracking table, used by every generator that touches it. */
public final class ChangeLogTable {

  public static final String ROW_VERSION_COLUMN = "ROWVERSION";

  private ChangeLogTable() {}

  public static String qualifiedName(Database database) {
    String catalog =
        database.getLiquibaseCatalogName() == null
            ? database.getDefaultCatalogName()
            : database.getLiquibaseCatalogName();

    String table = Identifiers.quote(database.getDatabaseChangeLogTableName());
    return catalog == null || catalog.isBlank() ? table : Identifiers.quote(catalog) + "." + table;
  }
}
