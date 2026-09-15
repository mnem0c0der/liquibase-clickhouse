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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.OnClusterPolicy;
import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.StandaloneClusterPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClickHouseDdlBuilderTest {

  @Test
  void buildsAMinimalStandaloneCreateTable() {
    String sql =
        ClickHouseDdlBuilder.createTable("`analytics`.`events`")
            .onCluster(StandaloneClusterPolicy.INSTANCE)
            .column("`id`", "Int64")
            .column("`name`", "Nullable(String)")
            .engine("MergeTree")
            .orderBy(List.of("`id`"))
            .build();

    assertThat(sql)
        .isEqualTo(
            "CREATE TABLE `analytics`.`events` "
                + "(`id` Int64, `name` Nullable(String)) "
                + "ENGINE = MergeTree ORDER BY (`id`)");
  }

  @Test
  void fallsBackToAnEmptyTupleWhenNoOrderIsGiven() {
    String sql =
        ClickHouseDdlBuilder.createTable("`t`")
            .onCluster(StandaloneClusterPolicy.INSTANCE)
            .column("`id`", "Int64")
            .engine("MergeTree")
            .build();

    assertThat(sql).endsWith("ENGINE = MergeTree ORDER BY tuple()");
  }

  @Test
  void addsTheOnClusterClauseImmediatelyAfterTheTableName() {
    String sql =
        ClickHouseDdlBuilder.createTable("`t`")
            .onCluster(new OnClusterPolicy("c", "/p", "{replica}"))
            .column("`id`", "Int64")
            .engine("MergeTree")
            .build();

    assertThat(sql).startsWith("CREATE TABLE `t` ON CLUSTER `c` (`id` Int64)");
  }

  @Test
  void resolvesTheEngineThroughTheClusterPolicy() {
    String sql =
        ClickHouseDdlBuilder.createTable("`t`")
            .onCluster(new OnClusterPolicy("c", "/p", "{replica}"))
            .column("`id`", "Int64")
            .engine("MergeTree")
            .build();

    assertThat(sql).contains("ENGINE = ReplicatedMergeTree('/p', '{replica}')");
  }

  @Test
  void rendersEveryOptionalClauseInClickHouseOrder() {
    String sql =
        ClickHouseDdlBuilder.createTable("`t`")
            .onCluster(StandaloneClusterPolicy.INSTANCE)
            .column("`id`", "Int64")
            .columnWithDefault("`created`", "DateTime64(3)", "now64(3)")
            .engine("MergeTree")
            .primaryKey(List.of("`id`"))
            .orderBy(List.of("`id`", "`created`"))
            .partitionBy("toYYYYMM(`created`)")
            .ttl("`created` + INTERVAL 30 DAY")
            .settings("index_granularity = 8192")
            .comment("event stream")
            .build();

    assertThat(sql)
        .isEqualTo(
            "CREATE TABLE `t` "
                + "(`id` Int64, `created` DateTime64(3) DEFAULT now64(3)) "
                + "ENGINE = MergeTree "
                + "PRIMARY KEY (`id`) "
                + "ORDER BY (`id`, `created`) "
                + "PARTITION BY toYYYYMM(`created`) "
                + "TTL `created` + INTERVAL 30 DAY "
                + "SETTINGS index_granularity = 8192 "
                + "COMMENT 'event stream'");
  }
}
