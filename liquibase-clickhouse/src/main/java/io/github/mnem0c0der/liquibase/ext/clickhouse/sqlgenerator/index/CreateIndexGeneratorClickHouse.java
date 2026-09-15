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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.index;

import io.github.mnem0c0der.liquibase.ext.clickhouse.exception.UnsupportedClickHouseFeatureException;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.Arrays;
import java.util.stream.Collectors;
import liquibase.change.AddColumnConfig;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.CreateIndexStatement;

/**
 * Creates a data-skipping index.
 *
 * <p>Always emits two statements: {@code ADD INDEX} only applies to new parts, so it is immediately
 * followed by {@code MATERIALIZE INDEX}, which applies the index to data already written. The index
 * is always {@code minmax GRANULARITY 1}, since {@link CreateIndexStatement} carries no index type
 * and {@code minmax} is the one meaningful for any column type.
 */
public class CreateIndexGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<CreateIndexStatement> {

  private static final String DEFAULT_INDEX_TYPE = "minmax";
  private static final int DEFAULT_GRANULARITY = 1;

  @Override
  public Sql[] generateSql(
      CreateIndexStatement statement,
      Database database,
      SqlGeneratorChain<CreateIndexStatement> chain) {

    if (Boolean.TRUE.equals(statement.isUnique())) {
      throw UnsupportedClickHouseFeatureException.uniqueIndexes();
    }

    String table =
        qualifiedTableName(database, statement.getTableCatalogName(), statement.getTableName());
    String indexName = Identifiers.quote(statement.getIndexName());
    String onCluster = clusterPolicy().onClusterClause();

    String columns =
        Arrays.stream(statement.getColumns())
            .map(AddColumnConfig::getName)
            .map(Identifiers::quote)
            .collect(Collectors.joining(", "));

    return sql(
        "ALTER TABLE "
            + table
            + onCluster
            + " ADD INDEX "
            + indexName
            + " ("
            + columns
            + ") TYPE "
            + DEFAULT_INDEX_TYPE
            + " GRANULARITY "
            + DEFAULT_GRANULARITY,
        "ALTER TABLE " + table + onCluster + " MATERIALIZE INDEX " + indexName);
  }
}
