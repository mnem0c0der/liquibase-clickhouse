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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sql;

import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterPolicy;
import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.StandaloneClusterPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Собирает CREATE TABLE для ClickHouse.
 *
 * <p>Порядок секций задан грамматикой ClickHouse и не является свободным: ENGINE, PRIMARY KEY,
 * ORDER BY, PARTITION BY, TTL, SETTINGS, COMMENT.
 */
public final class ClickHouseDdlBuilder {

  private final String qualifiedTableName;
  private final List<String> columns = new ArrayList<>();
  private ClusterPolicy clusterPolicy = StandaloneClusterPolicy.INSTANCE;
  private String engine = "MergeTree";
  private List<String> primaryKey = List.of();
  private List<String> orderBy = List.of();
  private String partitionBy;
  private String ttl;
  private String settings;
  private String comment;

  private ClickHouseDdlBuilder(String qualifiedTableName) {
    this.qualifiedTableName = qualifiedTableName;
  }

  public static ClickHouseDdlBuilder createTable(String qualifiedTableName) {
    return new ClickHouseDdlBuilder(qualifiedTableName);
  }

  public ClickHouseDdlBuilder onCluster(ClusterPolicy clusterPolicy) {
    this.clusterPolicy = clusterPolicy;
    return this;
  }

  public ClickHouseDdlBuilder column(String quotedName, String type) {
    columns.add(quotedName + " " + type);
    return this;
  }

  public ClickHouseDdlBuilder columnWithDefault(
      String quotedName, String type, String defaultExpression) {
    columns.add(quotedName + " " + type + " DEFAULT " + defaultExpression);
    return this;
  }

  public ClickHouseDdlBuilder engine(String engine) {
    this.engine = engine;
    return this;
  }

  public ClickHouseDdlBuilder primaryKey(List<String> quotedColumns) {
    this.primaryKey = List.copyOf(quotedColumns);
    return this;
  }

  public ClickHouseDdlBuilder orderBy(List<String> quotedColumns) {
    this.orderBy = List.copyOf(quotedColumns);
    return this;
  }

  public ClickHouseDdlBuilder partitionBy(String expression) {
    this.partitionBy = expression;
    return this;
  }

  public ClickHouseDdlBuilder ttl(String expression) {
    this.ttl = expression;
    return this;
  }

  public ClickHouseDdlBuilder settings(String settings) {
    this.settings = settings;
    return this;
  }

  public ClickHouseDdlBuilder comment(String comment) {
    this.comment = comment;
    return this;
  }

  public String build() {
    StringBuilder sql = new StringBuilder("CREATE TABLE ").append(qualifiedTableName);
    sql.append(clusterPolicy.onClusterClause());
    sql.append(" (").append(String.join(", ", columns)).append(")");
    sql.append(" ENGINE = ").append(clusterPolicy.resolveEngine(engine));

    if (!primaryKey.isEmpty()) {
      sql.append(" PRIMARY KEY ").append(tuple(primaryKey));
    }

    sql.append(" ORDER BY ").append(orderBy.isEmpty() ? "tuple()" : tuple(orderBy));

    if (partitionBy != null) {
      sql.append(" PARTITION BY ").append(partitionBy);
    }
    if (ttl != null) {
      sql.append(" TTL ").append(ttl);
    }
    if (settings != null) {
      sql.append(" SETTINGS ").append(settings);
    }
    if (comment != null) {
      sql.append(" COMMENT ").append(Identifiers.literal(comment));
    }

    return sql.toString();
  }

  private static String tuple(List<String> quotedColumns) {
    StringJoiner joiner = new StringJoiner(", ", "(", ")");
    quotedColumns.forEach(joiner::add);
    return joiner.toString();
  }
}
