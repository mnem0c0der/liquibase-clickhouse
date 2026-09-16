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

/**
 * Result of {@link LockRepository#readAll()}: the lock rows together with the ClickHouse server
 * instant the read observed.
 *
 * @param rows every row currently in the lock table
 * @param serverNow the server's clock at the time of the read, safe to compare against {@code
 *     renewedAt}/{@code claimedAt} since all three come from the same clock
 */
public record LockSnapshot(List<LockCandidate> rows, Instant serverNow) {}
