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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClickHouseDataTypeTest {

  private final Database database = new ClickHouseDatabase();

  private String sqlFor(String liquibaseType) {
    return DataTypeFactory.getInstance()
        .fromDescription(liquibaseType, database)
        .toDatabaseDataType(database)
        .toSql();
  }

  @ParameterizedTest
  @CsvSource({
    "varchar(255), String",
    "varchar,      String",
    "char(3),      String",
    "clob,         String",
    "blob,         String",
    "tinyint,      Int8",
    "smallint,     Int16",
    "int,          Int32",
    "bigint,       Int64",
    "boolean,      Bool",
    "float,        Float32",
    "double,       Float64",
    "date,         Date32",
    "uuid,         UUID"
  })
  void mapsLiquibaseTypesToClickHouseTypes(String liquibaseType, String expected) {
    assertThat(sqlFor(liquibaseType)).isEqualTo(expected);
  }

  @Test
  void mapsDecimalPreservingPrecisionAndScale() {
    assertThat(sqlFor("decimal(18,4)")).isEqualTo("Decimal(18, 4)");
  }

  @Test
  void mapsDecimalWithoutParametersToASafeDefault() {
    assertThat(sqlFor("decimal")).isEqualTo("Decimal(38, 9)");
  }

  @Test
  void mapsTimestampToMillisecondPrecision() {
    assertThat(sqlFor("timestamp")).isEqualTo("DateTime64(3)");
    assertThat(sqlFor("datetime")).isEqualTo("DateTime64(3)");
  }

  @Test
  void honoursAnExplicitTimestampPrecision() {
    assertThat(sqlFor("timestamp(6)")).isEqualTo("DateTime64(6)");
  }

  @Test
  void wrapsNullableTypesExactlyOnce() {
    assertThat(ClickHouseTypes.nullable("String")).isEqualTo("Nullable(String)");
    assertThat(ClickHouseTypes.nullable("Nullable(String)")).isEqualTo("Nullable(String)");
  }

  @Test
  void neverWrapsArrayTypes() {
    assertThat(ClickHouseTypes.nullable("Array(String)")).isEqualTo("Array(String)");
  }

  @Test
  void movesTheNullableWrapperInsideLowCardinality() {
    assertThat(ClickHouseTypes.nullable("LowCardinality(String)"))
        .isEqualTo("LowCardinality(Nullable(String))");
    assertThat(ClickHouseTypes.nullable("LowCardinality(Nullable(String))"))
        .isEqualTo("LowCardinality(Nullable(String))");
  }

  @Test
  void rejectsAnEmptyColumnType() {
    assertThatThrownBy(() -> ClickHouseTypes.nullable("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty column type");
  }
}
