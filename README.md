# liquibase-clickhouse

A Liquibase extension for [ClickHouse](https://clickhouse.com). It works
standalone or against a replicated cluster, and a single artifact supports
both Liquibase 4.31.x and 5.0.x — which in practice means both Spring Boot 3
and Spring Boot 4.

## Installation

```xml
<dependency>
  <groupId>io.github.mnem0c0der</groupId>
  <artifactId>liquibase-clickhouse</artifactId>
  <version>1.0.0</version>
</dependency>
```

The extension has no runtime dependencies of its own. Bring your own
`liquibase-core` (4.31.1 or newer, including 5.x) and
`com.clickhouse:clickhouse-jdbc`. Liquibase discovers the extension through
`META-INF/services` as soon as it is on the classpath — there is nothing to
register in code.

### Spring Boot

Adding the dependency is most of the setup, but what else you need depends on
which Boot line you're on, and getting this wrong fails silently:

- **Spring Boot 3** needs `spring-boot-starter-jdbc` (for the `DataSource`)
  plus `liquibase-core` explicitly — Boot 3 doesn't pull it in on its own.
- **Spring Boot 4** needs `spring-boot-starter-liquibase` instead.
  Boot 4 moved `LiquibaseAutoConfiguration` out of
  `spring-boot-autoconfigure` into its own module, and
  `spring-boot-starter-jdbc` no longer brings it in. **If you only add
  `spring-boot-starter-jdbc` under Boot 4, your changelog never runs — no
  error, no log line, nothing.** `spring-boot-starter-liquibase` pulls in
  `spring-boot-starter-jdbc` transitively, so you don't need both.

Either way, add `com.clickhouse:clickhouse-jdbc` and this extension, point
`spring.datasource.url` at your ClickHouse instance, and the standard
`spring.liquibase.*` properties apply as normal.

```xml
<!-- Spring Boot 3 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
  <groupId>org.liquibase</groupId>
  <artifactId>liquibase-core</artifactId>
</dependency>
```

```xml
<!-- Spring Boot 4 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-liquibase</artifactId>
</dependency>
<dependency>
  <groupId>org.liquibase</groupId>
  <artifactId>liquibase-core</artifactId>
</dependency>
```

Working, tested examples of both live in `examples/spring-boot-3-demo` and
`examples/spring-boot-4-demo`.

## Configuration

Every key below can be set in `liquibase.properties`, as a `-D` system
property, or as the corresponding environment variable. Liquibase matches
environment variables case-insensitively, treating `.` and camelCase word
boundaries the same as `_`, so `liquibase.clickhouse.lock.timeoutSeconds`
matches `LIQUIBASE_CLICKHOUSE_LOCK_TIMEOUT_SECONDS`.

| Key | Default | Meaning |
|---|---|---|
| `liquibase.clickhouse.cluster` | unset | Cluster name. When set, DDL is issued with `ON CLUSTER "<name>"` and `*MergeTree` engines are rewritten to their `Replicated*` counterparts. Leave unset for standalone. |
| `liquibase.clickhouse.tableEngine` | `MergeTree` | Default engine for `createTable` when a changeset doesn't specify one. |
| `liquibase.clickhouse.zookeeperPath` | `/clickhouse/tables/{shard}/{database}/{table}` | Keeper path template passed to `Replicated*` engines. Only used when `cluster` is set. |
| `liquibase.clickhouse.replicaName` | `{replica}` | Replica name template passed to `Replicated*` engines. Only used when `cluster` is set. |
| `liquibase.clickhouse.mutationsSync` | `2` | Value of ClickHouse's `mutations_sync` setting, applied to the `ALTER TABLE ... UPDATE/DELETE` statements `update` and `delete` compile to. `2` waits for all replicas before Liquibase moves on. |
| `liquibase.clickhouse.lock.timeoutSeconds` | `300` | Age after which a held changelog lock is treated as stale and may be preempted. The lock holder heartbeats well inside this window, so a legitimately long-running migration is never mistaken for a crashed one. |
| `liquibase.clickhouse.lock.pollIntervalMillis` | `500` | Delay between lock acquisition attempts, and the heartbeat's base period. |
| `liquibase.clickhouse.lock.enabled` | `true` | Set to `false` to skip changelog locking entirely. Only safe when exactly one migration process can ever run against the database at a time. |

## How this differs from a migration tool for a transactional database

ClickHouse has no transactions, no atomic `UPDATE ... WHERE` with an affected-
row count, and treats nullability as part of a column's type rather than as a
separate constraint. Several parts of the extension exist specifically
because of that.

**Columns are `NOT NULL` by default.** This is the opposite of the SQL
standard. A column your changelog doesn't mark `NOT NULL` is created as
`Nullable(T)`, so a changelog written with PostgreSQL or MySQL in mind keeps
the same meaning on ClickHouse instead of silently becoming non-nullable.

**`update` and `delete` are mutations.** They compile to
`ALTER TABLE ... UPDATE` / `... DELETE` with `mutations_sync` applied, so
Liquibase waits for the mutation to actually finish rather than moving on
while ClickHouse is still rewriting parts in the background. Mutations are
expensive on large tables — rewriting the affected parts, not modifying rows
in place — so prefer insert-only changelogs where you can.

**`modifyDataType` preserves the column's existing nullability.** Because
nullability is part of the type string in ClickHouse, a naive
`ALTER TABLE ... MODIFY COLUMN` with just the new type would silently drop a
`Nullable(...)` wrapper. The generator reads the column's current nullability
back from `system.columns` first and carries it forward.

**`createIndex` creates a data-skipping index**, `minmax` with granularity 1,
and immediately materializes it (`MATERIALIZE INDEX`) over parts that already
exist. `ADD INDEX` alone only affects parts written after it runs, which would
otherwise make the index quietly incomplete on any table with existing data.

**Changelog locking is optimistic, not exclusive, and append-only.**
`DATABASECHANGELOGLOCK` is a `ReplacingMergeTree` table; acquiring the lock
means inserting a claim row, rereading the table, and checking whether your
claim is the deterministic winner. The current holder runs a background
heartbeat that keeps re-inserting its row well before `lock.timeoutSeconds`
elapses, so a migration that legitimately takes a while is never confused with
one that crashed. A lock whose holder has stopped heartbeating is preempted
with a warning in the log, once it passes `lock.timeoutSeconds`. All the
timestamps involved — claim time, renewal time, "now" — come from the
ClickHouse server itself (`now64(3)`), never from the calling host's clock,
so two contending hosts with skewed clocks can never disagree about whose
claim is older.

On a cluster, the same lock table uses quorum inserts (`insert_quorum =
'auto'`) and sequential-consistency reads (`select_sequential_consistency =
1`), so a lagging replica can't cause two contenders to both believe they won.
Quorum inserts against that table are deliberately kept serialized
(`insert_quorum_parallel = 0`): ClickHouse's own documentation says sequential
consistency does not hold while parallel quorum inserts are enabled, since
they can land on different sets of replicas. The cost is that only one
claim/renew/release write can be in flight at a time; a second one racing it
is rejected by ClickHouse with `UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE`, and
the extension treats that as ordinary contention and retries it rather than
failing the migration.

This concurrent-locking behavior is exercised by an integration test that
starts eight migrators against the same changelog at once and asserts every
changeset is applied exactly once. That test races threads within a single
JVM — it does not prove correctness across eight genuinely separate processes
or hosts, though the design (server-timestamped claims, quorum-consistent
reads) doesn't depend on being in one process.

## Unsupported features

These raise `UnsupportedClickHouseFeatureException` — during Liquibase's
validation pass, before anything is executed — rather than generating SQL that
ClickHouse would reject, or worse, accept with a different meaning than the
changelog intended.

| Change | Why | What to do instead |
|---|---|---|
| `addForeignKeyConstraint` | ClickHouse has no foreign keys | Enforce integrity in the application, or denormalize, as is customary for analytical workloads |
| `addPrimaryKey` on an existing table | The sorting key (`ORDER BY`) is fixed at table creation | Declare it via `ORDER BY` in `createTable`, or create a new table and copy the data across |
| `addAutoIncrement` | No identity columns | Generate ids in the application, or use a `DEFAULT` expression such as `generateUUIDv4()` |
| `addUniqueConstraint` | No uniqueness enforcement | Use a `ReplacingMergeTree` engine, or deduplicate in the application before inserting |
| `createIndex` with `unique="true"` | No unique indexes | As above |
| `createSequence` / `alterSequence` / `dropSequence` / `renameSequence` | No sequences | Generate ids in the application, or `DEFAULT generateUUIDv4()` |

## Development

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # if your default JDK isn't 21

mvn clean verify                     # everything (unit, integration, both demos) on Liquibase 4.31.1
mvn clean verify -Pliquibase-5       # same, on Liquibase 5.0.4
```

The integration tests manage their own ClickHouse containers through
Testcontainers (a single node for standalone tests, a full two-shard,
two-replica cluster with Keeper for the clustered ones) — Docker needs to be
running, but there's nothing to start by hand. `docker/standalone` and
`docker/cluster` hold the same compose files for running ClickHouse locally
by hand, e.g. to try a changelog interactively with the `liquibase` CLI:

```bash
docker compose -f docker/standalone/docker-compose.yml up -d
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full checklist before opening a
pull request, and [RELEASING.md](RELEASING.md) for how versions are cut and
published.

## License

Apache License 2.0. See [LICENSE](LICENSE).
