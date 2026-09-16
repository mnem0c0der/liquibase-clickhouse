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

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.ServiceLoader;
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
}
