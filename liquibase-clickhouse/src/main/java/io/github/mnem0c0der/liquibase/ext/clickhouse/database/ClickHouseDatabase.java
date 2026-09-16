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
package io.github.mnem0c0der.liquibase.ext.clickhouse.database;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import liquibase.database.AbstractJdbcDatabase;
import liquibase.database.DatabaseConnection;
import liquibase.exception.DatabaseException;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Schema;

/**
 * Liquibase {@link liquibase.database.Database} implementation for ClickHouse.
 *
 * <p>Declares the capabilities ClickHouse lacks (transactional DDL, sequences, foreign keys) so
 * that Liquibase does not generate SQL ClickHouse would reject.
 */
public class ClickHouseDatabase extends AbstractJdbcDatabase {

  public static final String PRODUCT_NAME = "ClickHouse";
  public static final String SHORT_NAME = "clickhouse";

  private static final String JDBC_URL_PREFIX = "jdbc:clickhouse";
  private static final String DRIVER_CLASS_NAME = "com.clickhouse.jdbc.ClickHouseDriver";
  private static final int DEFAULT_HTTP_PORT = 8123;

  @Override
  protected String getDefaultDatabaseProductName() {
    return PRODUCT_NAME;
  }

  @Override
  public String getShortName() {
    return SHORT_NAME;
  }

  @Override
  public Integer getDefaultPort() {
    return DEFAULT_HTTP_PORT;
  }

  @Override
  public int getPriority() {
    return PRIORITY_DATABASE;
  }

  @Override
  public boolean isCorrectDatabaseImplementation(DatabaseConnection connection)
      throws DatabaseException {
    return PRODUCT_NAME.equalsIgnoreCase(connection.getDatabaseProductName());
  }

  @Override
  public String getDefaultDriver(String url) {
    return url != null && url.startsWith(JDBC_URL_PREFIX) ? DRIVER_CLASS_NAME : null;
  }

  @Override
  public boolean supportsDDLInTransaction() {
    return false;
  }

  @Override
  public boolean supportsInitiallyDeferrableColumns() {
    return false;
  }

  @Override
  public boolean supportsSequences() {
    return false;
  }

  @Override
  public boolean supportsTablespaces() {
    return false;
  }

  @Override
  public boolean supportsAutoIncrement() {
    return false;
  }

  @Override
  public boolean supportsDropTableCascadeConstraints() {
    return false;
  }

  @Override
  public boolean supportsRestrictForeignKeys() {
    return false;
  }

  @Override
  public boolean supportsForeignKeyDisable() {
    return false;
  }

  @Override
  public boolean supportsPrimaryKeyNames() {
    return false;
  }

  @Override
  public boolean supportsNotNullConstraintNames() {
    return false;
  }

  @Override
  public boolean supportsCatalogs() {
    return true;
  }

  @Override
  public boolean supportsSchemas() {
    return false;
  }

  @Override
  public boolean supportsCatalogInObjectName(Class<? extends DatabaseObject> type) {
    return true;
  }

  @Override
  public boolean isSystemObject(DatabaseObject example) {
    if (example == null) {
      return false;
    }
    Schema schema = example.getSchema();
    String catalog = schema == null ? null : schema.getCatalogName();
    return "system".equalsIgnoreCase(catalog)
        || "INFORMATION_SCHEMA".equalsIgnoreCase(catalog)
        || super.isSystemObject(example);
  }

  @Override
  public String getCurrentDateTimeFunction() {
    return "now()";
  }

  @Override
  public boolean getAutoCommitMode() {
    return true;
  }

  @Override
  public boolean isSafeToRunUpdate() {
    return true;
  }

  @Override
  protected String getConnectionSchemaName() {
    return null;
  }

  @Override
  protected String getQuotingStartCharacter() {
    return "`";
  }

  @Override
  protected String getQuotingEndCharacter() {
    return "`";
  }

  @Override
  protected String getQuotingEndReplacement() {
    return "\\`";
  }

  @Override
  public String escapeStringForDatabase(String string) {
    // ClickHouse treats \ as an escape character inside string literals; the inherited
    // SQL-standard implementation only doubles quotes and leaves a trailing backslash
    // unescaped, which truncates the literal.
    return string == null ? null : Identifiers.escapeStringBody(string);
  }
}
