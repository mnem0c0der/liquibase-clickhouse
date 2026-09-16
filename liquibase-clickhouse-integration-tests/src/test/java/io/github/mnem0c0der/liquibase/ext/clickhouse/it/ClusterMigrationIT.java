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
package io.github.mnem0c0der.liquibase.ext.clickhouse.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.SingletonScopeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Runs the extension's {@code ON CLUSTER} and quorum-lock code paths against a real replicated
 * ClickHouse cluster (two shards of two replicas each, coordinated by a real Keeper).
 *
 * <p>Every other integration test in this module runs against a single standalone node, so {@code
 * OnClusterPolicy}'s engine rewriting and {@code LockRepository}'s quorum settings have never
 * actually executed before this class. Both tests here talk to shard 1's two replicas (ch-s1r1,
 * ch-s1r2) directly, never through a Distributed table, so the same physical
 * DATABASECHANGELOG/DATABASECHANGELOGLOCK/events data is reachable from both connections and a
 * lagging or inconsistent replica would be caught rather than silently read from whichever replica
 * happens to already agree.
 */
class ClusterMigrationIT {

  private static final String CLUSTER = "analytics_cluster";
  private static final String[] SHARD_ONE_REPLICAS = {"ch-s1r1", "ch-s1r2"};
  private static final int MIGRATORS = 8;

  @SuppressWarnings("resource")
  private static final ComposeContainer CLUSTER_STACK =
      new ComposeContainer(new File("../docker/cluster/docker-compose.yml"))
          .withLocalCompose(true)
          .withExposedService(
              "ch-s1r1", 8123, Wait.forHttp("/ping").withStartupTimeout(Duration.ofMinutes(4)))
          .withExposedService(
              "ch-s1r2", 8123, Wait.forHttp("/ping").withStartupTimeout(Duration.ofMinutes(4)));

  @BeforeAll
  static void startCluster() {
    CLUSTER_STACK.start();
  }

  @AfterAll
  static void stopCluster() {
    CLUSTER_STACK.stop();
  }

  private static Connection connectTo(String service) throws Exception {
    String url =
        "jdbc:clickhouse://"
            + CLUSTER_STACK.getServiceHost(service, 8123)
            + ":"
            + CLUSTER_STACK.getServicePort(service, 8123)
            + "/analytics";

    Properties properties = new Properties();
    properties.setProperty("user", "default");
    properties.setProperty("password", "");
    return DriverManager.getConnection(url, properties);
  }

  /**
   * Runs the full-lifecycle changelog against one replica with the cluster name set for the
   * duration of the call only, via {@link Scope#child}, so it never leaks into other tests running
   * in the same JVM.
   *
   * <p>Liquibase's default {@code ScopeManager} is a {@code SingletonScopeManager}: one mutable
   * "current scope" field per instance, propagated to new threads by reference via an {@code
   * InheritableThreadLocal}. A thread pool created after this JVM has already touched {@code Scope}
   * inherits that same instance across every worker thread, so concurrent {@code
   * Scope.child}/{@code exit} calls from different pool threads race on one shared field and can
   * throw ("Cannot end scope X when currently at scope Y") — reliably here, since a clustered
   * quorum write is slow enough for two threads' push/pop windows to overlap; rarely on a fast
   * standalone server, which is why {@code ConcurrentMigrationIT} never surfaces it. Installing a
   * fresh, unshared manager as the first thing each call does breaks that inherited sharing.
   */
  private static void runMigrationOnCluster(String replica) throws Exception {
    Scope.setScopeManager(new SingletonScopeManager());
    Scope.child(
        Map.of(ClickHouseConfiguration.CLUSTER.getKey(), CLUSTER),
        () -> {
          try (Connection connection = connectTo(replica);
              Liquibase liquibase =
                  ClickHouseTestSupport.openLiquibase(
                      connection, "changelogs/full-lifecycle.xml")) {
            liquibase.update(new Contexts(), new LabelExpression());
          }
        });
  }

  private static List<String> engineOf(Connection connection, String table) throws Exception {
    return ClickHouseTestSupport.queryColumn(
        connection,
        "SELECT engine FROM system.tables WHERE database = 'analytics' AND name = '" + table + "'");
  }

  @Test
  void replicatesTheSchemaToEveryReplicaWhenAClusterIsConfigured() throws Exception {
    runMigrationOnCluster("ch-s1r1");

    try (Connection secondReplica = connectTo("ch-s1r2")) {
      assertThat(engineOf(secondReplica, "events"))
          .as("ON CLUSTER DDL must have reached the second replica")
          .containsExactly("ReplicatedMergeTree");

      // Read straight from system.tables rather than inferring from the CREATE statement text:
      // the changelog and lock tables go through the same engine-rewriting path as user tables,
      // and that path has never run against a real server before this test.
      assertThat(engineOf(secondReplica, "DATABASECHANGELOG"))
          .as("the changelog table itself must be replicated")
          .containsExactly("ReplicatedReplacingMergeTree");

      assertThat(engineOf(secondReplica, "DATABASECHANGELOGLOCK"))
          .as("the lock table itself must be replicated")
          .containsExactly("ReplicatedReplacingMergeTree");

      List<String> applied =
          ClickHouseTestSupport.queryColumn(
              secondReplica,
              "SELECT ID FROM analytics.DATABASECHANGELOG FINAL ORDER BY ORDEREXECUTED");

      assertThat(applied).hasSize(6).doesNotHaveDuplicates();
    }
  }

  /**
   * The clustered counterpart of {@code ConcurrentMigrationIT}'s 8-way race: contenders connect to
   * both replicas of shard 1 instead of all opening connections to one node, so {@code
   * LockRepository}'s {@code insert_quorum}/{@code select_sequential_consistency} settings are the
   * only thing standing between "every contender agrees who holds the lock" and two contenders on
   * different replicas each believing they won.
   */
  @Test
  void appliesEveryChangeSetExactlyOnceWhenContendersUseDifferentReplicas() throws Exception {
    CyclicBarrier startLine = new CyclicBarrier(MIGRATORS);
    ExecutorService pool = Executors.newFixedThreadPool(MIGRATORS);

    try {
      List<Callable<Void>> migrators =
          IntStream.range(0, MIGRATORS)
              .<Callable<Void>>mapToObj(
                  index ->
                      () -> {
                        String replica = SHARD_ONE_REPLICAS[index % SHARD_ONE_REPLICAS.length];
                        startLine.await(30, TimeUnit.SECONDS);
                        runMigrationOnCluster(replica);
                        return null;
                      })
              .toList();

      for (Future<Void> result : pool.invokeAll(migrators, 6, TimeUnit.MINUTES)) {
        result.get();
      }
    } catch (Exception e) {
      throw new AssertionError(
          "Clustered concurrent migration failed; lock state across both replicas of shard 1 at"
              + " the point of failure:\n"
              + dumpLockStateAcrossReplicas(),
          e);
    } finally {
      pool.shutdownNow();
    }

    // select_sequential_consistency = 1 so a read against either replica reflects every quorum
    // write, not whichever version has replicated to that specific node so far.
    try (Connection connection = connectTo("ch-s1r2")) {
      List<String> applied =
          ClickHouseTestSupport.queryColumn(
              connection,
              "SELECT ID FROM analytics.DATABASECHANGELOG FINAL ORDER BY ORDEREXECUTED"
                  + " SETTINGS select_sequential_consistency = 1");

      assertThat(applied)
          .as(
              "every changeset must be recorded exactly once, lock state:\n"
                  + dumpLockStateAcrossReplicas())
          .hasSize(6)
          .doesNotHaveDuplicates();

      List<String> seededRows =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT count() FROM events SETTINGS select_sequential_consistency = 1");

      assertThat(seededRows)
          .as("the seeding changeset must have inserted exactly one row")
          .containsExactly("1");
    }

    // Checked on both replicas independently: a phantom held row that only one replica has
    // received (or one that quorum considers released but a stale replica still shows as held)
    // is exactly the kind of split view this settings pair exists to prevent.
    for (String replica : SHARD_ONE_REPLICAS) {
      try (Connection connection = connectTo(replica)) {
        List<String> heldLocks =
            ClickHouseTestSupport.queryColumn(
                connection,
                "SELECT count() FROM DATABASECHANGELOGLOCK FINAL WHERE LOCKED = 1"
                    + " SETTINGS select_sequential_consistency = 1");

        assertThat(heldLocks)
            .as(
                "no lock may be left held on "
                    + replica
                    + " after every contender finishes; full state:\n"
                    + dumpLockStateAcrossReplicas())
            .containsExactly("0");
      }
    }
  }

  /** Full {@code DATABASECHANGELOGLOCK} contents from both replicas, for diagnosing a failure. */
  private static String dumpLockStateAcrossReplicas() {
    StringBuilder dump = new StringBuilder();
    for (String replica : SHARD_ONE_REPLICAS) {
      dump.append("-- ").append(replica).append(" --\n");
      try (Connection connection = connectTo(replica)) {
        List<String> rows =
            ClickHouseTestSupport.queryColumn(
                connection,
                "SELECT concat('LOCKID=', LOCKID, ' LOCKED=', toString(LOCKED), ' CLAIMED=',"
                    + " toString(LOCKCLAIMED), ' RENEWED=', toString(LOCKRENEWED), ' LOCKEDBY=',"
                    + " LOCKEDBY, ' LOCKVERSION=', toString(LOCKVERSION)) FROM"
                    + " DATABASECHANGELOGLOCK FINAL ORDER BY LOCKVERSION"
                    + " SETTINGS select_sequential_consistency = 1");
        rows.forEach(row -> dump.append(row).append('\n'));
      } catch (Exception e) {
        dump.append("failed to read lock state from ")
            .append(replica)
            .append(": ")
            .append(e)
            .append('\n');
      }
    }
    return dump.toString();
  }
}
