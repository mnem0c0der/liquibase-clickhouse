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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import io.github.mnem0c0der.liquibase.ext.clickhouse.testsupport.CapturingExecutor;
import java.util.Map;
import java.util.ServiceLoader;
import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryService;
import liquibase.changelog.StandardChangeLogHistoryService;
import liquibase.database.core.PostgresDatabase;
import org.junit.jupiter.api.Test;

class ClickHouseChangeLogHistoryServiceTest {

  private final ClickHouseChangeLogHistoryService service = new ClickHouseChangeLogHistoryService();

  @Test
  void isRegisteredAsAServiceProvider() {
    assertThat(ServiceLoader.load(ChangeLogHistoryService.class))
        .anyMatch(ClickHouseChangeLogHistoryService.class::isInstance);
  }

  @Test
  void supportsOnlyClickHouse() {
    assertThat(service.supports(new ClickHouseDatabase())).isTrue();
    assertThat(service.supports(new PostgresDatabase())).isFalse();
  }

  @Test
  void outranksTheStandardService() {
    assertThat(service.getPriority())
        .isGreaterThan(new StandardChangeLogHistoryService().getPriority());
  }

  private static ClickHouseDatabase database() {
    ClickHouseDatabase database = new ClickHouseDatabase();
    database.setDefaultCatalogName("analytics");
    return database;
  }

  @Test
  void queryReadsWithFinalAndNoConsistencySettingsWhenStandalone() throws Exception {
    ClickHouseDatabase database = database();
    CapturingExecutor executor = CapturingExecutor.installFor(database);

    service.queryDatabaseChangeLogTable(database);

    assertThat(executor.lastStatement())
        .isEqualTo(
            "SELECT * FROM `analytics`.`DATABASECHANGELOG` FINAL ORDER BY `DATEEXECUTED` ASC,"
                + " `ORDEREXECUTED` ASC");
  }

  @Test
  void queryCarriesReadConsistencySettingsWhenClustered() throws Exception {
    ClickHouseDatabase database = database();
    CapturingExecutor executor = CapturingExecutor.installFor(database);

    Scope.child(
        Map.of(ClickHouseConfiguration.CLUSTER.getKey(), "analytics_cluster"),
        () -> service.queryDatabaseChangeLogTable(database));

    assertThat(executor.lastStatement())
        .isEqualTo(
            "SELECT * FROM `analytics`.`DATABASECHANGELOG` FINAL ORDER BY `DATEEXECUTED` ASC,"
                + " `ORDEREXECUTED` ASC SETTINGS select_sequential_consistency = 1");
  }

  @Test
  void clearAllCheckSumsReinsertsWithNoConsistencySettingsWhenStandalone() throws Exception {
    ClickHouseDatabase database = database();
    CapturingExecutor executor = CapturingExecutor.installFor(database);
    service.setDatabase(database);

    service.clearAllCheckSums();

    assertThat(executor.lastStatement())
        .isEqualTo(
            "INSERT INTO `analytics`.`DATABASECHANGELOG` (`ID`, `AUTHOR`, `FILENAME`,"
                + " `DATEEXECUTED`, `ORDEREXECUTED`, `EXECTYPE`, `MD5SUM`, `DESCRIPTION`,"
                + " `COMMENTS`, `LIQUIBASE`, `CONTEXTS`, `LABELS`, `DEPLOYMENT_ID`, `TAG`,"
                + " `ROWVERSION`) SELECT `ID`, `AUTHOR`, `FILENAME`, `DATEEXECUTED`,"
                + " `ORDEREXECUTED`, `EXECTYPE`, NULL AS `MD5SUM`, `DESCRIPTION`, `COMMENTS`,"
                + " `LIQUIBASE`, `CONTEXTS`, `LABELS`, `DEPLOYMENT_ID`, `TAG`,"
                + " toUnixTimestamp64Milli(now64(3)) AS `ROWVERSION` FROM"
                + " `analytics`.`DATABASECHANGELOG` FINAL");
  }

  @Test
  void clearAllCheckSumsCarriesWriteConsistencySettingsWhenClustered() throws Exception {
    ClickHouseDatabase database = database();
    CapturingExecutor executor = CapturingExecutor.installFor(database);
    service.setDatabase(database);

    Scope.child(
        Map.of(ClickHouseConfiguration.CLUSTER.getKey(), "analytics_cluster"),
        () -> service.clearAllCheckSums());

    assertThat(executor.lastStatement())
        .endsWith(
            "FROM `analytics`.`DATABASECHANGELOG` FINAL SETTINGS insert_quorum = 'auto',"
                + " insert_quorum_parallel = 0, async_insert = 0");
  }
}
