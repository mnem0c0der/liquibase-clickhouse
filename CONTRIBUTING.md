# Contributing

## Requirements

- JDK 21 (the build enforces this; export it explicitly if your default JDK
  is newer: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`)
- Maven 3.9+
- Docker (for integration tests, which start real ClickHouse containers via
  Testcontainers)

## Before opening a pull request

```bash
mvn spotless:apply
mvn clean verify
mvn clean verify -Pliquibase-5
```

The first `verify` builds and tests everything — unit tests, standalone and
clustered integration tests, and both Spring Boot demos — against Liquibase
4.31.1 (the default). The second repeats all of it against Liquibase 5.0.4.
Both must pass locally before you open a pull request; CI runs the same
matrix, split across separate jobs so failures are easier to pinpoint.

## Rules that CI enforces

- The published `liquibase-clickhouse` module must have **zero** runtime
  dependencies. `liquibase-core` and `clickhouse-jdbc` are `provided`; the CI
  job fails the build if `dependency:list -DincludeScope=runtime` finds
  anything.
- Code compiles against Liquibase 4.31.1. Using API that only exists in 5.x
  breaks Spring Boot 3 support, since Boot 3 pulls in 4.31.1.
- Overrides of `LockService` methods (`init`, `destroy`, `forceReleaseLock`)
  declare `throws DatabaseException`, not the wider `throws LiquibaseException`
  that 5.x uses. The narrower clause is a valid override under both lines and
  is what keeps one jar working against both.
- Anything ClickHouse cannot do raises `UnsupportedClickHouseFeatureException`
  with a concrete alternative, from `validate()` so Liquibase reports it before
  touching the database. Never emit SQL that only looks correct — a changeset
  ClickHouse would silently misinterpret is worse than one that fails loudly.
- `spotless:check` runs on `verify` (google-java-format plus the Apache 2.0
  license header on every source file). Run `mvn spotless:apply` before
  committing rather than fighting the formatter by hand.

## Project layout

- `liquibase-clickhouse` — the published extension. No dependency on anything
  outside `provided`/`test` scope.
- `liquibase-clickhouse-integration-tests` — Testcontainers-based integration
  tests, standalone and clustered. Never deployed.
- `examples/spring-boot-3-demo`, `examples/spring-boot-4-demo` — minimal Boot
  applications proving the extension needs no code, only the dependency on the
  classpath. Never deployed.
- `docker/standalone`, `docker/cluster` — compose files for running ClickHouse
  locally the same way the integration tests do.

## Commit style

Plain [Conventional Commits](https://www.conventionalcommits.org/) subject
lines (`fix:`, `feat:`, `test:`, `docs:`, `chore:`, `ci:`).
