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
package io.github.mnem0c0der.liquibase.ext.clickhouse.cluster;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import java.util.Objects;

/**
 * Кластерная топология: DDL выполняется через ON CLUSTER, а движки семейства MergeTree заменяются
 * на Replicated-аналоги.
 *
 * <p>Replicated-движок принимает путь в Keeper и имя реплики ПЕРВЫМИ аргументами, поэтому
 * существующие аргументы движка сдвигаются вправо, а не заменяются.
 */
public final class OnClusterPolicy implements ClusterPolicy {

  private static final String REPLICATED_PREFIX = "Replicated";
  private static final String MERGE_TREE_SUFFIX = "MergeTree";

  private final String clusterName;
  private final String zooKeeperPath;
  private final String replicaName;

  public OnClusterPolicy(String clusterName, String zooKeeperPath, String replicaName) {
    this.clusterName = Objects.requireNonNull(clusterName, "clusterName");
    this.zooKeeperPath = Objects.requireNonNull(zooKeeperPath, "zooKeeperPath");
    this.replicaName = Objects.requireNonNull(replicaName, "replicaName");
  }

  @Override
  public boolean isClustered() {
    return true;
  }

  @Override
  public String onClusterClause() {
    return " ON CLUSTER " + Identifiers.quote(clusterName);
  }

  @Override
  public String resolveEngine(String requestedEngine) {
    String engine = requestedEngine.trim();
    int parenthesis = engine.indexOf('(');

    if (parenthesis >= 0 && !engine.endsWith(")")) {
      throw new IllegalArgumentException(
          "Malformed ClickHouse table engine: "
              + requestedEngine
              + ". An engine with arguments must close its parenthesis, for example"
              + " ReplacingMergeTree(version).");
    }

    String family = (parenthesis < 0 ? engine : engine.substring(0, parenthesis)).trim();

    if (family.startsWith(REPLICATED_PREFIX) || !family.endsWith(MERGE_TREE_SUFFIX)) {
      return engine;
    }

    String replicationArguments =
        Identifiers.literal(zooKeeperPath) + ", " + Identifiers.literal(replicaName);

    if (parenthesis < 0) {
      return REPLICATED_PREFIX + family + "(" + replicationArguments + ")";
    }

    String existingArguments = engine.substring(parenthesis + 1, engine.length() - 1).trim();

    return REPLICATED_PREFIX
        + family
        + "("
        + replicationArguments
        + (existingArguments.isEmpty() ? "" : ", " + existingArguments)
        + ")";
  }
}
