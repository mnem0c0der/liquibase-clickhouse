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
package io.github.mnem0c0der.liquibase.ext.clickhouse.database;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.testsupport.FakeJdbcConnections;
import java.util.ServiceLoader;
import liquibase.database.Database;
import org.junit.jupiter.api.Test;

class ClickHouseDatabaseTest {

  private final ClickHouseDatabase database = new ClickHouseDatabase();

  @Test
  void isRegisteredAsAServiceProvider() {
    assertThat(ServiceLoader.load(Database.class)).anyMatch(ClickHouseDatabase.class::isInstance);
  }

  @Test
  void identifiesItselfAsClickhouse() {
    assertThat(database.getShortName()).isEqualTo("clickhouse");
    assertThat(database.getDefaultPort()).isEqualTo(8123);
  }

  @Test
  void recognisesAClickHouseConnection() throws Exception {
    assertThat(
            database.isCorrectDatabaseImplementation(
                FakeJdbcConnections.withProductName("ClickHouse")))
        .isTrue();
  }

  @Test
  void rejectsANonClickHouseConnection() throws Exception {
    assertThat(
            database.isCorrectDatabaseImplementation(
                FakeJdbcConnections.withProductName("PostgreSQL")))
        .isFalse();
  }

  @Test
  void suggestsTheClickHouseDriverForClickHouseUrlsOnly() {
    assertThat(database.getDefaultDriver("jdbc:clickhouse://localhost:8123/default"))
        .isEqualTo("com.clickhouse.jdbc.ClickHouseDriver");
    assertThat(database.getDefaultDriver("jdbc:postgresql://localhost:5432/db")).isNull();
  }

  @Test
  void declaresEngineCapabilitiesHonestly() {
    assertThat(database.supportsDDLInTransaction()).isFalse();
    assertThat(database.supportsSequences()).isFalse();
    assertThat(database.supportsTablespaces()).isFalse();
    assertThat(database.supportsAutoIncrement()).isFalse();
    assertThat(database.supportsInitiallyDeferrableColumns()).isFalse();
    assertThat(database.supportsRestrictForeignKeys()).isFalse();
    assertThat(database.supportsPrimaryKeyNames()).isFalse();
    assertThat(database.supportsNotNullConstraintNames()).isFalse();
  }

  @Test
  void mapsClickHouseDatabasesToCatalogsRatherThanSchemas() {
    assertThat(database.supportsCatalogs()).isTrue();
    assertThat(database.supportsSchemas()).isFalse();
  }
}
