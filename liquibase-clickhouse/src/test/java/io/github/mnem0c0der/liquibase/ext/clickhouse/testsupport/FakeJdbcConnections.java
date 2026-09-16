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

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import liquibase.database.jvm.JdbcConnection;

/** Creates fake JDBC connections that report a given database product name. */
public final class FakeJdbcConnections {

  private FakeJdbcConnections() {}

  public static JdbcConnection withProductName(String productName) {
    return withProductNameAndClosedState(productName, false);
  }

  /**
   * Same fake connection as {@link #withProductName}, but {@code isClosed()} reports {@code closed}
   * instead of always {@code false}, so a test can simulate a connection that has already been
   * closed out from under whatever is still using it.
   */
  public static JdbcConnection withProductNameAndClosedState(String productName, boolean closed) {
    DatabaseMetaData metaData =
        (DatabaseMetaData)
            Proxy.newProxyInstance(
                FakeJdbcConnections.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "getDatabaseProductName" -> productName;
                      case "getDatabaseProductVersion" -> "0.0.0";
                      case "getDatabaseMajorVersion", "getDatabaseMinorVersion" -> 0;
                      case "getURL" -> "jdbc:clickhouse://localhost:8123/default";
                      case "getUserName" -> "default";
                      // AbstractJdbcDatabase.setConnection() calls attached(), which upper-cases
                      // this unconditionally; a real driver never returns null for it.
                      case "getSQLKeywords" -> "";
                      default -> defaultValueFor(method.getReturnType());
                    });

    Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                FakeJdbcConnections.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  if ("getMetaData".equals(method.getName())) {
                    return metaData;
                  }
                  if ("isClosed".equals(method.getName())) {
                    return closed;
                  }
                  return defaultValueFor(method.getReturnType());
                });

    return new JdbcConnection(connection);
  }

  private static Object defaultValueFor(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == void.class) {
      return null;
    }
    return 0;
  }
}
