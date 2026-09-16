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

import liquibase.configuration.AutoloadedConfigurations;
import liquibase.configuration.ConfigurationDefinition;

/**
 * Configuration for the extension. Each key is declared once; Liquibase exposes it through
 * liquibase.properties, system properties, environment variables, and the CLI.
 */
public final class ClickHouseConfiguration implements AutoloadedConfigurations {

  private static final String NAMESPACE = "liquibase.clickhouse";

  public static final ConfigurationDefinition<String> CLUSTER;
  public static final ConfigurationDefinition<String> TABLE_ENGINE;
  public static final ConfigurationDefinition<String> ZOOKEEPER_PATH;
  public static final ConfigurationDefinition<String> REPLICA_NAME;
  public static final ConfigurationDefinition<Integer> MUTATIONS_SYNC;
  public static final ConfigurationDefinition<Integer> LOCK_TIMEOUT_SECONDS;
  public static final ConfigurationDefinition<Integer> LOCK_POLL_INTERVAL_MILLIS;
  public static final ConfigurationDefinition<Boolean> LOCK_ENABLED;

  static {
    ConfigurationDefinition.Builder builder = new ConfigurationDefinition.Builder(NAMESPACE);

    CLUSTER =
        builder
            .define("cluster", String.class)
            .setDescription(
                "ClickHouse cluster name. When set, DDL is issued with ON CLUSTER and"
                    + " MergeTree engines are replaced by their Replicated counterparts."
                    + " Leave unset for standalone deployments.")
            .setCommonlyUsed(true)
            .build();

    TABLE_ENGINE =
        builder
            .define("tableEngine", String.class)
            .setDescription("Default table engine used when a changeset does not specify one.")
            .setDefaultValue("MergeTree")
            .build();

    ZOOKEEPER_PATH =
        builder
            .define("zookeeperPath", String.class)
            .setDescription("ZooKeeper/Keeper path template for Replicated* engines.")
            .setDefaultValue("/clickhouse/tables/{shard}/{database}/{table}")
            .build();

    REPLICA_NAME =
        builder
            .define("replicaName", String.class)
            .setDescription("Replica name template for Replicated* engines.")
            .setDefaultValue("{replica}")
            .build();

    MUTATIONS_SYNC =
        builder
            .define("mutationsSync", Integer.class)
            .setDescription(
                "Value of the ClickHouse mutations_sync setting applied to ALTER ... UPDATE"
                    + " and ALTER ... DELETE. 2 waits for all replicas.")
            .setDefaultValue(2)
            .build();

    LOCK_TIMEOUT_SECONDS =
        builder
            .define("lock.timeoutSeconds", Integer.class)
            .setDescription(
                "Age after which a held changelog lock is treated as stale and may be"
                    + " preempted. Protects against a crashed migration holding the lock forever.")
            .setDefaultValue(300)
            .build();

    LOCK_POLL_INTERVAL_MILLIS =
        builder
            .define("lock.pollIntervalMillis", Integer.class)
            .setDescription("Delay between lock acquisition attempts.")
            .setDefaultValue(500)
            .build();

    LOCK_ENABLED =
        builder
            .define("lock.enabled", Boolean.class)
            .setDescription(
                "Set to false to skip changelog locking entirely. Only safe when a single"
                    + " migration process can ever run at a time.")
            .setDefaultValue(true)
            .build();
  }
}
