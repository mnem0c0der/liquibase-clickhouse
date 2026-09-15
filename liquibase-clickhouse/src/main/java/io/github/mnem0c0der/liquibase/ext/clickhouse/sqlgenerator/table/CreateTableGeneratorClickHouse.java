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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.ClickHouseDdlBuilder;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.SqlValues;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.LinkedHashSet;
import java.util.List;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.CreateTableStatement;

public class CreateTableGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<CreateTableStatement> {

  @Override
  public Sql[] generateSql(
      CreateTableStatement statement,
      Database database,
      SqlGeneratorChain<CreateTableStatement> chain) {

    ClickHouseDdlBuilder builder =
        ClickHouseDdlBuilder.createTable(
                qualifiedTableName(database, statement.getCatalogName(), statement.getTableName()))
            .onCluster(clusterPolicy())
            .engine(ClickHouseConfiguration.TABLE_ENGINE.getCurrentValue());

    // getColumns() is a raw list: a column registered through both addColumn and
    // addPrimaryKeyColumn appears twice, which would emit a duplicate definition.
    for (String columnName : new LinkedHashSet<>(statement.getColumns())) {
      String type = TableColumns.renderType(statement, columnName, database);
      Object defaultValue = statement.getDefaultValue(columnName);

      if (defaultValue == null) {
        builder.column(Identifiers.quote(columnName), type);
      } else {
        builder.columnWithDefault(
            Identifiers.quote(columnName), type, SqlValues.render(defaultValue, database));
      }
    }

    List<String> keyColumns = sortingKey(statement);
    if (!keyColumns.isEmpty()) {
      builder.primaryKey(keyColumns).orderBy(keyColumns);
    }

    if (statement.getRemarks() != null) {
      builder.comment(statement.getRemarks());
    }

    return sql(builder.build());
  }

  private static List<String> sortingKey(CreateTableStatement statement) {
    if (statement.getPrimaryKeyConstraint() == null
        || statement.getPrimaryKeyConstraint().getColumns().isEmpty()) {
      return List.of();
    }
    return statement.getPrimaryKeyConstraint().getColumns().stream()
        .map(Identifiers::quote)
        .toList();
  }
}
