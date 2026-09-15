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

/**
 * One row of the changelog lock table.
 *
 * @param lockId unique identifier of this lock attempt
 * @param locked true for a claim, false for a release
 * @param grantedAt when the claim was made; used to detect abandoned locks
 * @param version monotonically increasing row version; ReplacingMergeTree keeps the highest
 * @param lockedBy human-readable owner description, for diagnostics
 */
public record LockCandidate(
    String lockId, boolean locked, Instant grantedAt, long version, String lockedBy) {}
