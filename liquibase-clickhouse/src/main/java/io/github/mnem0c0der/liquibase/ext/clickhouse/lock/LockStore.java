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
package io.github.mnem0c0der.liquibase.ext.clickhouse.lock;

import java.time.Instant;
import java.util.List;
import liquibase.exception.DatabaseException;

/**
 * Surface of the changelog lock table that {@link ClickHouseLockService} depends on.
 *
 * <p>{@link LockRepository} is the only production implementation. The interface exists so tests
 * can substitute a fake and drive acquire, release, and heartbeat renewal deterministically,
 * without a live ClickHouse server.
 */
public interface LockStore {

  void createTableIfMissing() throws DatabaseException;

  /** Inserts a brand new claim. */
  void claim(String lockId, long version, String lockedBy) throws DatabaseException;

  /** Re-inserts a still-held claim, preserving its original {@code claimedAt}. */
  void renew(String lockId, Instant claimedAt, long version, String lockedBy)
      throws DatabaseException;

  /** Inserts a release row for {@code lockId}. */
  void release(String lockId, Instant claimedAt, long version, String lockedBy)
      throws DatabaseException;

  /** Reads every row together with the server instant the read observed. */
  LockSnapshot readAll() throws DatabaseException;

  /** Computes the next version from a snapshot's rows, avoiding a redundant read. */
  long nextVersion(List<LockCandidate> rows);

  /** Reads the current rows itself before computing the next version. */
  long nextVersion() throws DatabaseException;

  void dropTable() throws DatabaseException;
}
