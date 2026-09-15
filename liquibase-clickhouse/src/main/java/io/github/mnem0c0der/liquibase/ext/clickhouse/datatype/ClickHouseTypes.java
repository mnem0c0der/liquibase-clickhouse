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
package io.github.mnem0c0der.liquibase.ext.clickhouse.datatype;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import liquibase.database.Database;

/** Helpers shared by all type classes and by the DDL generators. */
public final class ClickHouseTypes {

  private ClickHouseTypes() {}

  public static boolean isClickHouse(Database database) {
    return database instanceof ClickHouseDatabase;
  }

  private static final String LOW_CARDINALITY = "LowCardinality(";

  /**
   * Оборачивает тип в {@code Nullable(...)}.
   *
   * <p>ClickHouse запрещает Nullable поверх Array и поверх уже нулевого типа, поэтому такие случаи
   * возвращаются без изменений. Для LowCardinality единственная допустимая вложенность — {@code
   * LowCardinality(Nullable(T))}, а не наоборот, поэтому обёртка уходит внутрь.
   */
  public static String nullable(String type) {
    String trimmed = type.trim();

    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("Cannot make an empty column type nullable");
    }
    if (trimmed.startsWith("Nullable(") || trimmed.startsWith("Array(")) {
      return trimmed;
    }
    if (trimmed.startsWith(LOW_CARDINALITY) && trimmed.endsWith(")")) {
      String inner = trimmed.substring(LOW_CARDINALITY.length(), trimmed.length() - 1);
      return LOW_CARDINALITY + nullable(inner) + ")";
    }
    return "Nullable(" + trimmed + ")";
  }
}
