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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.SqlValues;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.List;
import java.util.stream.Collectors;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.InsertSetStatement;
import liquibase.statement.core.InsertStatement;

/**
 * Batches a loadData row set into a single multi-row INSERT.
 *
 * <p>ClickHouse is built for large batched inserts; inserting one row at a time would create a
 * separate part per row and put unnecessary pressure on background merges.
 */
public class InsertSetGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<InsertSetStatement> {

  @Override
  public Sql[] generateSql(
      InsertSetStatement statement,
      Database database,
      SqlGeneratorChain<InsertSetStatement> chain) {

    List<InsertStatement> rows = statement.getStatements();
    if (rows.isEmpty()) {
      return sql();
    }

    String columns =
        rows.get(0).getColumnValues().keySet().stream()
            .map(Identifiers::quote)
            .collect(Collectors.joining(", "));

    String values =
        rows.stream()
            .map(
                row ->
                    row.getColumnValues().values().stream()
                        .map(value -> SqlValues.render(value, database))
                        .collect(Collectors.joining(", ", "(", ")")))
            .collect(Collectors.joining(", "));

    return sql(
        "INSERT INTO "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + " ("
            + columns
            + ") VALUES "
            + values);
  }
}
