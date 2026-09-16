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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared scaffolding for the integration tests: starting a server, connecting, running Liquibase.
 */
public final class ClickHouseTestSupport {

  private ClickHouseTestSupport() {}

  public static ClickHouseContainer startServer() {
    String image = System.getProperty("clickhouse.image", "clickhouse/clickhouse-server:26.8");
    ClickHouseContainer container = new ClickHouseContainer(DockerImageName.parse(image));
    container.start();
    return container;
  }

  public static Connection connect(ClickHouseContainer container) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("user", container.getUsername());
    properties.setProperty("password", container.getPassword());
    return DriverManager.getConnection(container.getJdbcUrl(), properties);
  }

  public static Liquibase openLiquibase(Connection connection, String changelogPath)
      throws Exception {
    return new Liquibase(
        changelogPath,
        new ClassLoaderResourceAccessor(),
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection)));
  }

  public static List<String> queryColumn(Connection connection, String sql) throws Exception {
    List<String> values = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        values.add(resultSet.getString(1));
      }
    }
    return values;
  }
}
