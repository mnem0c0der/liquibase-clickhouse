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

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import java.util.List;
import liquibase.database.Database;
import liquibase.util.SqlUtil;

/** Shared logic for UPDATE and DELETE mutations: ClickHouse requires WHERE and async settings. */
final class Mutations {

  static final String TAUTOLOGY = "1 = 1";

  private Mutations() {}

  /**
   * Resolves a where clause to a tautology when absent, otherwise substitutes the {@code :name} and
   * {@code ?}/{@code :value} placeholders a changeset's {@code <whereParams>} produces.
   */
  static String whereOrTautology(
      Database database, String whereClause, List<String> columnNames, List<Object> parameters) {

    if (whereClause == null || whereClause.isBlank()) {
      return TAUTOLOGY;
    }
    return SqlUtil.replacePredicatePlaceholders(database, whereClause, columnNames, parameters);
  }

  static String synchronousSettings() {
    return " SETTINGS mutations_sync = " + ClickHouseConfiguration.MUTATIONS_SYNC.getCurrentValue();
  }
}
