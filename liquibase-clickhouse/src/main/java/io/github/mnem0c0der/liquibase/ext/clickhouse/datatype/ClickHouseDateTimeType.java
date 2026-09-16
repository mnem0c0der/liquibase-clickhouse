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

import liquibase.database.Database;
import liquibase.datatype.DataTypeInfo;
import liquibase.datatype.DatabaseDataType;
import liquibase.datatype.core.DateTimeType;
import liquibase.servicelocator.PrioritizedService;

@DataTypeInfo(
    name = "datetime",
    aliases = {
      "java.sql.Types.DATETIME",
      "java.sql.Types.TIMESTAMP",
      "java.sql.Timestamp",
      "timestamp",
      "timestamptz",
      "timestamp with time zone",
      "timestamp without time zone"
    },
    minParameters = 0,
    maxParameters = 1,
    priority = PrioritizedService.PRIORITY_DATABASE)
public class ClickHouseDateTimeType extends DateTimeType {

  private static final String DEFAULT_FRACTIONAL_DIGITS = "3";

  @Override
  public boolean supports(Database database) {
    return ClickHouseTypes.isClickHouse(database);
  }

  @Override
  public DatabaseDataType toDatabaseDataType(Database database) {
    Object[] parameters = getParameters();
    String precision =
        parameters.length > 0 ? String.valueOf(parameters[0]) : DEFAULT_FRACTIONAL_DIGITS;
    return new DatabaseDataType("DateTime64(" + precision + ")");
  }
}
