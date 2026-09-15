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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator;

import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterPolicy;
import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterPolicyFactory;
import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import java.util.Arrays;
import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.AbstractSqlGenerator;
import liquibase.statement.SqlStatement;

/**
 * Base class for all ClickHouse SQL generators: handles priority, database matching, and access to
 * the current cluster topology, so subclasses only deal with their own statement.
 */
public abstract class AbstractClickHouseSqlGenerator<T extends SqlStatement>
    extends AbstractSqlGenerator<T> {

  @Override
  public int getPriority() {
    return PRIORITY_DATABASE;
  }

  @Override
  public boolean supports(T statement, Database database) {
    return database instanceof ClickHouseDatabase;
  }

  @Override
  public ValidationErrors validate(T statement, Database database, SqlGeneratorChain<T> chain) {
    return new ValidationErrors();
  }

  protected ClusterPolicy clusterPolicy() {
    return ClusterPolicyFactory.fromConfiguration();
  }

  protected Sql[] sql(String... statements) {
    return Arrays.stream(statements).map(UnparsedSql::new).toArray(Sql[]::new);
  }

  /**
   * Returns the table name qualified by catalog. ClickHouse has no schema, so the name has at most
   * two parts.
   */
  protected String qualifiedTableName(Database database, String catalogName, String tableName) {
    String catalog =
        catalogName == null || catalogName.isBlank()
            ? database.getDefaultCatalogName()
            : catalogName;

    return catalog == null || catalog.isBlank()
        ? Identifiers.quote(tableName)
        : Identifiers.quote(catalog) + "." + Identifiers.quote(tableName);
  }
}
