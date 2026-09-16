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
import liquibase.datatype.core.IntType;
import liquibase.servicelocator.PrioritizedService;

@DataTypeInfo(
    name = "int",
    aliases = {"java.sql.Types.INTEGER", "java.lang.Integer", "integer", "int4"},
    minParameters = 0,
    maxParameters = 0,
    priority = PrioritizedService.PRIORITY_DATABASE)
public class ClickHouseIntType extends IntType {

  @Override
  public boolean supports(Database database) {
    return ClickHouseTypes.isClickHouse(database);
  }

  @Override
  public DatabaseDataType toDatabaseDataType(Database database) {
    return new DatabaseDataType("Int32");
  }
}
