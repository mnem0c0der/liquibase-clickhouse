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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sql;

/** Quotes ClickHouse identifiers and string literals. */
public final class Identifiers {

  private Identifiers() {}

  public static String quote(String identifier) {
    return "`" + identifier.replace("\\", "\\\\").replace("`", "\\`") + "`";
  }

  /** Escapes the body of a single-quoted ClickHouse string literal, without the quotes. */
  public static String escapeStringBody(String value) {
    return value.replace("\\", "\\\\").replace("'", "\\'");
  }

  public static String literal(String value) {
    return "'" + escapeStringBody(value) + "'";
  }
}
