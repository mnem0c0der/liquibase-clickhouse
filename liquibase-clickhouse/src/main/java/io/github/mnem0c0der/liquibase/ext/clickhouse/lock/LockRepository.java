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
 * themselves as the winner, so writes carry {@code insert_quorum = 'auto'} with {@code
 * insert_quorum_parallel = 0} and reads carry {@code select_sequential_consistency = 1}; both apply
 * only to this table's own statements, and only when {@link ClusterPolicy#isClustered()}.
 */
public final class LockRepository {

  private static final String LOCK_TABLE = "DATABASECHANGELOGLOCK";

  /**
   * {@code 'auto'} waits for a majority of replicas rather than a fixed count, because this
   * repository has no way to know how many replicas the cluster actually has.
   */
  private static final String WRITE_CONSISTENCY_SETTINGS =
      " SETTINGS insert_quorum = 'auto', insert_quorum_parallel = 0";

  private static final String READ_CONSISTENCY_SETTINGS =
      " SETTINGS select_sequential_consistency = 1";

  private final Database database;
  private final ClusterPolicy clusterPolicy;

  public LockRepository(Database database, ClusterPolicy clusterPolicy) {
    this.database = database;
    this.clusterPolicy = clusterPolicy;
  }

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

  public void insert(LockCandidate candidate) throws DatabaseException {
    execute(
        "INSERT INTO "
            + qualifiedName()
            + " (`ID`, `LOCKID`, `LOCKED`, `LOCKCLAIMED`, `LOCKRENEWED`, `LOCKEDBY`,"
            + " `LOCKVERSION`) VALUES (1, "
            + Identifiers.literal(candidate.lockId())
            + ", "
            + (candidate.locked() ? 1 : 0)
            + ", fromUnixTimestamp64Milli("
            + candidate.claimedAt().toEpochMilli()
            + "), fromUnixTimestamp64Milli("
            + candidate.renewedAt().toEpochMilli()
            + "), "
            + Identifiers.literal(candidate.lockedBy())
            + ", "
            + candidate.version()
            + ")"
            + writeConsistencySettings());
  }

  public List<LockCandidate> readAll() throws DatabaseException {
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
    return candidates;
  }

  /** Versions are monotonic in time, so the current time in milliseconds is the base value. */
  public long nextVersion() throws DatabaseException {
    long now = System.currentTimeMillis();
    long highest = readAll().stream().mapToLong(LockCandidate::version).max().orElse(0L);
    return Math.max(now, highest + 1);
  }

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
