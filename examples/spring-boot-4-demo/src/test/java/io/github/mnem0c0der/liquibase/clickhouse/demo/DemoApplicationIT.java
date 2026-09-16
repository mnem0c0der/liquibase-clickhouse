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
package io.github.mnem0c0der.liquibase.clickhouse.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full Spring context against a real ClickHouse container and checks that Spring Boot's
 * stock {@code spring.liquibase} autoconfiguration ran the changelog with no code from this demo.
 */
@SpringBootTest
@Testcontainers
class DemoApplicationIT {

  @Container
  static final ClickHouseContainer CLICKHOUSE =
      new ClickHouseContainer("clickhouse/clickhouse-server:26.8");

  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", CLICKHOUSE::getJdbcUrl);
    registry.add("spring.datasource.username", CLICKHOUSE::getUsername);
    registry.add("spring.datasource.password", CLICKHOUSE::getPassword);
  }

  @Test
  void springBootAutoconfigurationRunsTheChangelogAgainstClickHouse() {
    Long tables =
        jdbcTemplate.queryForObject(
            "SELECT count() FROM system.tables WHERE name = 'orders'"
                + " AND database = currentDatabase()",
            Long.class);

    assertThat(tables).isEqualTo(1L);
  }

  @Test
  void theChangelogIsRecorded() {
    Long applied =
        jdbcTemplate.queryForObject("SELECT count() FROM DATABASECHANGELOG FINAL", Long.class);

    assertThat(applied).isEqualTo(1L);
  }
}
