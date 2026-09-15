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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table;

import io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseTypes;
import liquibase.database.Database;
import liquibase.statement.core.CreateTableStatement;

/** Приведение колонок Liquibase к колонкам ClickHouse. */
final class TableColumns {

  private TableColumns() {}

  /**
   * В ClickHouse колонка по умолчанию NOT NULL — обратно SQL-стандарту. Поэтому всё, что не
   * объявлено NOT NULL явно, оборачивается в Nullable.
   */
  static String renderType(CreateTableStatement statement, String columnName, Database database) {
    String type = statement.getColumnTypes().get(columnName).toDatabaseDataType(database).toSql();

    return statement.getNotNullColumns().containsKey(columnName)
        ? type
        : ClickHouseTypes.nullable(type);
  }
}
