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
package io.github.mnem0c0der.liquibase.ext.clickhouse.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.ServiceLoader;
import liquibase.Scope;
import liquibase.configuration.AutoloadedConfigurations;
import org.junit.jupiter.api.Test;

class ClickHouseConfigurationTest {

  @Test
  void isRegisteredSoLiquibaseAutoloadsTheKeys() {
    assertThat(ServiceLoader.load(AutoloadedConfigurations.class))
        .anyMatch(ClickHouseConfiguration.class::isInstance);
  }

  @Test
  void exposesKeysUnderTheLiquibaseClickhouseNamespace() {
    assertThat(ClickHouseConfiguration.CLUSTER.getKey()).isEqualTo("liquibase.clickhouse.cluster");
    assertThat(ClickHouseConfiguration.LOCK_TIMEOUT_SECONDS.getKey())
        .isEqualTo("liquibase.clickhouse.lock.timeoutSeconds");
  }

  @Test
  void defaultsToStandaloneMergeTree() {
    assertThat(ClickHouseConfiguration.CLUSTER.getCurrentValue()).isNull();
    assertThat(ClickHouseConfiguration.TABLE_ENGINE.getCurrentValue()).isEqualTo("MergeTree");
    assertThat(ClickHouseConfiguration.MUTATIONS_SYNC.getCurrentValue()).isEqualTo(2);
    assertThat(ClickHouseConfiguration.LOCK_ENABLED.getCurrentValue()).isTrue();
    assertThat(ClickHouseConfiguration.LOCK_TIMEOUT_SECONDS.getCurrentValue()).isEqualTo(300);
    assertThat(ClickHouseConfiguration.LOCK_POLL_INTERVAL_MILLIS.getCurrentValue()).isEqualTo(500);
  }

  @Test
  void readsOverridesFromTheLiquibaseScope() throws Exception {
    String cluster =
        Scope.child(
            Map.of("liquibase.clickhouse.cluster", "analytics"),
            () -> ClickHouseConfiguration.CLUSTER.getCurrentValue());

    assertThat(cluster).isEqualTo("analytics");
  }
}
