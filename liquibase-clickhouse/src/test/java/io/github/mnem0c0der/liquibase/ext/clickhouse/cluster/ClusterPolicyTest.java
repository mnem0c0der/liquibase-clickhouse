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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import java.util.Map;
import liquibase.Scope;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClusterPolicyTest {

  private static final String ZK_PATH = "/clickhouse/tables/{shard}/{database}/{table}";
  private static final String REPLICA = "{replica}";

  @Nested
  class Standalone {

    private final ClusterPolicy policy = StandaloneClusterPolicy.INSTANCE;

    @Test
    void emitsNoOnClusterClause() {
      assertThat(policy.isClustered()).isFalse();
      assertThat(policy.onClusterClause()).isEmpty();
    }

    @Test
    void leavesEveryEngineUntouched() {
      assertThat(policy.resolveEngine("MergeTree")).isEqualTo("MergeTree");
      assertThat(policy.resolveEngine("ReplacingMergeTree(version)"))
          .isEqualTo("ReplacingMergeTree(version)");
      assertThat(policy.resolveEngine("Memory")).isEqualTo("Memory");
    }
  }

  @Nested
  class Clustered {

    private final ClusterPolicy policy = new OnClusterPolicy("analytics", ZK_PATH, REPLICA);

    @Test
    void emitsAQuotedOnClusterClause() {
      assertThat(policy.isClustered()).isTrue();
      assertThat(policy.onClusterClause()).isEqualTo(" ON CLUSTER `analytics`");
    }

    @Test
    void replicatesAnArgumentlessMergeTree() {
      assertThat(policy.resolveEngine("MergeTree"))
          .isEqualTo(
              "ReplicatedMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}')");
    }

    @Test
    void prependsReplicationArgumentsToAnExistingArgumentList() {
      assertThat(policy.resolveEngine("ReplacingMergeTree(version)"))
          .isEqualTo(
              "ReplicatedReplacingMergeTree("
                  + "'/clickhouse/tables/{shard}/{database}/{table}', '{replica}', version)");
    }

    @Test
    void leavesAnAlreadyReplicatedEngineAlone() {
      assertThat(policy.resolveEngine("ReplicatedMergeTree('/x', '{replica}')"))
          .isEqualTo("ReplicatedMergeTree('/x', '{replica}')");
    }

    @Test
    void leavesNonMergeTreeEnginesAlone() {
      assertThat(policy.resolveEngine("Memory")).isEqualTo("Memory");
      assertThat(policy.resolveEngine("Null")).isEqualTo("Null");
    }

    @Test
    void rejectsAnEngineWithAnUnclosedParenthesis() {
      assertThatThrownBy(() -> policy.resolveEngine("MergeTree("))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("MergeTree(")
          .hasMessageContaining("close its parenthesis");
    }

    @Test
    void trimsSurroundingWhitespaceTheSameWayForRewrittenAndPassThroughEngines() {
      assertThat(policy.resolveEngine("  MergeTree  "))
          .isEqualTo(
              "ReplicatedMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}')");
      assertThat(policy.resolveEngine("  Memory  ")).isEqualTo("Memory");
    }
  }

  @Nested
  class Factory {

    @Test
    void buildsStandalonePolicyWhenNoClusterIsConfigured() {
      assertThat(ClusterPolicyFactory.fromConfiguration().isClustered()).isFalse();
    }

    @Test
    void buildsClusteredPolicyWhenClusterIsConfigured() throws Exception {
      ClusterPolicy policy =
          Scope.child(
              Map.of(ClickHouseConfiguration.CLUSTER.getKey(), "analytics"),
              ClusterPolicyFactory::fromConfiguration);

      assertThat(policy.isClustered()).isTrue();
      assertThat(policy.onClusterClause()).isEqualTo(" ON CLUSTER `analytics`");
    }

    @Test
    void treatsABlankClusterNameAsStandalone() throws Exception {
      ClusterPolicy policy =
          Scope.child(
              Map.of(ClickHouseConfiguration.CLUSTER.getKey(), "   "),
              ClusterPolicyFactory::fromConfiguration);

      assertThat(policy.isClustered()).isFalse();
    }
  }

  @Nested
  class Quoting {

    @Test
    void quotesIdentifiersWithBackticks() {
      assertThat(Identifiers.quote("events")).isEqualTo("`events`");
    }

    @Test
    void escapesBackticksInsideIdentifiers() {
      assertThat(Identifiers.quote("we`ird")).isEqualTo("`we\\`ird`");
    }

    @Test
    void escapesQuotesInsideStringLiterals() {
      assertThat(Identifiers.literal("it's")).isEqualTo("'it\\'s'");
    }

    @Test
    void escapesABackslashAlreadyPresentInTheInput() {
      assertThat(Identifiers.quote("back\\slash")).isEqualTo("`back\\\\slash`");
    }
  }
}
