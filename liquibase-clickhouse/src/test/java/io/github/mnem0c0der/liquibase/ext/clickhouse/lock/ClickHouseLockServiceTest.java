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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.ServiceLoader;
import liquibase.Scope;
import liquibase.database.core.PostgresDatabase;
import liquibase.lockservice.LockService;
import org.junit.jupiter.api.Test;

class ClickHouseLockServiceTest {

  @Test
  void isRegisteredAsAServiceProvider() {
    assertThat(ServiceLoader.load(LockService.class))
        .anyMatch(ClickHouseLockService.class::isInstance);
  }

  @Test
  void supportsOnlyClickHouse() {
    ClickHouseLockService service = new ClickHouseLockService();

    assertThat(service.supports(new ClickHouseDatabase())).isTrue();
    assertThat(service.supports(new PostgresDatabase())).isFalse();
  }

  @Test
  void outranksTheStandardLockService() {
    assertThat(new ClickHouseLockService().getPriority())
        .isGreaterThan(new liquibase.lockservice.StandardLockService().getPriority());
  }

  @Test
  void acquiresImmediatelyAndWithoutTouchingTheDatabaseWhenLockingIsDisabled() throws Exception {
    ClickHouseLockService service = new ClickHouseLockService();
    service.setDatabase(new ClickHouseDatabase());

    Boolean acquired =
        Scope.child(
            Map.of(ClickHouseConfiguration.LOCK_ENABLED.getKey(), "false"),
            () -> service.acquireLock());

    assertThat(acquired).isTrue();
    assertThat(service.hasChangeLogLock()).isTrue();
  }

  @Test
  void stopHeartbeatLeavesNoLiveHeartbeatThreadBehind() throws Exception {
    ClickHouseLockService service = new ClickHouseLockService();
    service.setDatabase(new ClickHouseDatabase());

    // The default lock.timeoutSeconds (300s) gives the heartbeat a ~100s sleep before its first
    // renewal round trip, so stopping it immediately below exercises the interrupt-while-sleeping
    // path only, without ever touching the (absent) database.
    service.startHeartbeat("test-lock-id", Instant.now());
    Thread heartbeat = service.currentHeartbeatThread();
    assertThat(heartbeat).isNotNull();
    assertThat(heartbeat.isAlive()).isTrue();

    service.stopHeartbeat();

    // stopHeartbeat() already joins internally; this join is a second, independent check that the
    // thread has actually terminated rather than merely been asked to.
    heartbeat.join(Duration.ofSeconds(2).toMillis());
    assertThat(heartbeat.getState()).isEqualTo(Thread.State.TERMINATED);
    assertThat(heartbeat.isAlive()).isFalse();
    assertThat(service.currentHeartbeatThread()).isNull();
  }
}
