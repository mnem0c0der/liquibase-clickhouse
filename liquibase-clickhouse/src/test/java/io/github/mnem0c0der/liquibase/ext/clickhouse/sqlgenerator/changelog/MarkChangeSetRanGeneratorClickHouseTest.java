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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import java.util.Map;
import liquibase.Scope;
import liquibase.change.core.TagDatabaseChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.core.MarkChangeSetRanStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarkChangeSetRanGeneratorClickHouseTest {

  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    database = new ClickHouseDatabase();
    database.setDefaultCatalogName("analytics");
  }

  private static ChangeSet changeSet(String id) {
    return new ChangeSet(
        id,
        "liquibase-clickhouse",
        false,
        false,
        "changelog.xml",
        null,
        null,
        new DatabaseChangeLog());
  }

  private String generate(MarkChangeSetRanStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return Arrays.stream(sql).map(Sql::toSql).findFirst().orElse("");
  }

  @Test
  void aFirstRunInsertsANewRowWithNoConsistencySettingsWhenStandalone() {
    MarkChangeSetRanStatement statement =
        new MarkChangeSetRanStatement(changeSet("1-create-events"), ChangeSet.ExecType.EXECUTED);

    String sql = generate(statement);

    assertThat(sql)
        .startsWith(
            "INSERT INTO `analytics`.`DATABASECHANGELOG` (`ID`, `AUTHOR`, `FILENAME`,"
                + " `DATEEXECUTED`, `ORDEREXECUTED`, `EXECTYPE`, `MD5SUM`, `DESCRIPTION`,"
                + " `COMMENTS`, `LIQUIBASE`, `CONTEXTS`, `LABELS`, `DEPLOYMENT_ID`, `TAG`,"
                + " `ROWVERSION`) VALUES ('1-create-events', 'liquibase-clickhouse',"
                + " 'changelog.xml', now(), ")
        .contains("'EXECUTED'")
        .doesNotContain("SETTINGS");
  }

  @Test
  void aFirstRunCarriesWriteConsistencySettingsBeforeValuesWhenClustered() throws Exception {
    MarkChangeSetRanStatement statement =
        new MarkChangeSetRanStatement(changeSet("1-create-events"), ChangeSet.ExecType.EXECUTED);

    String sql =
        Scope.child(
            Map.of("liquibase.clickhouse.cluster", "analytics_cluster"), () -> generate(statement));

    assertThat(sql)
        .as("SETTINGS must precede VALUES: ClickHouse rejects it after VALUES")
        .contains(
            "`ROWVERSION`) SETTINGS insert_quorum = 'auto', insert_quorum_parallel = 0,"
                + " async_insert = 0 VALUES (");
  }

  @Test
  void markRanAlsoInsertsANewRow() {
    MarkChangeSetRanStatement statement =
        new MarkChangeSetRanStatement(changeSet("1-create-events"), ChangeSet.ExecType.MARK_RAN);

    assertThat(generate(statement)).contains("'MARK_RAN'").doesNotContainIgnoringCase("SELECT");
  }

  @Test
  void aChangeSetCarryingItsOwnTagChangeWritesTheTagColumn() {
    ChangeSet changeSet = changeSet("6-tag");
    TagDatabaseChange tagChange = new TagDatabaseChange();
    tagChange.setTag("v1");
    changeSet.addChange(tagChange);

    String sql = generate(new MarkChangeSetRanStatement(changeSet, ChangeSet.ExecType.EXECUTED));

    assertThat(sql).contains("'v1'");
  }

  @Test
  void failedAndSkippedAreNotRecorded() {
    assertThat(generate(new MarkChangeSetRanStatement(changeSet("x"), ChangeSet.ExecType.FAILED)))
        .isEmpty();
    assertThat(generate(new MarkChangeSetRanStatement(changeSet("x"), ChangeSet.ExecType.SKIPPED)))
        .isEmpty();
  }

  @Test
  void aRerunReinsertsTheExistingRowMatchedByItsNaturalKeyRatherThanUpdatingInPlace() {
    MarkChangeSetRanStatement statement =
        new MarkChangeSetRanStatement(changeSet("1-create-events"), ChangeSet.ExecType.RERAN);

    String sql = generate(statement);

    assertThat(sql)
        .startsWith(
            "INSERT INTO `analytics`.`DATABASECHANGELOG` (`ID`, `AUTHOR`, `FILENAME`,"
                + " `DATEEXECUTED`, `ORDEREXECUTED`, `EXECTYPE`, `MD5SUM`, `DESCRIPTION`,"
                + " `COMMENTS`, `LIQUIBASE`, `CONTEXTS`, `LABELS`, `DEPLOYMENT_ID`, `TAG`,"
                + " `ROWVERSION`) SELECT `ID`, `AUTHOR`, `FILENAME`, now() AS `DATEEXECUTED`, ")
        .contains(
            "FROM `analytics`.`DATABASECHANGELOG` FINAL WHERE `ID` = '1-create-events' AND"
                + " `AUTHOR` = 'liquibase-clickhouse' AND `FILENAME` = 'changelog.xml'")
        .doesNotContain("SETTINGS")
        .doesNotContainIgnoringCase("UPDATE");
  }

  @Test
  void aRerunCarriesWriteConsistencySettingsAtTheEndWhenClustered() throws Exception {
    MarkChangeSetRanStatement statement =
        new MarkChangeSetRanStatement(changeSet("1-create-events"), ChangeSet.ExecType.RERAN);

    String sql =
        Scope.child(
            Map.of("liquibase.clickhouse.cluster", "analytics_cluster"), () -> generate(statement));

    assertThat(sql)
        .endsWith(
            "AND `FILENAME` = 'changelog.xml' SETTINGS insert_quorum = 'auto',"
                + " insert_quorum_parallel = 0, async_insert = 0");
  }

  @Test
  void aRerunLeavesTheTagColumnUntouchedWhenTheChangeSetCarriesNoTagChange() {
    MarkChangeSetRanStatement statement =
        new MarkChangeSetRanStatement(changeSet("1-create-events"), ChangeSet.ExecType.RERAN);

    String sql = generate(statement);

    assertThat(sql).contains("`TAG`,").doesNotContain("AS `TAG`");
  }
}
