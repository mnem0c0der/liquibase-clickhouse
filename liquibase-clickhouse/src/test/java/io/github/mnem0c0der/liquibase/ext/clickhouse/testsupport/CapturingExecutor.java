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
package io.github.mnem0c0der.liquibase.ext.clickhouse.testsupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import liquibase.database.Database;
import liquibase.executor.AbstractExecutor;
import liquibase.executor.ExecutorService;
import liquibase.sql.visitor.SqlVisitor;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.RawSqlStatement;

/**
 * A {@code liquibase.executor.Executor} that records the raw SQL text of every statement it is
 * given instead of running it, so a test can assert on exactly what a generator or service sends
 * without a real ClickHouse server.
 *
 * <p>Only {@code RawSqlStatement} is supported, since that is the only statement type this
 * extension's own repository and history-service classes hand an {@code Executor} directly (every
 * other statement goes through a {@code SqlGenerator} instead, which can be tested without an
 * executor at all).
 */
public final class CapturingExecutor extends AbstractExecutor {

  private final List<String> statements = new ArrayList<>();

  /** Registers a fresh {@code CapturingExecutor} as the "jdbc" executor for {@code database}. */
  public static CapturingExecutor installFor(Database database) {
    CapturingExecutor executor = new CapturingExecutor();
    executor.setDatabase(database);
    liquibase.Scope.getCurrentScope()
        .getSingleton(ExecutorService.class)
        .setExecutor("jdbc", database, executor);
    return executor;
  }

  /** Every captured statement's raw SQL, in call order. */
  public List<String> statements() {
    return Collections.unmodifiableList(statements);
  }

  /** The most recently captured statement's raw SQL. */
  public String lastStatement() {
    if (statements.isEmpty()) {
      throw new IllegalStateException("No statement was captured");
    }
    return statements.get(statements.size() - 1);
  }

  @Override
  public String getName() {
    return "jdbc";
  }

  @Override
  public int getPriority() {
    return PRIORITY_DEFAULT;
  }

  @Override
  public void execute(SqlStatement sql) {
    statements.add(rawSql(sql));
  }

  @Override
  public void execute(SqlStatement sql, List<SqlVisitor> sqlVisitors) {
    execute(sql);
  }

  @Override
  public List<Map<String, ?>> queryForList(SqlStatement sql) {
    statements.add(rawSql(sql));
    return List.of();
  }

  @Override
  public List<Map<String, ?>> queryForList(SqlStatement sql, List<SqlVisitor> sqlVisitors) {
    return queryForList(sql);
  }

  @Override
  @SuppressWarnings("rawtypes")
  public List queryForList(SqlStatement sql, Class elementType) {
    throw new UnsupportedOperationException();
  }

  @Override
  @SuppressWarnings("rawtypes")
  public List queryForList(SqlStatement sql, Class elementType, List<SqlVisitor> sqlVisitors) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T queryForObject(SqlStatement sql, Class<T> requiredType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T queryForObject(
      SqlStatement sql, Class<T> requiredType, List<SqlVisitor> sqlVisitors) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long queryForLong(SqlStatement sql) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long queryForLong(SqlStatement sql, List<SqlVisitor> sqlVisitors) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int queryForInt(SqlStatement sql) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int queryForInt(SqlStatement sql, List<SqlVisitor> sqlVisitors) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(SqlStatement sql) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(SqlStatement sql, List<SqlVisitor> sqlVisitors) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void comment(String message) {
    // No-op: nothing captures comments.
  }

  @Override
  public boolean updatesDatabase() {
    return true;
  }

  private static String rawSql(SqlStatement sql) {
    if (sql instanceof RawSqlStatement rawSqlStatement) {
      return rawSqlStatement.getSql();
    }
    throw new IllegalArgumentException(
        "CapturingExecutor only supports RawSqlStatement, got: " + sql);
  }
}
