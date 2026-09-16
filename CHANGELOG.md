# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-09-16

### Added

- `ClickHouseDatabase` implementation, data type mapping (including the
  `Nullable(T)` wrapping ClickHouse's NOT-NULL-by-default columns need) and
  DDL/DML generators for the standard Liquibase change types.
- Optional clustered mode: `ON CLUSTER` DDL and `Replicated*` engines behind
  the `liquibase.clickhouse.cluster` configuration key.
- Append-only, optimistic changelog locking with a renewal heartbeat and
  stale-lock preemption, using quorum writes and sequential-consistency reads
  when clustered.
- `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK` backed by
  `ReplacingMergeTree`.
- `update` and `delete` issued as `ALTER TABLE ... UPDATE/DELETE` mutations
  with `mutations_sync`, so Liquibase does not move on before the change is
  visible.
- `createIndex` as a data-skipping (`minmax`) index, materialized over
  existing parts.
- `modifyDataType` that reads the column's current nullability back from
  `system.columns` and preserves it.
- Explicit refusal, with a working alternative, for constructs ClickHouse does
  not have: foreign keys, adding a primary key to an existing table,
  auto-increment, unique constraints and unique indexes, and sequences.
- On a cluster, `DATABASECHANGELOG` carries the same quorum writes and
  sequential-consistency reads as the lock table. Without them a migrator
  could read the changelog from a replica that had not yet received the
  previous migrator's record and apply the same changeset twice.
- Verified support for Liquibase 4.31.x and 5.0.x from a single artifact,
  covering Spring Boot 3 and Spring Boot 4.
