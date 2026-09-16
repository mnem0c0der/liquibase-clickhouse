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

/**
 * The {@code SETTINGS} clauses that give a clustered write or read sequential consistency, shared
 * by every statement that touches one of the extension's own tracking tables: the lock table and
 * the changelog table. Both need the exact same guarantee for the exact same reason, so both go
 * through this one place rather than each keeping its own copy of the strings.
 *
 * <p>{@code insert_quorum = 'auto'} waits for a majority of replicas rather than a fixed count,
 * since neither caller knows how many replicas the cluster actually has. {@code
 * insert_quorum_parallel} stays at its default of {@code 0}: ClickHouse's own documentation for
 * {@code select_sequential_consistency} says sequential consistency does not work while {@code
 * insert_quorum_parallel} is enabled, because parallel quorum inserts can land on different sets of
 * replicas, so no single replica is guaranteed to have every write. {@code async_insert = 0} is
 * required too: recent ClickHouse servers default {@code async_insert} to on, and a quorum insert
 * through the async path refuses to run at all unless {@code insert_quorum_parallel} is enabled
 * ({@code UNSUPPORTED_PARAMETER}). {@code select_sequential_consistency = 1} makes a read wait for
 * every quorum-acknowledged write instead of returning whatever the replica it happens to land on
 * has replicated so far.
 *
 * <p>Both strings are verified against a real ClickHouse 26.8 server. Applied only when {@link
 * ClusterPolicy#isClustered()}: a standalone node has no replicas to disagree, and quorum settings
 * on a single node only add pointless overhead.
 */
public final class ClusterConsistencySettings {

  private static final String WRITE_SETTINGS =
      " SETTINGS insert_quorum = 'auto', insert_quorum_parallel = 0, async_insert = 0";

  private static final String READ_SETTINGS = " SETTINGS select_sequential_consistency = 1";

  private ClusterConsistencySettings() {}

  /**
   * Empty string, or a ready-to-append write-consistency {@code SETTINGS} clause. On an {@code
   * INSERT ... VALUES} statement this must be appended before {@code VALUES}; ClickHouse rejects
   * {@code SETTINGS} placed after it with {@code CANNOT_PARSE_INPUT_ASSERTION_FAILED}.
   */
  public static String forWrite(ClusterPolicy policy) {
    return policy.isClustered() ? WRITE_SETTINGS : "";
  }

  /** Empty string, or a ready-to-append read-consistency {@code SETTINGS} clause. */
  public static String forRead(ClusterPolicy policy) {
    return policy.isClustered() ? READ_SETTINGS : "";
  }
}
