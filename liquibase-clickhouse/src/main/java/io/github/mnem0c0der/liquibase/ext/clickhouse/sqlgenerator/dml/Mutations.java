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

/** Shared logic for UPDATE and DELETE mutations: ClickHouse requires WHERE and async settings. */
final class Mutations {

  static final String TAUTOLOGY = "1 = 1";

  private Mutations() {}

  static String whereOrTautology(String whereClause) {
    return whereClause == null || whereClause.isBlank() ? TAUTOLOGY : whereClause;
  }

  static String synchronousSettings() {
    return " SETTINGS mutations_sync = " + ClickHouseConfiguration.MUTATIONS_SYNC.getCurrentValue();
  }
}
