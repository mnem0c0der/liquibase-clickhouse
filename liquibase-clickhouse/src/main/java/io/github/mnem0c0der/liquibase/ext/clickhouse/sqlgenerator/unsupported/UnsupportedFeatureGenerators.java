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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.unsupported;

import io.github.mnem0c0der.liquibase.ext.clickhouse.exception.UnsupportedClickHouseFeatureException;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.function.Supplier;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.AddAutoIncrementStatement;
import liquibase.statement.core.AddForeignKeyConstraintStatement;
import liquibase.statement.core.AddPrimaryKeyStatement;
import liquibase.statement.core.AddUniqueConstraintStatement;
import liquibase.statement.core.CreateSequenceStatement;

/**
 * Generators that deliberately refuse to run.
 *
 * <p>The constructs listed here do not exist in ClickHouse. Silently ignoring them would produce a
 * schema that diverges from the changelog, so each generator instead stops the migration and points
 * at a working alternative.
 */
public final class UnsupportedFeatureGenerators {

  private UnsupportedFeatureGenerators() {}

  private abstract static class Refusing<T extends SqlStatement>
      extends AbstractClickHouseSqlGenerator<T> {

    private final Supplier<UnsupportedClickHouseFeatureException> refusal;

    Refusing(Supplier<UnsupportedClickHouseFeatureException> refusal) {
      this.refusal = refusal;
    }

    @Override
    public Sql[] generateSql(T statement, Database database, SqlGeneratorChain<T> chain) {
      throw refusal.get();
    }
  }

  public static class ForeignKey extends Refusing<AddForeignKeyConstraintStatement> {
    public ForeignKey() {
      super(UnsupportedClickHouseFeatureException::foreignKeys);
    }
  }

  public static class PrimaryKey extends Refusing<AddPrimaryKeyStatement> {
    public PrimaryKey() {
      super(UnsupportedClickHouseFeatureException::primaryKeyOnExistingTable);
    }
  }

  public static class AutoIncrement extends Refusing<AddAutoIncrementStatement> {
    public AutoIncrement() {
      super(UnsupportedClickHouseFeatureException::autoIncrement);
    }
  }

  public static class UniqueConstraint extends Refusing<AddUniqueConstraintStatement> {
    public UniqueConstraint() {
      super(UnsupportedClickHouseFeatureException::uniqueConstraints);
    }
  }

  public static class Sequence extends Refusing<CreateSequenceStatement> {
    public Sequence() {
      super(UnsupportedClickHouseFeatureException::sequences);
    }
  }
}
