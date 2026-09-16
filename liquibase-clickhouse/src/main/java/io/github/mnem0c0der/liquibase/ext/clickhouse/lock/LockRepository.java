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
package io.github.mnem0c0der.liquibase.ext.clickhouse.lock;

import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterPolicy;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.executor.Executor;
import liquibase.executor.ExecutorService;
import liquibase.statement.core.RawSqlStatement;

/**
 * The only place the extension reads or writes the lock table.
 *
 * <p>The table is append-only: acquiring, renewing, and releasing the lock are all an INSERT of a
 * new row with a higher version, and a ReplacingMergeTree read with FINAL keeps only the latest
 * version per lockId. On a replicated table a lagging read could let two contenders each see
 * themselves as the winner, so writes carry {@code insert_quorum = 'auto'} and reads carry {@code
 * select_sequential_consistency = 1}; both apply only to this table's own statements, and only when
 * {@link ClusterPolicy#isClustered()}. {@code insert_quorum_parallel} stays at its default of
 * {@code 0}: ClickHouse's own documentation for {@code select_sequential_consistency} says
 * sequential consistency does not work while {@code insert_quorum_parallel} is enabled, because
 * parallel quorum inserts can land on different sets of replicas, so no single replica is
 * guaranteed to have every write. With parallelism off, a contender racing another for this table
 * can be rejected with UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE (error 286); {@link QuorumWriteRetry}
 * treats that as the contention signal it is and retries rather than failing the caller outright.
 *
 * <p>Every {@code LOCKCLAIMED} and {@code LOCKRENEWED} value is written by ClickHouse itself
 * ({@code now64(3)}), never by the calling host's own clock, so two contending hosts can never
 * disagree about a claim's age even if their local clocks have drifted apart.
 *
 * <p>Implements {@link LockStore}, the seam {@link ClickHouseLockService} depends on, so tests can
 * substitute a fake in its place.
 */
public final class LockRepository implements LockStore {

  private static final String LOCK_TABLE = "DATABASECHANGELOGLOCK";

  /**
   * {@code 'auto'} waits for a majority of replicas rather than a fixed count, because this
   * repository has no way to know how many replicas the cluster actually has.
   *
   * <p>{@code insert_quorum_parallel} is left at its default of {@code 0} (disabled), not enabled:
   * enabling it silently breaks {@code select_sequential_consistency} on reads (see the class
   * Javadoc), which is the guarantee {@link OptimisticLockArbiter} actually depends on to see every
   * contender's write. The cost of leaving it disabled is that ClickHouse allows only one in-flight
   * quorum insert per table at a time; a second contender's claim racing the first is rejected with
   * {@code UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE} (error 286) instead of being queued. That is
   * exactly the contention every claim/renew/release write is retried for, in {@link
   * QuorumWriteRetry}.
   *
   * <p>{@code async_insert = 0} is required too: recent ClickHouse servers default {@code
   * async_insert} to on, and a quorum insert through the async path refuses to run at all unless
   * {@code insert_quorum_parallel} is enabled ({@code UNSUPPORTED_PARAMETER}) &mdash; satisfied
   * here, but the async path buffers writes rather than confirming them immediately, which this
   * repository's single-row claim/renew/release inserts have no use for.
   */
  private static final String WRITE_CONSISTENCY_SETTINGS =
      " SETTINGS insert_quorum = 'auto', insert_quorum_parallel = 0, async_insert = 0";

  private static final String READ_CONSISTENCY_SETTINGS =
      " SETTINGS select_sequential_consistency = 1";

  private final Database database;
  private final ClusterPolicy clusterPolicy;

  public LockRepository(Database database, ClusterPolicy clusterPolicy) {
    this.database = database;
    this.clusterPolicy = clusterPolicy;
  }

  @Override
  public void createTableIfMissing() throws DatabaseException {
    String engine = clusterPolicy.resolveEngine("ReplacingMergeTree(`LOCKVERSION`)");

    execute(
        "CREATE TABLE IF NOT EXISTS "
            + qualifiedName()
            + clusterPolicy.onClusterClause()
            + " (`ID` Int32, `LOCKID` String, `LOCKED` UInt8, `LOCKCLAIMED` DateTime64(3),"
            + " `LOCKRENEWED` DateTime64(3), `LOCKEDBY` String, `LOCKVERSION` UInt64)"
            + " ENGINE = "
            + engine
            + " ORDER BY (`LOCKID`)");
  }

  /**
   * Inserts a brand new claim. Both {@code LOCKCLAIMED} and {@code LOCKRENEWED} are the server's
   * current instant, since a fresh claim has not been renewed yet.
   *
   * <p>Retried on quorum contention: a retried claim is just another row version for the same
   * {@code lockId}, which the ReplacingMergeTree collapse already resolves, so re-sending it is
   * never a blind retry.
   */
  @Override
  public void claim(String lockId, long version, String lockedBy) throws DatabaseException {
    String sql =
        "INSERT INTO "
            + qualifiedName()
            + " (`ID`, `LOCKID`, `LOCKED`, `LOCKCLAIMED`, `LOCKRENEWED`, `LOCKEDBY`,"
            + " `LOCKVERSION`)"
            + writeConsistencySettings()
            + " VALUES (1, "
            + Identifiers.literal(lockId)
            + ", 1, now64(3), now64(3), "
            + Identifiers.literal(lockedBy)
            + ", "
            + version
            + ")";
    QuorumWriteRetry.execute(() -> execute(sql));
  }

  /**
   * Re-inserts a still-held claim. {@code claimedAt} is written back exactly as the caller read it,
   * so precedence never shifts across renewals; {@code LOCKRENEWED} is the server's current
   * instant.
   */
  @Override
  public void renew(String lockId, Instant claimedAt, long version, String lockedBy)
      throws DatabaseException {
    insertLockedState(lockId, true, claimedAt, version, lockedBy);
  }

  /**
   * Inserts a release row. {@code claimedAt} is written back exactly as the caller read it, purely
   * for diagnostics: a release row is never a candidate for {@code currentHolder}.
   */
  @Override
  public void release(String lockId, Instant claimedAt, long version, String lockedBy)
      throws DatabaseException {
    insertLockedState(lockId, false, claimedAt, version, lockedBy);
  }

  /**
   * Retried on quorum contention, like {@link #claim}: a retried renew/release is just another row
   * version for the same {@code lockId}, which the ReplacingMergeTree collapse already resolves.
   */
  private void insertLockedState(
      String lockId, boolean locked, Instant claimedAt, long version, String lockedBy)
      throws DatabaseException {
    String sql =
        "INSERT INTO "
            + qualifiedName()
            + " (`ID`, `LOCKID`, `LOCKED`, `LOCKCLAIMED`, `LOCKRENEWED`, `LOCKEDBY`,"
            + " `LOCKVERSION`)"
            + writeConsistencySettings()
            + " VALUES (1, "
            + Identifiers.literal(lockId)
            + ", "
            + (locked ? 1 : 0)
            + ", fromUnixTimestamp64Milli("
            + claimedAt.toEpochMilli()
            + "), now64(3), "
            + Identifiers.literal(lockedBy)
            + ", "
            + version
            + ")";
    QuorumWriteRetry.execute(() -> execute(sql));
  }

  /**
   * Reads every row in the table together with the server's current instant, so a caller can
   * compare {@code renewedAt}/{@code claimedAt} against a {@code now} that came from the same clock
   * instead of its own. The server instant is fetched as a second, tiny statement (a plain {@code
   * now64(3)}, no table scan) because the row query alone returns nothing to attach it to when the
   * table is empty.
   */
  @Override
  public LockSnapshot readAll() throws DatabaseException {
    // Reading the instant before the rows is safe even though the two are not atomic: any drift
    // only makes the rows look newer than `now`, which biases staleness toward false negatives
    // and so can never preempt a still-live holder.
    Instant serverNow = readServerNow();

    List<Map<String, ?>> rows =
        executor()
            .queryForList(
                new RawSqlStatement(
                    "SELECT `LOCKID`, `LOCKED`, toUnixTimestamp64Milli(`LOCKCLAIMED`) AS"
                        + " `CLAIMEDMILLIS`, toUnixTimestamp64Milli(`LOCKRENEWED`) AS"
                        + " `RENEWEDMILLIS`, `LOCKEDBY`, `LOCKVERSION` FROM "
                        + qualifiedName()
                        + " FINAL"
                        + readConsistencySettings()));

    List<LockCandidate> candidates = new ArrayList<>(rows.size());
    for (Map<String, ?> row : rows) {
      candidates.add(
          new LockCandidate(
              String.valueOf(value(row, "LOCKID")),
              ((Number) value(row, "LOCKED")).intValue() != 0,
              Instant.ofEpochMilli(((Number) value(row, "CLAIMEDMILLIS")).longValue()),
              Instant.ofEpochMilli(((Number) value(row, "RENEWEDMILLIS")).longValue()),
              ((Number) value(row, "LOCKVERSION")).longValue(),
              String.valueOf(value(row, "LOCKEDBY"))));
    }
    return new LockSnapshot(candidates, serverNow);
  }

  private Instant readServerNow() throws DatabaseException {
    List<Map<String, ?>> rows =
        executor()
            .queryForList(
                new RawSqlStatement(
                    "SELECT toUnixTimestamp64Milli(now64(3)) AS `SERVERNOWMILLIS`"));
    return Instant.ofEpochMilli(((Number) value(rows.get(0), "SERVERNOWMILLIS")).longValue());
  }

  /**
   * Reads all rows itself before computing the version. Callers that already hold a fresh {@link
   * LockSnapshot} should call {@link #nextVersion(List)} instead to avoid a redundant round trip.
   */
  @Override
  public long nextVersion() throws DatabaseException {
    return nextVersion(readAll().rows());
  }

  /**
   * Versions are monotonic in time, so the current time in milliseconds is the base value.
   *
   * <p>Version no longer decides who wins the lock &mdash; {@code claimedAt} does &mdash; it only
   * decides which duplicate row ReplacingMergeTree keeps, so it stays safe to derive from the
   * client's own clock: {@code max(now, highest + 1)} is already monotonic against every version
   * this repository has seen, so a client with a fast clock cannot get ahead of that guarantee.
   */
  @Override
  public long nextVersion(List<LockCandidate> rows) {
    long now = System.currentTimeMillis();
    long highest = rows.stream().mapToLong(LockCandidate::version).max().orElse(0L);
    return Math.max(now, highest + 1);
  }

  @Override
  public void dropTable() throws DatabaseException {
    execute("DROP TABLE IF EXISTS " + qualifiedName() + clusterPolicy.onClusterClause());
  }

  private String qualifiedName() {
    String catalog =
        database.getLiquibaseCatalogName() == null
            ? database.getDefaultCatalogName()
            : database.getLiquibaseCatalogName();

    String table = Identifiers.quote(LOCK_TABLE);
    return catalog == null || catalog.isBlank() ? table : Identifiers.quote(catalog) + "." + table;
  }

  private String writeConsistencySettings() {
    return clusterPolicy.isClustered() ? WRITE_CONSISTENCY_SETTINGS : "";
  }

  private String readConsistencySettings() {
    return clusterPolicy.isClustered() ? READ_CONSISTENCY_SETTINGS : "";
  }

  private void execute(String sql) throws DatabaseException {
    executor().execute(new RawSqlStatement(sql));
  }

  private Executor executor() {
    return Scope.getCurrentScope()
        .getSingleton(ExecutorService.class)
        .getExecutor("jdbc", database);
  }

  /** Drivers register column names with different casing, so the lookup ignores case. */
  private static Object value(Map<String, ?> row, String column) {
    Object direct = row.get(column);
    if (direct != null) {
      return direct;
    }
    return row.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(column))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Column " + column + " missing from result"));
  }
}
