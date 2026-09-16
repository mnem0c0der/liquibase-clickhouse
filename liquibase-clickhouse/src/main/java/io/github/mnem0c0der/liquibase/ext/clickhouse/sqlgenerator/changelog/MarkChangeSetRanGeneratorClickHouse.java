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
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.changelog;

import io.github.mnem0c0der.liquibase.ext.clickhouse.changelog.ChangeLogTable;
import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterConsistencySettings;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import liquibase.ChecksumVersion;
import liquibase.Scope;
import liquibase.change.Change;
import liquibase.change.core.TagDatabaseChange;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.exception.LiquibaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.exception.ValidationErrors;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.MarkChangeSetRanStatement;
import liquibase.util.LiquibaseUtil;
import liquibase.util.StringUtil;

/**
 * Records that a changeset ran, or updates the record of one that ran before.
 *
 * <p>Liquibase core's own {@code MarkChangeSetRanGenerator} builds an {@code InsertStatement} or an
 * {@code UpdateStatement} and hands it back to {@code SqlGeneratorFactory}, which would route the
 * update to {@code UpdateGeneratorClickHouse} — a heavy {@code ALTER TABLE ... UPDATE} mutation,
 * and worse, one with no consistency settings of its own. This generator instead builds the row
 * insert directly, the same way {@code TagDatabaseGeneratorClickHouse} and {@code
 * clearAllCheckSums} do, and applies {@link ClusterConsistencySettings} to it. Without this
 * override, the very statement that records a changeset as applied was the one write against this
 * table with no quorum guarantee at all.
 *
 * <p>{@link ChangeSet.ExecType#FAILED} and {@link ChangeSet.ExecType#SKIPPED} are not recorded,
 * same as core. {@link ChangeSet.ExecType#EXECUTED} and {@link ChangeSet.ExecType#MARK_RAN} insert
 * a brand new row. {@link ChangeSet.ExecType#RERAN} — a changeset that already has a row — cannot
 * be updated in place, so it is reinserted at a higher {@code ROWVERSION} instead, matched by its
 * {@code ID}/{@code AUTHOR}/{@code FILENAME}, exactly as {@code tag()} reinserts the latest row
 * rather than updating it.
 */
public class MarkChangeSetRanGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<MarkChangeSetRanStatement> {

  @Override
  public ValidationErrors validate(
      MarkChangeSetRanStatement statement,
      Database database,
      SqlGeneratorChain<MarkChangeSetRanStatement> chain) {
    ValidationErrors errors = new ValidationErrors();
    errors.checkRequiredField("changeSet", statement.getChangeSet());
    return errors;
  }

  @Override
  public Sql[] generateSql(
      MarkChangeSetRanStatement statement,
      Database database,
      SqlGeneratorChain<MarkChangeSetRanStatement> chain) {

    ChangeSet changeSet = statement.getChangeSet();
    ChangeSet.ExecType execType = statement.getExecType();

    if (execType == ChangeSet.ExecType.FAILED || execType == ChangeSet.ExecType.SKIPPED) {
      return EMPTY_SQL;
    }

    String table = ChangeLogTable.qualifiedName(database);
    String tag = tagFromChangeSet(changeSet);
    String dateExecuted = database.getCurrentDateTimeFunction();
    String md5Sum = changeSet.generateCheckSum(ChecksumVersion.latest()).toString();
    String liquibaseVersion = liquibaseBuildVersion();
    String description = StringUtil.limitSize(changeSet.getDescription(), 250);
    String comments = StringUtil.limitSize(StringUtil.trimToEmpty(changeSet.getComments()), 250);
    String deploymentId = Scope.getCurrentScope().getDeploymentId();
    int orderExecuted = nextSequenceValue(database);

    Map<String, String> values = new LinkedHashMap<>();
    values.put("DATEEXECUTED", dateExecuted);
    values.put("ORDEREXECUTED", String.valueOf(orderExecuted));
    values.put("EXECTYPE", Identifiers.literal(execType.value));
    values.put("MD5SUM", Identifiers.literal(md5Sum));
    values.put("DESCRIPTION", nullableLiteral(description));
    values.put("COMMENTS", nullableLiteral(comments));
    values.put("LIQUIBASE", Identifiers.literal(liquibaseVersion));
    values.put("CONTEXTS", nullableLiteral(changeSet.buildFullContext()));
    values.put("LABELS", nullableLiteral(changeSet.buildFullLabels()));
    values.put("DEPLOYMENT_ID", nullableLiteral(deploymentId));

    return execType.ranBefore
        ? sql(reinsertExistingRow(table, changeSet, values, tag))
        : sql(insertNewRow(table, changeSet, values, tag));
  }

  /**
   * A first run: every column is known, so the row is inserted outright. {@code TAG} is written as
   * {@code NULL} unless this changeset carries a {@code tagDatabase} change of its own.
   */
  private String insertNewRow(
      String table, ChangeSet changeSet, Map<String, String> values, String tag) {
    Map<String, String> row = new LinkedHashMap<>(values);
    row.put("ID", Identifiers.literal(changeSet.getId()));
    row.put("AUTHOR", Identifiers.literal(changeSet.getAuthor()));
    row.put("FILENAME", Identifiers.literal(changeSet.getFilePath()));
    row.put("TAG", nullableLiteral(tag));
    row.put(ChangeLogTable.ROW_VERSION_COLUMN, "toUnixTimestamp64Milli(now64(3))");

    return "INSERT INTO "
        + table
        + " ("
        + ChangeLogTable.quotedColumnList()
        + ")"
        + ClusterConsistencySettings.forWrite(clusterPolicy())
        + " VALUES ("
        + valuesInColumnOrder(row)
        + ")";
  }

  /**
   * A re-run of a changeset that already has a row: ClickHouse cannot update it in place, so the
   * existing row (matched by {@code ID}/{@code AUTHOR}/{@code FILENAME}, unique per {@code ORDER
   * BY}) is reinserted with the listed columns replaced and every other column copied through
   * unchanged, at a higher {@code ROWVERSION}. {@code TAG} is overridden only when this changeset
   * carries a {@code tagDatabase} change of its own; otherwise it keeps whatever the existing row
   * already has, the same as core's {@code UPDATE} leaving it out of the {@code SET} list.
   */
  private String reinsertExistingRow(
      String table, ChangeSet changeSet, Map<String, String> values, String tag) {
    Map<String, String> overrides = new LinkedHashMap<>(values);
    if (tag != null) {
      overrides.put("TAG", Identifiers.literal(tag));
    }
    overrides.put(ChangeLogTable.ROW_VERSION_COLUMN, "toUnixTimestamp64Milli(now64(3))");

    String selectList = ChangeLogTable.selectListWith(overrides);

    return "INSERT INTO "
        + table
        + " ("
        + ChangeLogTable.quotedColumnList()
        + ") SELECT "
        + selectList
        + " FROM "
        + table
        + " FINAL WHERE `ID` = "
        + Identifiers.literal(changeSet.getId())
        + " AND `AUTHOR` = "
        + Identifiers.literal(changeSet.getAuthor())
        + " AND `FILENAME` = "
        + Identifiers.literal(changeSet.getFilePath())
        + ClusterConsistencySettings.forWrite(clusterPolicy());
  }

  /** Joins values in {@link ChangeLogTable#COLUMN_NAMES} order; fails loudly if one is missing. */
  private static String valuesInColumnOrder(Map<String, String> values) {
    return ChangeLogTable.COLUMN_NAMES.stream()
        .map(
            column -> {
              String value = values.get(column);
              if (value == null) {
                throw new IllegalStateException("Missing value for changelog column: " + column);
              }
              return value;
            })
        .collect(Collectors.joining(", "));
  }

  private static String nullableLiteral(String value) {
    return value == null ? "NULL" : Identifiers.literal(value);
  }

  private static int nextSequenceValue(Database database) {
    try {
      return Scope.getCurrentScope()
          .getSingleton(ChangeLogHistoryServiceFactory.class)
          .getChangeLogService(database)
          .getNextSequenceValue();
    } catch (LiquibaseException e) {
      throw new UnexpectedLiquibaseException(e);
    }
  }

  /**
   * Mirrors {@code MarkChangeSetRanGenerator.getTagFromChangeset}: core has no public equivalent.
   */
  private static String tagFromChangeSet(ChangeSet changeSet) {
    for (Change change : changeSet.getChanges()) {
      if (change instanceof TagDatabaseChange tagChange) {
        return tagChange.getTag();
      }
    }
    return null;
  }

  /**
   * Mirrors {@code MarkChangeSetRanGenerator.getLiquibaseBuildVersion}: core has no public
   * equivalent.
   */
  private static String liquibaseBuildVersion() {
    return StringUtil.limitSize(
        LiquibaseUtil.getBuildVersion()
            .replace("SNAPSHOT", "SNP")
            .replace("beta", "b")
            .replace("alpha", "b"),
        20);
  }
}
