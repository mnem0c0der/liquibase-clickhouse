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
import liquibase.exception.ValidationErrors;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.AddAutoIncrementStatement;
import liquibase.statement.core.AddForeignKeyConstraintStatement;
import liquibase.statement.core.AddPrimaryKeyStatement;
import liquibase.statement.core.AddUniqueConstraintStatement;
import liquibase.statement.core.AlterSequenceStatement;
import liquibase.statement.core.CreateSequenceStatement;
import liquibase.statement.core.DropSequenceStatement;
import liquibase.statement.core.RenameSequenceStatement;

/**
 * Generators that deliberately refuse to run.
 *
 * <p>The constructs listed here do not exist in ClickHouse. Silently ignoring them would produce a
 * schema that diverges from the changelog, so each generator instead stops the migration and points
 * at a working alternative. The refusal is reported from {@code validate}, which Liquibase runs
 * over the whole changelog before applying anything; {@code generateSql} throws the same exception
 * as a backstop for any path that skips validation.
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
    public ValidationErrors validate(T statement, Database database, SqlGeneratorChain<T> chain) {
      return new ValidationErrors().addError(refusal.get().getMessage());
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

  public static class CreateSequence extends Refusing<CreateSequenceStatement> {
    public CreateSequence() {
      super(UnsupportedClickHouseFeatureException::sequences);
    }
  }

  public static class AlterSequence extends Refusing<AlterSequenceStatement> {
    public AlterSequence() {
      super(UnsupportedClickHouseFeatureException::sequences);
    }
  }

  public static class DropSequence extends Refusing<DropSequenceStatement> {
    public DropSequence() {
      super(UnsupportedClickHouseFeatureException::sequences);
    }
  }

  public static class RenameSequence extends Refusing<RenameSequenceStatement> {
    public RenameSequence() {
      super(UnsupportedClickHouseFeatureException::sequences);
    }
  }
}
