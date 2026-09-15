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
package io.github.mnem0c0der.liquibase.ext.clickhouse.exception;

import liquibase.exception.UnexpectedLiquibaseException;

/**
 * Бросается, когда changeset требует того, чего в ClickHouse нет.
 *
 * <p>Расширение сознательно падает вместо генерации SQL, который сервер отвергнет или, хуже, примет
 * с другим смыслом. В сообщении всегда указывается работающая альтернатива.
 */
public class UnsupportedClickHouseFeatureException extends UnexpectedLiquibaseException {

  private static final String GENERATE_IDS_IN_APPLICATION =
      "Generate identifiers in the application, or use a DEFAULT expression such as"
          + " generateUUIDv4().";

  private static final String DEDUPLICATE_INSTEAD =
      "Deduplicate with a ReplacingMergeTree engine, or enforce uniqueness in the application"
          + " before inserting.";

  public UnsupportedClickHouseFeatureException(String feature, String alternative) {
    super(
        "ClickHouse does not support "
            + feature
            + ". "
            + alternative
            + " See https://github.com/mnem0c0der/liquibase-clickhouse#unsupported-features");
  }

  public static UnsupportedClickHouseFeatureException autoIncrement() {
    return new UnsupportedClickHouseFeatureException(
        "auto-increment columns", GENERATE_IDS_IN_APPLICATION);
  }

  public static UnsupportedClickHouseFeatureException sequences() {
    return new UnsupportedClickHouseFeatureException("sequences", GENERATE_IDS_IN_APPLICATION);
  }

  public static UnsupportedClickHouseFeatureException uniqueIndexes() {
    return new UnsupportedClickHouseFeatureException("unique indexes", DEDUPLICATE_INSTEAD);
  }

  public static UnsupportedClickHouseFeatureException uniqueConstraints() {
    return new UnsupportedClickHouseFeatureException("unique constraints", DEDUPLICATE_INSTEAD);
  }

  public static UnsupportedClickHouseFeatureException foreignKeys() {
    return new UnsupportedClickHouseFeatureException(
        "foreign key constraints",
        "Enforce referential integrity in the application, or denormalise the data as is"
            + " customary for analytical workloads.");
  }

  public static UnsupportedClickHouseFeatureException primaryKeyOnExistingTable() {
    return new UnsupportedClickHouseFeatureException(
        "adding a primary key to an existing table",
        "The sorting key is fixed at creation time: declare it via ORDER BY in createTable,"
            + " or create a new table and copy the data across.");
  }
}
