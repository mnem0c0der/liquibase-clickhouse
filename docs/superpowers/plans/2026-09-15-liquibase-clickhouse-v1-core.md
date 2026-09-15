# liquibase-clickhouse v1.0 (ядро + кластер + релиз) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Выпустить работающее Liquibase-расширение для ClickHouse, пригодное к публикации в Maven Central, работающее под Spring Boot 3 и Spring Boot 4, в standalone- и кластерном режиме.

**Architecture:** Один публикуемый модуль без runtime-зависимостей, компилируемый против Liquibase 4.31.1 (`provided`), что даёт совместимость и с 4.31.x, и с 5.0.x. Регистрация через Java SPI (`META-INF/services`). Кластерная специфика изолирована в `ClusterPolicy`; отсутствие транзакций в ClickHouse обходится append-only-блокировкой с чистым, юнит-тестируемым арбитром.

**Tech Stack:** Java 21, Maven 3.9+, Liquibase 4.31.1 (compile) / 4.31.1 + 5.0.4 (runtime matrix), clickhouse-jdbc 0.10.0, JUnit 5.14.4, AssertJ 3.27.7, Testcontainers 1.21.4, ClickHouse 26.8 и 25.8, Spring Boot 3.5.16 и 4.1.1.

**Спека:** `docs/superpowers/specs/2026-09-15-liquibase-clickhouse-extension-design.md`

## Global Constraints

Требования ниже действуют для КАЖДОЙ задачи плана.

- **Java 21.** `maven.compiler.release` = `21`. Сборка обязана падать на более старой JDK (enforcer).
- **groupId** = `io.github.mnem0c0der`. **Базовый Java-пакет** = `io.github.mnem0c0der.liquibase.ext.clickhouse`. Никогда не `liquibase.ext.*` — это создало бы split-package с `liquibase-core`.
- **Лицензия** Apache License 2.0. Заголовок лицензии в каждом `.java`-файле, проверяется `spotless`.
- **Публикуемый модуль `liquibase-clickhouse` не имеет ни одной runtime-зависимости.** `liquibase-core` и `clickhouse-jdbc` — только `<scope>provided</scope>`. Добавление зависимости с `compile`-scope в этот модуль — ошибка, запрещённая `maven-enforcer-plugin` (`banTransitiveDependencies`).
- **Компиляция строго против Liquibase 4.31.1.** Использование API, появившегося только в 5.x, ломает поддержку Spring Boot 3.
- **В переопределениях `LockService` объявлять узкий `throws DatabaseException`**, а не `LiquibaseException`. Узкий `throws` легален и под 4.31.1, и под 5.0.x; широкий не скомпилируется под 4.31.1.
- **TDD обязателен.** Сначала падающий тест, затем минимальная реализация. Ни один шаг «написать реализацию» не выполняется раньше шага «убедиться, что тест падает».
- **Коммит после каждой задачи.** Сообщения в формате Conventional Commits.
- Каждое сообщение коммита заканчивается строкой:
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
- **Неподдерживаемая в ClickHouse конструкция обязана бросать `UnsupportedClickHouseFeatureException` с объяснением альтернативы.** Генерация «почти правильного» SQL запрещена.

## File Structure

```
pom.xml                                     parent (packaging=pom), dependencyManagement, общие плагины
liquibase-clickhouse/pom.xml                публикуемый модуль
liquibase-clickhouse/src/main/java/io/github/mnem0c0der/liquibase/ext/clickhouse/
  database/ClickHouseDatabase.java          Database SPI, декларация возможностей движка
  config/ClickHouseConfiguration.java       ConfigurationDefinition — единственный источник настроек
  cluster/ClusterPolicy.java                интерфейс: ON CLUSTER + выбор движка
  cluster/StandaloneClusterPolicy.java
  cluster/OnClusterPolicy.java
  cluster/ClusterPolicyFactory.java
  exception/UnsupportedClickHouseFeatureException.java
  datatype/*.java                           маппинг типов Liquibase → ClickHouse
  sql/ClickHouseDdlBuilder.java             сборка CREATE TABLE ... ENGINE ... ORDER BY ...
  sql/Identifiers.java                      единая точка квотирования идентификаторов
  sqlgenerator/AbstractClickHouseSqlGenerator.java   общая база всех генераторов
  sqlgenerator/*/                           по одному генератору на SqlStatement
  lock/LockCandidate.java                   value-объект строки-претендента
  lock/OptimisticLockArbiter.java           ЧИСТАЯ функция выбора победителя
  lock/LockRepository.java                  только SQL/IO
  lock/ClickHouseLockService.java           адаптер под Liquibase SPI
  changelog/ClickHouseChangeLogHistoryService.java
liquibase-clickhouse/src/main/resources/META-INF/services/
  liquibase.database.Database
  liquibase.sqlgenerator.SqlGenerator
  liquibase.datatype.LiquibaseDataType
  liquibase.lockservice.LockService
  liquibase.changelog.ChangeLogHistoryService
  liquibase.configuration.AutoloadedConfigurations
liquibase-clickhouse-integration-tests/     Testcontainers, skip deploy
examples/spring-boot-3-demo/                Boot 3.5.16, skip deploy
examples/spring-boot-4-demo/                Boot 4.1.1, skip deploy
docker/                                     compose для ручных запусков
.github/workflows/                          ci.yml, release.yml, codeql.yml
```

Принцип разбиения: один генератор — один `SqlStatement` — один файл. Файлы остаются маленькими и читаются целиком, а `ON CLUSTER` и квотирование идентификаторов живут ровно в одном месте каждый.

---

### Task 1: Maven-скелет, enforcer, гарантия «нуля runtime-зависимостей»

**Files:**
- Create: `pom.xml`
- Create: `liquibase-clickhouse/pom.xml`
- Create: `.gitignore`
- Create: `liquibase-clickhouse/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/BuildEnvironmentTest.java`

**Interfaces:**
- Consumes: ничего.
- Produces: свойства parent-POM, на которые ссылаются все последующие задачи: `${liquibase.version}` = `4.31.1`, `${clickhouse-jdbc.version}` = `0.10.0`, `${junit.version}` = `5.14.4`, `${assertj.version}` = `3.27.7`, `${testcontainers.version}` = `1.21.4`. Координаты публикуемого модуля: `io.github.mnem0c0der:liquibase-clickhouse:1.0.0-SNAPSHOT`.

> **Проверка версий плагинов.** Ниже зафиксированы стабильные релизы. Не подставляй `4.0.0-beta-*` для `maven-compiler-plugin`, `maven-jar-plugin`, `maven-source-plugin` — на момент планирования они в бете.
>
> **Testcontainers 1.21.4, не 2.x.** BOM `testcontainers-bom:2.0.5` существует, но модуля `org.testcontainers:clickhouse` версии 2.x нет — только 1.x.
>
> **Email в POM отсутствует намеренно.** POM публичен и попадёт в Maven Central навсегда. В блоке `<developers>` указаны только GitHub-id и URL. Добавляй email только если владелец репозитория явно этого захочет.

- [ ] **Step 1: Убедиться, что сборки ещё нет**

Run: `cd /Users/jizo/IdeaProjects/liquibase-clickhouse && mvn -q clean verify`
Expected: FAIL — `The goal you specified requires a project to execute but there is no POM in this directory`

- [ ] **Step 2: Создать parent POM**

Создай `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>io.github.mnem0c0der</groupId>
  <artifactId>liquibase-clickhouse-parent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <name>liquibase-clickhouse parent</name>
  <description>Liquibase extension for ClickHouse</description>
  <url>https://github.com/mnem0c0der/liquibase-clickhouse</url>

  <licenses>
    <license>
      <name>Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>

  <developers>
    <developer>
      <id>mnem0c0der</id>
      <url>https://github.com/mnem0c0der</url>
    </developer>
  </developers>

  <scm>
    <connection>scm:git:https://github.com/mnem0c0der/liquibase-clickhouse.git</connection>
    <developerConnection>scm:git:ssh://git@github.com/mnem0c0der/liquibase-clickhouse.git</developerConnection>
    <url>https://github.com/mnem0c0der/liquibase-clickhouse</url>
  </scm>

  <modules>
    <module>liquibase-clickhouse</module>
  </modules>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>

    <liquibase.version>4.31.1</liquibase.version>
    <clickhouse-jdbc.version>0.10.0</clickhouse-jdbc.version>
    <junit.version>5.14.4</junit.version>
    <assertj.version>3.27.7</assertj.version>
    <testcontainers.version>1.21.4</testcontainers.version>

    <maven-compiler-plugin.version>3.16.0</maven-compiler-plugin.version>
    <maven-enforcer-plugin.version>3.6.3</maven-enforcer-plugin.version>
    <maven-surefire-plugin.version>3.6.0</maven-surefire-plugin.version>
    <maven-failsafe-plugin.version>3.6.0</maven-failsafe-plugin.version>
    <maven-jar-plugin.version>3.5.1</maven-jar-plugin.version>
    <maven-source-plugin.version>3.4.0</maven-source-plugin.version>
    <maven-javadoc-plugin.version>3.12.0</maven-javadoc-plugin.version>
    <maven-gpg-plugin.version>3.2.8</maven-gpg-plugin.version>
    <spotless-plugin.version>3.10.2</spotless-plugin.version>
    <jacoco-plugin.version>0.8.15</jacoco-plugin.version>
    <central-publishing-plugin.version>0.11.0</central-publishing-plugin.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.junit</groupId>
        <artifactId>junit-bom</artifactId>
        <version>${junit.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>${testcontainers.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.liquibase</groupId>
        <artifactId>liquibase-core</artifactId>
        <version>${liquibase.version}</version>
      </dependency>
      <dependency>
        <groupId>com.clickhouse</groupId>
        <artifactId>clickhouse-jdbc</artifactId>
        <version>${clickhouse-jdbc.version}</version>
      </dependency>
      <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>${assertj.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>${maven-compiler-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>${maven-surefire-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-failsafe-plugin</artifactId>
          <version>${maven-failsafe-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-jar-plugin</artifactId>
          <version>${maven-jar-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-source-plugin</artifactId>
          <version>${maven-source-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-javadoc-plugin</artifactId>
          <version>${maven-javadoc-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-gpg-plugin</artifactId>
          <version>${maven-gpg-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>org.jacoco</groupId>
          <artifactId>jacoco-maven-plugin</artifactId>
          <version>${jacoco-plugin.version}</version>
        </plugin>
      </plugins>
    </pluginManagement>

    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>${maven-enforcer-plugin.version}</version>
        <executions>
          <execution>
            <id>enforce-build-environment</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <requireJavaVersion><version>[21,)</version></requireJavaVersion>
                <requireMavenVersion><version>[3.9.0,)</version></requireMavenVersion>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>

      <plugin>
        <groupId>com.diffplug.spotless</groupId>
        <artifactId>spotless-maven-plugin</artifactId>
        <version>${spotless-plugin.version}</version>
        <configuration>
          <java>
            <googleJavaFormat/>
            <licenseHeader>
              <content>/*
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
</content>
            </licenseHeader>
          </java>
        </configuration>
        <executions>
          <execution>
            <goals><goal>check</goal></goals>
            <phase>verify</phase>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Создать POM публикуемого модуля**

Создай `liquibase-clickhouse/pom.xml`. Обрати внимание: обе зависимости — `provided`, а `banTransitiveDependencies` гарантирует, что никто случайно не добавит `compile`-зависимость.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.github.mnem0c0der</groupId>
    <artifactId>liquibase-clickhouse-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>liquibase-clickhouse</artifactId>
  <packaging>jar</packaging>

  <name>liquibase-clickhouse</name>
  <description>Liquibase extension for ClickHouse: standalone and ON CLUSTER schema migrations</description>
  <url>https://github.com/mnem0c0der/liquibase-clickhouse</url>

  <dependencies>
    <dependency>
      <groupId>org.liquibase</groupId>
      <artifactId>liquibase-core</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>com.clickhouse</groupId>
      <artifactId>clickhouse-jdbc</artifactId>
      <scope>provided</scope>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <executions>
          <execution>
            <id>ban-runtime-dependencies</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <banTransitiveDependencies>
                  <excludes>
                    <exclude>*:*:*:*:provided</exclude>
                    <exclude>*:*:*:*:test</exclude>
                  </excludes>
                </banTransitiveDependencies>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>

      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <configuration>
          <archive>
            <manifestEntries>
              <Automatic-Module-Name>io.github.mnem0c0der.liquibase.ext.clickhouse</Automatic-Module-Name>
            </manifestEntries>
          </archive>
        </configuration>
      </plugin>

      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
        <executions>
          <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
          </execution>
          <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals><goal>report</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: Создать `.gitignore`**

```gitignore
target/
*.class
.idea/
*.iml
.DS_Store
```

- [ ] **Step 5: Написать тест окружения сборки**

Создай `liquibase-clickhouse/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/BuildEnvironmentTest.java`:

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildEnvironmentTest {

  @Test
  void compilesAndRunsOnJava21OrLater() {
    assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
  }

  @Test
  void liquibaseCoreIsOnTheTestClasspath() {
    assertThat(liquibase.database.AbstractJdbcDatabase.class).isNotNull();
  }
}
```

- [ ] **Step 6: Запустить сборку**

Run: `mvn -q clean verify`
Expected: PASS. Если `spotless` ругается на форматирование — выполни `mvn spotless:apply` и повтори.

- [ ] **Step 7: Проверить, что runtime-зависимостей действительно ноль**

Run:
```bash
mvn -q -pl liquibase-clickhouse dependency:list -DincludeScope=runtime -DoutputFile=/tmp/rt.txt && grep -cE '^\s+\S+:\S+:' /tmp/rt.txt
```
Expected: `0`. Любое другое число означает, что в модуль просочилась runtime-зависимость — это нарушение Global Constraints.

- [ ] **Step 8: Коммит**

```bash
git add pom.xml liquibase-clickhouse/pom.xml .gitignore liquibase-clickhouse/src
git commit -m "build: maven skeleton with java 21, enforcer and zero-runtime-dependency guard

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: `ClickHouseDatabase` — Database SPI

**Files:**
- Create: `liquibase-clickhouse/src/main/java/io/github/mnem0c0der/liquibase/ext/clickhouse/database/ClickHouseDatabase.java`
- Create: `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.database.Database`
- Create: `liquibase-clickhouse/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/testsupport/FakeJdbcConnections.java`
- Test: `liquibase-clickhouse/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/database/ClickHouseDatabaseTest.java`

**Interfaces:**
- Consumes: свойства POM из Task 1.
- Produces:
  - `ClickHouseDatabase extends liquibase.database.AbstractJdbcDatabase`
  - `public static final String ClickHouseDatabase.PRODUCT_NAME = "ClickHouse"`
  - `public static final String ClickHouseDatabase.SHORT_NAME = "clickhouse"`
  - `FakeJdbcConnections.withProductName(String productName)` → `liquibase.database.jvm.JdbcConnection` — тестовый помощник, используется в Task 4 и далее.

> **Почему `supportsSchemas() == false`, а `supportsCatalogs() == true`.** В ClickHouse есть ровно один уровень группировки объектов — `DATABASE`. Liquibase различает catalog и schema; отображаем ClickHouse-базу на catalog и честно сообщаем, что schema нет. Если объявить оба уровня, Liquibase начнёт генерировать двухсоставные имена вида `db.schema.table`, которых в ClickHouse не существует.

- [ ] **Step 1: Написать падающий тест**

Создай `FakeJdbcConnections.java` — помощник, позволяющий проверять определение СУБД без реального сервера:

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.testsupport;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import liquibase.database.jvm.JdbcConnection;

/** Создаёт JDBC-соединения-заглушки, отвечающие заданным именем продукта. */
public final class FakeJdbcConnections {

  private FakeJdbcConnections() {}

  public static JdbcConnection withProductName(String productName) {
    DatabaseMetaData metaData =
        (DatabaseMetaData)
            Proxy.newProxyInstance(
                FakeJdbcConnections.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "getDatabaseProductName" -> productName;
                      case "getDatabaseProductVersion" -> "0.0.0";
                      case "getDatabaseMajorVersion", "getDatabaseMinorVersion" -> 0;
                      case "getURL" -> "jdbc:clickhouse://localhost:8123/default";
                      case "getUserName" -> "default";
                      default -> defaultValueFor(method.getReturnType());
                    });

    Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                FakeJdbcConnections.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) ->
                    "getMetaData".equals(method.getName())
                        ? metaData
                        : defaultValueFor(method.getReturnType()));

    return new JdbcConnection(connection);
  }

  private static Object defaultValueFor(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == void.class) {
      return null;
    }
    return 0;
  }
}
```

Создай `ClickHouseDatabaseTest.java`:

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.database;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.testsupport.FakeJdbcConnections;
import java.util.ServiceLoader;
import liquibase.database.Database;
import org.junit.jupiter.api.Test;

class ClickHouseDatabaseTest {

  private final ClickHouseDatabase database = new ClickHouseDatabase();

  @Test
  void isRegisteredAsAServiceProvider() {
    assertThat(ServiceLoader.load(Database.class))
        .anyMatch(ClickHouseDatabase.class::isInstance);
  }

  @Test
  void identifiesItselfAsClickhouse() {
    assertThat(database.getShortName()).isEqualTo("clickhouse");
    assertThat(database.getDefaultPort()).isEqualTo(8123);
  }

  @Test
  void recognisesAClickHouseConnection() throws Exception {
    assertThat(database.isCorrectDatabaseImplementation(
            FakeJdbcConnections.withProductName("ClickHouse")))
        .isTrue();
  }

  @Test
  void rejectsANonClickHouseConnection() throws Exception {
    assertThat(database.isCorrectDatabaseImplementation(
            FakeJdbcConnections.withProductName("PostgreSQL")))
        .isFalse();
  }

  @Test
  void suggestsTheClickHouseDriverForClickHouseUrlsOnly() {
    assertThat(database.getDefaultDriver("jdbc:clickhouse://localhost:8123/default"))
        .isEqualTo("com.clickhouse.jdbc.ClickHouseDriver");
    assertThat(database.getDefaultDriver("jdbc:postgresql://localhost:5432/db")).isNull();
  }

  @Test
  void declaresEngineCapabilitiesHonestly() {
    assertThat(database.supportsDDLInTransaction()).isFalse();
    assertThat(database.supportsSequences()).isFalse();
    assertThat(database.supportsTablespaces()).isFalse();
    assertThat(database.supportsAutoIncrement()).isFalse();
    assertThat(database.supportsInitiallyDeferrableColumns()).isFalse();
    assertThat(database.supportsRestrictForeignKeys()).isFalse();
    assertThat(database.supportsPrimaryKeyNames()).isFalse();
    assertThat(database.supportsNotNullConstraintNames()).isFalse();
  }

  @Test
  void mapsClickHouseDatabasesToCatalogsRatherThanSchemas() {
    assertThat(database.supportsCatalogs()).isTrue();
    assertThat(database.supportsSchemas()).isFalse();
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseDatabaseTest`
Expected: FAIL — компиляция не проходит, `ClickHouseDatabase` не существует.

- [ ] **Step 3: Реализовать `ClickHouseDatabase`**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.database;

import liquibase.database.AbstractJdbcDatabase;
import liquibase.database.DatabaseConnection;
import liquibase.exception.DatabaseException;
import liquibase.structure.DatabaseObject;

/**
 * Liquibase-описание ClickHouse.
 *
 * <p>Класс сознательно объявляет отсутствие возможностей, которых в движке нет (транзакционный DDL,
 * последовательности, внешние ключи), вместо того чтобы позволять Liquibase генерировать SQL,
 * который ClickHouse не примет.
 */
public class ClickHouseDatabase extends AbstractJdbcDatabase {

  public static final String PRODUCT_NAME = "ClickHouse";
  public static final String SHORT_NAME = "clickhouse";

  private static final String JDBC_URL_PREFIX = "jdbc:clickhouse";
  private static final String DRIVER_CLASS_NAME = "com.clickhouse.jdbc.ClickHouseDriver";
  private static final int DEFAULT_HTTP_PORT = 8123;

  @Override
  protected String getDefaultDatabaseProductName() {
    return PRODUCT_NAME;
  }

  @Override
  public String getShortName() {
    return SHORT_NAME;
  }

  @Override
  public Integer getDefaultPort() {
    return DEFAULT_HTTP_PORT;
  }

  @Override
  public int getPriority() {
    return PRIORITY_DATABASE;
  }

  @Override
  public boolean isCorrectDatabaseImplementation(DatabaseConnection connection)
      throws DatabaseException {
    return PRODUCT_NAME.equalsIgnoreCase(connection.getDatabaseProductName());
  }

  @Override
  public String getDefaultDriver(String url) {
    return url != null && url.startsWith(JDBC_URL_PREFIX) ? DRIVER_CLASS_NAME : null;
  }

  @Override
  public boolean supportsDDLInTransaction() {
    return false;
  }

  @Override
  public boolean supportsInitiallyDeferrableColumns() {
    return false;
  }

  @Override
  public boolean supportsSequences() {
    return false;
  }

  @Override
  public boolean supportsTablespaces() {
    return false;
  }

  @Override
  public boolean supportsAutoIncrement() {
    return false;
  }

  @Override
  public boolean supportsDropTableCascadeConstraints() {
    return false;
  }

  @Override
  public boolean supportsRestrictForeignKeys() {
    return false;
  }

  @Override
  public boolean supportsForeignKeyDisable() {
    return false;
  }

  @Override
  public boolean supportsPrimaryKeyNames() {
    return false;
  }

  @Override
  public boolean supportsNotNullConstraintNames() {
    return false;
  }

  @Override
  public boolean supportsCatalogs() {
    return true;
  }

  @Override
  public boolean supportsSchemas() {
    return false;
  }

  @Override
  public boolean supportsCatalogInObjectName(Class<? extends DatabaseObject> type) {
    return true;
  }

  @Override
  public boolean isSystemObject(DatabaseObject example) {
    if (example == null) {
      return false;
    }
    liquibase.structure.core.Schema schema = example.getSchema();
    String catalog = schema == null ? null : schema.getCatalogName();
    return "system".equalsIgnoreCase(catalog)
        || "INFORMATION_SCHEMA".equalsIgnoreCase(catalog)
        || super.isSystemObject(example);
  }

  @Override
  public String getCurrentDateTimeFunction() {
    return "now()";
  }

  @Override
  public boolean getAutoCommitMode() {
    return true;
  }

  @Override
  public boolean isSafeToRunUpdate() {
    return true;
  }

  @Override
  protected String getConnectionSchemaName() {
    return null;
  }

  @Override
  protected String getQuotingStartCharacter() {
    return "`";
  }

  @Override
  protected String getQuotingEndCharacter() {
    return "`";
  }

  @Override
  protected String getQuotingEndReplacement() {
    return "\\`";
  }
}
```

- [ ] **Step 4: Зарегистрировать через SPI**

Создай `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.database.Database` с единственной строкой:

```
io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseDatabaseTest`
Expected: PASS, 7 тестов.

- [ ] **Step 6: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add ClickHouseDatabase with SPI registration

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: `ClickHouseConfiguration` — единый источник настроек

**Files:**
- Create: `liquibase-clickhouse/src/main/java/io/github/mnem0c0der/liquibase/ext/clickhouse/config/ClickHouseConfiguration.java`
- Create: `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.configuration.AutoloadedConfigurations`
- Test: `liquibase-clickhouse/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/config/ClickHouseConfigurationTest.java`

**Interfaces:**
- Consumes: ничего из предыдущих задач.
- Produces: публичные статические поля, используемые в Task 4, 6, 11, 15, 16:
  - `ClickHouseConfiguration.CLUSTER` → `ConfigurationDefinition<String>`, ключ `liquibase.clickhouse.cluster`, default `null`
  - `ClickHouseConfiguration.TABLE_ENGINE` → `ConfigurationDefinition<String>`, default `"MergeTree"`
  - `ClickHouseConfiguration.ZOOKEEPER_PATH` → `ConfigurationDefinition<String>`, default `"/clickhouse/tables/{shard}/{database}/{table}"`
  - `ClickHouseConfiguration.REPLICA_NAME` → `ConfigurationDefinition<String>`, default `"{replica}"`
  - `ClickHouseConfiguration.MUTATIONS_SYNC` → `ConfigurationDefinition<Integer>`, default `2`
  - `ClickHouseConfiguration.LOCK_TIMEOUT_SECONDS` → `ConfigurationDefinition<Integer>`, default `300`
  - `ClickHouseConfiguration.LOCK_POLL_INTERVAL_MILLIS` → `ConfigurationDefinition<Integer>`, default `500`
  - `ClickHouseConfiguration.LOCK_ENABLED` → `ConfigurationDefinition<Boolean>`, default `true`

> **Почему `ConfigurationDefinition`, а не чтение `System.getProperty`.** Одно объявление автоматически делает ключ доступным через `liquibase.properties`, `-D`, переменную окружения (`LIQUIBASE_CLICKHOUSE_CLUSTER`) и CLI. Ручное чтение системных свойств дало бы только один из четырёх путей и продублировало бы логику дефолтов — прямое нарушение DRY.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.ServiceLoader;
import liquibase.Scope;
import liquibase.configuration.AutoloadedConfigurations;
import org.junit.jupiter.api.Test;

class ClickHouseConfigurationTest {

  @Test
  void isRegisteredSoLiquibaseAutoloadsTheKeys() {
    assertThat(ServiceLoader.load(AutoloadedConfigurations.class))
        .anyMatch(ClickHouseConfiguration.class::isInstance);
  }

  @Test
  void exposesKeysUnderTheLiquibaseClickhouseNamespace() {
    assertThat(ClickHouseConfiguration.CLUSTER.getKey())
        .isEqualTo("liquibase.clickhouse.cluster");
    assertThat(ClickHouseConfiguration.LOCK_TIMEOUT_SECONDS.getKey())
        .isEqualTo("liquibase.clickhouse.lock.timeoutSeconds");
  }

  @Test
  void defaultsToStandaloneMergeTree() {
    assertThat(ClickHouseConfiguration.CLUSTER.getCurrentValue()).isNull();
    assertThat(ClickHouseConfiguration.TABLE_ENGINE.getCurrentValue()).isEqualTo("MergeTree");
    assertThat(ClickHouseConfiguration.MUTATIONS_SYNC.getCurrentValue()).isEqualTo(2);
    assertThat(ClickHouseConfiguration.LOCK_ENABLED.getCurrentValue()).isTrue();
    assertThat(ClickHouseConfiguration.LOCK_TIMEOUT_SECONDS.getCurrentValue()).isEqualTo(300);
    assertThat(ClickHouseConfiguration.LOCK_POLL_INTERVAL_MILLIS.getCurrentValue()).isEqualTo(500);
  }

  @Test
  void readsOverridesFromTheLiquibaseScope() throws Exception {
    String cluster =
        Scope.child(
            Map.of("liquibase.clickhouse.cluster", "analytics"),
            () -> ClickHouseConfiguration.CLUSTER.getCurrentValue());

    assertThat(cluster).isEqualTo("analytics");
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseConfigurationTest`
Expected: FAIL — `ClickHouseConfiguration` не существует.

- [ ] **Step 3: Реализовать**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.config;

import liquibase.configuration.AutoloadedConfigurations;
import liquibase.configuration.ConfigurationDefinition;

/**
 * Настройки расширения.
 *
 * <p>Каждый ключ объявлен ровно один раз. Liquibase сам делает его доступным через
 * liquibase.properties, системные свойства, переменные окружения и CLI.
 */
public final class ClickHouseConfiguration implements AutoloadedConfigurations {

  private static final String NAMESPACE = "liquibase.clickhouse";

  public static final ConfigurationDefinition<String> CLUSTER;
  public static final ConfigurationDefinition<String> TABLE_ENGINE;
  public static final ConfigurationDefinition<String> ZOOKEEPER_PATH;
  public static final ConfigurationDefinition<String> REPLICA_NAME;
  public static final ConfigurationDefinition<Integer> MUTATIONS_SYNC;
  public static final ConfigurationDefinition<Integer> LOCK_TIMEOUT_SECONDS;
  public static final ConfigurationDefinition<Integer> LOCK_POLL_INTERVAL_MILLIS;
  public static final ConfigurationDefinition<Boolean> LOCK_ENABLED;

  static {
    ConfigurationDefinition.Builder builder = new ConfigurationDefinition.Builder(NAMESPACE);

    CLUSTER =
        builder
            .define("cluster", String.class)
            .setDescription(
                "ClickHouse cluster name. When set, DDL is issued with ON CLUSTER and"
                    + " MergeTree engines are replaced by their Replicated counterparts."
                    + " Leave unset for standalone deployments.")
            .setCommonlyUsed(true)
            .build();

    TABLE_ENGINE =
        builder
            .define("tableEngine", String.class)
            .setDescription("Default table engine used when a changeset does not specify one.")
            .setDefaultValue("MergeTree")
            .build();

    ZOOKEEPER_PATH =
        builder
            .define("zookeeperPath", String.class)
            .setDescription("ZooKeeper/Keeper path template for Replicated* engines.")
            .setDefaultValue("/clickhouse/tables/{shard}/{database}/{table}")
            .build();

    REPLICA_NAME =
        builder
            .define("replicaName", String.class)
            .setDescription("Replica name template for Replicated* engines.")
            .setDefaultValue("{replica}")
            .build();

    MUTATIONS_SYNC =
        builder
            .define("mutationsSync", Integer.class)
            .setDescription(
                "Value of the ClickHouse mutations_sync setting applied to ALTER ... UPDATE"
                    + " and ALTER ... DELETE. 2 waits for all replicas.")
            .setDefaultValue(2)
            .build();

    LOCK_TIMEOUT_SECONDS =
        builder
            .define("lock.timeoutSeconds", Integer.class)
            .setDescription(
                "Age after which a held changelog lock is treated as stale and may be"
                    + " preempted. Protects against a crashed migration holding the lock forever.")
            .setDefaultValue(300)
            .build();

    LOCK_POLL_INTERVAL_MILLIS =
        builder
            .define("lock.pollIntervalMillis", Integer.class)
            .setDescription("Delay between lock acquisition attempts.")
            .setDefaultValue(500)
            .build();

    LOCK_ENABLED =
        builder
            .define("lock.enabled", Boolean.class)
            .setDescription(
                "Set to false to skip changelog locking entirely. Only safe when a single"
                    + " migration process can ever run at a time.")
            .setDefaultValue(true)
            .build();
  }
}
```

> **У класса НЕ должно быть приватного конструктора.** Реализации `AutoloadedConfigurations` создаёт
> `ServiceLoader`, а ему нужен публичный конструктор без аргументов. С приватным конструктором ключи просто
> не зарегистрируются. В самом Liquibase `GlobalConfiguration` устроен так же — конструктор не объявлен вовсе.

- [ ] **Step 4: Зарегистрировать через SPI**

Создай `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.configuration.AutoloadedConfigurations`:

```
io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseConfigurationTest`
Expected: PASS, 4 теста.

- [ ] **Step 6: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add ClickHouseConfiguration definitions

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: `ClusterPolicy` — вся кластерная специфика в одном месте

**Files:**
- Create: `liquibase-clickhouse/src/main/java/io/github/mnem0c0der/liquibase/ext/clickhouse/sql/Identifiers.java`
- Create: `liquibase-clickhouse/src/main/java/io/github/mnem0c0der/liquibase/ext/clickhouse/cluster/ClusterPolicy.java`
- Create: `liquibase-clickhouse/src/main/java/io/github/mnem0c0der/liquibase/ext/clickhouse/cluster/StandaloneClusterPolicy.java`
- Create: `liquibase-clickhouse/src/main/java/io/github/mnem0c0der/liquibase/ext/clickhouse/cluster/OnClusterPolicy.java`
- Create: `liquibase-clickhouse/src/main/java/io/github/mnem0c0der/liquibase/ext/clickhouse/cluster/ClusterPolicyFactory.java`
- Test: `liquibase-clickhouse/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/cluster/ClusterPolicyTest.java`

**Interfaces:**
- Consumes: `ClickHouseConfiguration.CLUSTER`, `.ZOOKEEPER_PATH`, `.REPLICA_NAME` (Task 3).
- Produces:
  - `Identifiers.quote(String identifier)` → `String` — оборачивает в обратные кавычки с экранированием
  - `Identifiers.literal(String value)` → `String` — SQL-строковый литерал в одинарных кавычках с экранированием
  - `interface ClusterPolicy` с методами `boolean isClustered()`, `String onClusterClause()`, `String resolveEngine(String requestedEngine)`
  - `ClusterPolicyFactory.fromConfiguration()` → `ClusterPolicy`
  - `new OnClusterPolicy(String clusterName, String zooKeeperPath, String replicaName)`
  - `StandaloneClusterPolicy.INSTANCE`

> **Почему это отдельная абстракция, а не `if (cluster != null)` в генераторах.** Правило «Replicated-движок собирается из имени семейства плюс два новых первых аргумента» нетривиально и обязано существовать в одном экземпляре. Разбросанное по двадцати генераторам, оно разъедется при первой же правке. Здесь же оно полностью юнит-тестируемо без БД.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import java.util.Map;
import liquibase.Scope;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClusterPolicyTest {

  private static final String ZK_PATH = "/clickhouse/tables/{shard}/{database}/{table}";
  private static final String REPLICA = "{replica}";

  @Nested
  class Standalone {

    private final ClusterPolicy policy = StandaloneClusterPolicy.INSTANCE;

    @Test
    void emitsNoOnClusterClause() {
      assertThat(policy.isClustered()).isFalse();
      assertThat(policy.onClusterClause()).isEmpty();
    }

    @Test
    void leavesEveryEngineUntouched() {
      assertThat(policy.resolveEngine("MergeTree")).isEqualTo("MergeTree");
      assertThat(policy.resolveEngine("ReplacingMergeTree(version)"))
          .isEqualTo("ReplacingMergeTree(version)");
      assertThat(policy.resolveEngine("Memory")).isEqualTo("Memory");
    }
  }

  @Nested
  class Clustered {

    private final ClusterPolicy policy = new OnClusterPolicy("analytics", ZK_PATH, REPLICA);

    @Test
    void emitsAQuotedOnClusterClause() {
      assertThat(policy.isClustered()).isTrue();
      assertThat(policy.onClusterClause()).isEqualTo(" ON CLUSTER `analytics`");
    }

    @Test
    void replicatesAnArgumentlessMergeTree() {
      assertThat(policy.resolveEngine("MergeTree"))
          .isEqualTo(
              "ReplicatedMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}')");
    }

    @Test
    void prependsReplicationArgumentsToAnExistingArgumentList() {
      assertThat(policy.resolveEngine("ReplacingMergeTree(version)"))
          .isEqualTo(
              "ReplicatedReplacingMergeTree("
                  + "'/clickhouse/tables/{shard}/{database}/{table}', '{replica}', version)");
    }

    @Test
    void leavesAnAlreadyReplicatedEngineAlone() {
      assertThat(policy.resolveEngine("ReplicatedMergeTree('/x', '{replica}')"))
          .isEqualTo("ReplicatedMergeTree('/x', '{replica}')");
    }

    @Test
    void leavesNonMergeTreeEnginesAlone() {
      assertThat(policy.resolveEngine("Memory")).isEqualTo("Memory");
      assertThat(policy.resolveEngine("Null")).isEqualTo("Null");
    }
  }

  @Nested
  class Factory {

    @Test
    void buildsStandalonePolicyWhenNoClusterIsConfigured() {
      assertThat(ClusterPolicyFactory.fromConfiguration().isClustered()).isFalse();
    }

    @Test
    void buildsClusteredPolicyWhenClusterIsConfigured() throws Exception {
      ClusterPolicy policy =
          Scope.child(
              Map.of(ClickHouseConfiguration.CLUSTER.getKey(), "analytics"),
              ClusterPolicyFactory::fromConfiguration);

      assertThat(policy.isClustered()).isTrue();
      assertThat(policy.onClusterClause()).isEqualTo(" ON CLUSTER `analytics`");
    }

    @Test
    void treatsABlankClusterNameAsStandalone() throws Exception {
      ClusterPolicy policy =
          Scope.child(
              Map.of(ClickHouseConfiguration.CLUSTER.getKey(), "   "),
              ClusterPolicyFactory::fromConfiguration);

      assertThat(policy.isClustered()).isFalse();
    }
  }

  @Nested
  class Quoting {

    @Test
    void quotesIdentifiersWithBackticks() {
      assertThat(Identifiers.quote("events")).isEqualTo("`events`");
    }

    @Test
    void escapesBackticksInsideIdentifiers() {
      assertThat(Identifiers.quote("we`ird")).isEqualTo("`we\\`ird`");
    }

    @Test
    void escapesQuotesInsideStringLiterals() {
      assertThat(Identifiers.literal("it's")).isEqualTo("'it\\'s'");
    }
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClusterPolicyTest`
Expected: FAIL — классы не существуют.

- [ ] **Step 3: Реализовать `Identifiers`**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sql;

/** Единственная точка квотирования идентификаторов и строковых литералов ClickHouse. */
public final class Identifiers {

  private Identifiers() {}

  public static String quote(String identifier) {
    return "`" + identifier.replace("\\", "\\\\").replace("`", "\\`") + "`";
  }

  public static String literal(String value) {
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }
}
```

- [ ] **Step 4: Реализовать `ClusterPolicy` и обе реализации**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.cluster;

/** Решает два кластерных вопроса: нужен ли ON CLUSTER и нужен ли Replicated-движок. */
public interface ClusterPolicy {

  boolean isClustered();

  /** Либо пустая строка, либо готовый к подстановке фрагмент вида {@code " ON CLUSTER `name`"}. */
  String onClusterClause();

  /** Возвращает движок, пригодный для текущей топологии. */
  String resolveEngine(String requestedEngine);
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.cluster;

/** Топология без репликации: DDL выполняется на одном узле, движки не переписываются. */
public final class StandaloneClusterPolicy implements ClusterPolicy {

  public static final ClusterPolicy INSTANCE = new StandaloneClusterPolicy();

  private StandaloneClusterPolicy() {}

  @Override
  public boolean isClustered() {
    return false;
  }

  @Override
  public String onClusterClause() {
    return "";
  }

  @Override
  public String resolveEngine(String requestedEngine) {
    return requestedEngine;
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.cluster;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import java.util.Objects;

/**
 * Кластерная топология: DDL выполняется через ON CLUSTER, а движки семейства MergeTree
 * заменяются на Replicated-аналоги.
 *
 * <p>Replicated-движок принимает путь в Keeper и имя реплики ПЕРВЫМИ аргументами, поэтому
 * существующие аргументы движка сдвигаются вправо, а не заменяются.
 */
public final class OnClusterPolicy implements ClusterPolicy {

  private static final String REPLICATED_PREFIX = "Replicated";
  private static final String MERGE_TREE_SUFFIX = "MergeTree";

  private final String clusterName;
  private final String zooKeeperPath;
  private final String replicaName;

  public OnClusterPolicy(String clusterName, String zooKeeperPath, String replicaName) {
    this.clusterName = Objects.requireNonNull(clusterName, "clusterName");
    this.zooKeeperPath = Objects.requireNonNull(zooKeeperPath, "zooKeeperPath");
    this.replicaName = Objects.requireNonNull(replicaName, "replicaName");
  }

  @Override
  public boolean isClustered() {
    return true;
  }

  @Override
  public String onClusterClause() {
    return " ON CLUSTER " + Identifiers.quote(clusterName);
  }

  @Override
  public String resolveEngine(String requestedEngine) {
    String engine = requestedEngine.trim();
    int parenthesis = engine.indexOf('(');

    if (parenthesis >= 0 && !engine.endsWith(")")) {
      throw new IllegalArgumentException(
          "Malformed ClickHouse table engine: "
              + requestedEngine
              + ". An engine with arguments must close its parenthesis, for example"
              + " ReplacingMergeTree(version).");
    }

    String family = (parenthesis < 0 ? engine : engine.substring(0, parenthesis)).trim();

    if (family.startsWith(REPLICATED_PREFIX) || !family.endsWith(MERGE_TREE_SUFFIX)) {
      return engine;
    }

    String replicationArguments =
        Identifiers.literal(zooKeeperPath) + ", " + Identifiers.literal(replicaName);

    if (parenthesis < 0) {
      return REPLICATED_PREFIX + family + "(" + replicationArguments + ")";
    }

    String existingArguments = engine.substring(parenthesis + 1, engine.length() - 1).trim();

    return REPLICATED_PREFIX
        + family
        + "("
        + replicationArguments
        + (existingArguments.isEmpty() ? "" : ", " + existingArguments)
        + ")";
  }
}
```

- [ ] **Step 5: Реализовать фабрику**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.cluster;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;

/** Выбирает топологию по текущей конфигурации Liquibase. */
public final class ClusterPolicyFactory {

  private ClusterPolicyFactory() {}

  public static ClusterPolicy fromConfiguration() {
    String clusterName = ClickHouseConfiguration.CLUSTER.getCurrentValue();

    if (clusterName == null || clusterName.isBlank()) {
      return StandaloneClusterPolicy.INSTANCE;
    }

    return new OnClusterPolicy(
        clusterName.trim(),
        ClickHouseConfiguration.ZOOKEEPER_PATH.getCurrentValue(),
        ClickHouseConfiguration.REPLICA_NAME.getCurrentValue());
  }
}
```

- [ ] **Step 6: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClusterPolicyTest`
Expected: PASS, 13 тестов.

- [ ] **Step 7: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add ClusterPolicy abstraction for ON CLUSTER and Replicated engines

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Маппинг типов данных

**Files:**
- Create: `liquibase-clickhouse/src/main/java/io/github/mnem0c0der/liquibase/ext/clickhouse/datatype/ClickHouseTypes.java`
- Create: `liquibase-clickhouse/src/main/java/io/github/mnem0c0der/liquibase/ext/clickhouse/datatype/` + по одному классу на тип (список в Step 3)
- Create: `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.datatype.LiquibaseDataType`
- Test: `liquibase-clickhouse/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/datatype/ClickHouseDataTypeTest.java`

**Interfaces:**
- Consumes: `ClickHouseDatabase` (Task 2).
- Produces:
  - `ClickHouseTypes.isClickHouse(Database database)` → `boolean`
  - `ClickHouseTypes.nullable(String type)` → `String` — оборачивает в `Nullable(...)`, повторно не оборачивает
  - Классы типов, разрешаемые через `DataTypeFactory.getInstance().fromDescription(description, database).toDatabaseDataType(database).toSql()`

> **Nullable сюда НЕ входит.** В ClickHouse колонка по умолчанию NOT NULL — обратно SQL-стандарту. Признак nullability живёт в `CreateTableStatement`, а не в типе, поэтому обёртку `Nullable(T)` накладывает генератор DDL (Task 7), пользуясь `ClickHouseTypes.nullable`. Класс типа обязан возвращать голый тип.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.datatype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClickHouseDataTypeTest {

  private final Database database = new ClickHouseDatabase();

  private String sqlFor(String liquibaseType) {
    return DataTypeFactory.getInstance()
        .fromDescription(liquibaseType, database)
        .toDatabaseDataType(database)
        .toSql();
  }

  @ParameterizedTest
  @CsvSource({
    "varchar(255), String",
    "varchar,      String",
    "char(3),      String",
    "clob,         String",
    "blob,         String",
    "tinyint,      Int8",
    "smallint,     Int16",
    "int,          Int32",
    "bigint,       Int64",
    "boolean,      Bool",
    "float,        Float32",
    "double,       Float64",
    "date,         Date32",
    "uuid,         UUID"
  })
  void mapsLiquibaseTypesToClickHouseTypes(String liquibaseType, String expected) {
    assertThat(sqlFor(liquibaseType)).isEqualTo(expected);
  }

  @Test
  void mapsDecimalPreservingPrecisionAndScale() {
    assertThat(sqlFor("decimal(18,4)")).isEqualTo("Decimal(18, 4)");
  }

  @Test
  void mapsDecimalWithoutParametersToASafeDefault() {
    assertThat(sqlFor("decimal")).isEqualTo("Decimal(38, 9)");
  }

  @Test
  void mapsTimestampToMillisecondPrecision() {
    assertThat(sqlFor("timestamp")).isEqualTo("DateTime64(3)");
    assertThat(sqlFor("datetime")).isEqualTo("DateTime64(3)");
  }

  @Test
  void honoursAnExplicitTimestampPrecision() {
    assertThat(sqlFor("timestamp(6)")).isEqualTo("DateTime64(6)");
  }

  @Test
  void wrapsNullableTypesExactlyOnce() {
    assertThat(ClickHouseTypes.nullable("String")).isEqualTo("Nullable(String)");
    assertThat(ClickHouseTypes.nullable("Nullable(String)")).isEqualTo("Nullable(String)");
  }

  @Test
  void neverWrapsArrayTypes() {
    assertThat(ClickHouseTypes.nullable("Array(String)")).isEqualTo("Array(String)");
  }

  @Test
  void movesTheNullableWrapperInsideLowCardinality() {
    assertThat(ClickHouseTypes.nullable("LowCardinality(String)"))
        .isEqualTo("LowCardinality(Nullable(String))");
    assertThat(ClickHouseTypes.nullable("LowCardinality(Nullable(String))"))
        .isEqualTo("LowCardinality(Nullable(String))");
  }

  @Test
  void rejectsAnEmptyColumnType() {
    assertThatThrownBy(() -> ClickHouseTypes.nullable("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty column type");
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseDataTypeTest`
Expected: FAIL — `ClickHouseTypes` не существует.

- [ ] **Step 3: Реализовать**

`ClickHouseTypes.java`:

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.datatype;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import liquibase.database.Database;

/** Помощники, общие для всех классов типов и для генераторов DDL. */
public final class ClickHouseTypes {

  private ClickHouseTypes() {}

  public static boolean isClickHouse(Database database) {
    return database instanceof ClickHouseDatabase;
  }

  private static final String LOW_CARDINALITY = "LowCardinality(";

  /**
   * Оборачивает тип в {@code Nullable(...)}.
   *
   * <p>ClickHouse запрещает Nullable поверх Array и поверх уже нулевого типа, поэтому такие
   * случаи возвращаются без изменений. Для LowCardinality единственная допустимая вложенность —
   * {@code LowCardinality(Nullable(T))}, а не наоборот, поэтому обёртка уходит внутрь.
   */
  public static String nullable(String type) {
    String trimmed = type.trim();

    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("Cannot make an empty column type nullable");
    }
    if (trimmed.startsWith("Nullable(") || trimmed.startsWith("Array(")) {
      return trimmed;
    }
    if (trimmed.startsWith(LOW_CARDINALITY) && trimmed.endsWith(")")) {
      String inner = trimmed.substring(LOW_CARDINALITY.length(), trimmed.length() - 1);
      return LOW_CARDINALITY + nullable(inner) + ")";
    }
    return "Nullable(" + trimmed + ")";
  }
}
```

Далее — по одному классу на тип. Все они устроены одинаково: аннотация с приоритетом уровня БД, `supports` через `ClickHouseTypes.isClickHouse` и возврат `DatabaseDataType`. Создай их в пакете `io.github.mnem0c0der.liquibase.ext.clickhouse.datatype`, каждый со стандартным лицензионным заголовком и импортами.

Простые типы без параметров — шаблон на примере `ClickHouseBigIntType`:

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.datatype;

import liquibase.database.Database;
import liquibase.datatype.DataTypeInfo;
import liquibase.datatype.DatabaseDataType;
import liquibase.datatype.core.BigIntType;
import liquibase.servicelocator.PrioritizedService;

@DataTypeInfo(
    name = "bigint",
    aliases = {"java.sql.Types.BIGINT", "java.math.BigInteger", "java.lang.Long", "int8"},
    minParameters = 0,
    maxParameters = 0,
    priority = PrioritizedService.PRIORITY_DATABASE)
public class ClickHouseBigIntType extends BigIntType {

  @Override
  public boolean supports(Database database) {
    return ClickHouseTypes.isClickHouse(database);
  }

  @Override
  public DatabaseDataType toDatabaseDataType(Database database) {
    return new DatabaseDataType("Int64");
  }
}
```

По этому шаблону создай остальные простые типы, меняя только имя класса, базовый класс, содержимое `@DataTypeInfo` и возвращаемую строку:

| Класс | Базовый класс | `name` в `@DataTypeInfo` | `aliases` | ClickHouse-тип |
|---|---|---|---|---|
| `ClickHouseVarcharType` | `VarcharType` | `varchar` | `java.sql.Types.VARCHAR`, `java.lang.String`, `varchar2`, `character varying`, `nvarchar`, `text` | `String` |
| `ClickHouseCharType` | `CharType` | `char` | `java.sql.Types.CHAR`, `bpchar`, `character` | `String` |
| `ClickHouseClobType` | `ClobType` | `clob` | `java.sql.Types.CLOB`, `longtext`, `longvarchar` | `String` |
| `ClickHouseBlobType` | `BlobType` | `blob` | `java.sql.Types.BLOB`, `java.sql.Types.VARBINARY`, `bytea`, `binary`, `varbinary` | `String` |
| `ClickHouseTinyIntType` | `TinyIntType` | `tinyint` | `java.sql.Types.TINYINT`, `int1` | `Int8` |
| `ClickHouseSmallIntType` | `SmallIntType` | `smallint` | `java.sql.Types.SMALLINT`, `int2` | `Int16` |
| `ClickHouseIntType` | `IntType` | `int` | `java.sql.Types.INTEGER`, `java.lang.Integer`, `integer`, `int4` | `Int32` |
| `ClickHouseBigIntType` | `BigIntType` | `bigint` | `java.sql.Types.BIGINT`, `java.math.BigInteger`, `java.lang.Long`, `int8` | `Int64` |
| `ClickHouseBooleanType` | `BooleanType` | `boolean` | `java.sql.Types.BOOLEAN`, `java.lang.Boolean`, `bit`, `bool` | `Bool` |
| `ClickHouseFloatType` | `FloatType` | `float` | `java.sql.Types.FLOAT`, `java.lang.Float`, `real` | `Float32` |
| `ClickHouseDoubleType` | `DoubleType` | `double` | `java.sql.Types.DOUBLE`, `java.lang.Double`, `double precision` | `Float64` |
| `ClickHouseDateType` | `DateType` | `date` | `java.sql.Types.DATE`, `java.sql.Date` | `Date32` |
| `ClickHouseUuidType` | `UUIDType` | `uuid` | `java.util.UUID`, `uniqueidentifier` | `UUID` |

Два типа параметризованы и требуют собственной логики.

`ClickHouseDecimalType`:

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.datatype;

import liquibase.database.Database;
import liquibase.datatype.DataTypeInfo;
import liquibase.datatype.DatabaseDataType;
import liquibase.datatype.core.DecimalType;
import liquibase.servicelocator.PrioritizedService;

@DataTypeInfo(
    name = "decimal",
    aliases = {"java.sql.Types.DECIMAL", "java.math.BigDecimal", "numeric", "number"},
    minParameters = 0,
    maxParameters = 2,
    priority = PrioritizedService.PRIORITY_DATABASE)
public class ClickHouseDecimalType extends DecimalType {

  private static final String DEFAULT_PRECISION = "38";
  private static final String DEFAULT_SCALE = "9";

  @Override
  public boolean supports(Database database) {
    return ClickHouseTypes.isClickHouse(database);
  }

  @Override
  public DatabaseDataType toDatabaseDataType(Database database) {
    Object[] parameters = getParameters();
    String precision = parameters.length > 0 ? String.valueOf(parameters[0]) : DEFAULT_PRECISION;
    String scale = parameters.length > 1 ? String.valueOf(parameters[1]) : DEFAULT_SCALE;
    return new DatabaseDataType("Decimal(" + precision + ", " + scale + ")");
  }
}
```

`ClickHouseDateTimeType`:

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.datatype;

import liquibase.database.Database;
import liquibase.datatype.DataTypeInfo;
import liquibase.datatype.DatabaseDataType;
import liquibase.datatype.core.DateTimeType;
import liquibase.servicelocator.PrioritizedService;

@DataTypeInfo(
    name = "datetime",
    aliases = {
      "java.sql.Types.DATETIME",
      "java.sql.Types.TIMESTAMP",
      "java.sql.Timestamp",
      "timestamp",
      "timestamptz",
      "timestamp with time zone",
      "timestamp without time zone"
    },
    minParameters = 0,
    maxParameters = 1,
    priority = PrioritizedService.PRIORITY_DATABASE)
public class ClickHouseDateTimeType extends DateTimeType {

  private static final String DEFAULT_FRACTIONAL_DIGITS = "3";

  @Override
  public boolean supports(Database database) {
    return ClickHouseTypes.isClickHouse(database);
  }

  @Override
  public DatabaseDataType toDatabaseDataType(Database database) {
    Object[] parameters = getParameters();
    String precision =
        parameters.length > 0 ? String.valueOf(parameters[0]) : DEFAULT_FRACTIONAL_DIGITS;
    return new DatabaseDataType("DateTime64(" + precision + ")");
  }
}
```

- [ ] **Step 4: Зарегистрировать через SPI**

Создай `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.datatype.LiquibaseDataType`, по одной полностью квалифицированной строке на каждый из 15 созданных классов, например:

```
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseVarcharType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseCharType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseClobType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseBlobType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseTinyIntType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseSmallIntType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseIntType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseBigIntType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseBooleanType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseFloatType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseDoubleType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseDateType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseUuidType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseDecimalType
io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseDateTimeType
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseDataTypeTest`
Expected: PASS, 20 тестов (14 параметризованных случаев + 6 отдельных).

Если какой-то тип разрешается в стандартный SQL-тип вместо ClickHouse-типа, причина всегда одна из двух: класс не перечислен в файле SPI, либо `priority` в `@DataTypeInfo` не равен `PRIORITY_DATABASE`.

- [ ] **Step 6: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: map Liquibase data types to ClickHouse types

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: База генераторов SQL и `ClickHouseDdlBuilder`

**Files:**
- Create: `.../exception/UnsupportedClickHouseFeatureException.java`
- Create: `.../sql/ClickHouseDdlBuilder.java`
- Create: `.../sqlgenerator/AbstractClickHouseSqlGenerator.java`
- Test: `.../sql/ClickHouseDdlBuilderTest.java`

(Здесь и далее `...` = `liquibase-clickhouse/src/{main,test}/java/io/github/mnem0c0der/liquibase/ext/clickhouse`.)

**Interfaces:**
- Consumes: `ClusterPolicy`, `Identifiers` (Task 4); `ClickHouseDatabase` (Task 2).
- Produces:
  - `UnsupportedClickHouseFeatureException extends liquibase.exception.UnexpectedLiquibaseException`, конструктор `(String feature, String alternative)`
  - `ClickHouseDdlBuilder.createTable(String qualifiedTableName)` → билдер; методы `onCluster(ClusterPolicy)`, `column(String quotedName, String type)`, `columnWithDefault(String quotedName, String type, String defaultExpression)`, `engine(String)`, `orderBy(List<String>)`, `primaryKey(List<String>)`, `partitionBy(String)`, `ttl(String)`, `settings(String)`, `comment(String)`, `build()` → `String`
  - `AbstractClickHouseSqlGenerator<T extends SqlStatement>` с защищёнными методами `clusterPolicy()` → `ClusterPolicy`, `sql(String...)` → `Sql[]`, `qualifiedTableName(Database, String catalog, String table)` → `String`

Каждый вариант отказа описан ровно один раз, в виде статической фабрики. Текст одной и той же
альтернативы требуется в нескольких генераторах (Task 8, 9, 11), и расползшиеся копии разошлись бы при первой же
правке формулировки.

> **Почему `ORDER BY tuple()`, а не пропуск секции.** Движки семейства MergeTree требуют `ORDER BY`. Если changeset не задал ни PK, ни явного порядка, единственный корректный вариант — `ORDER BY tuple()`. Пропустить секцию нельзя: ClickHouse откажет в создании таблицы.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.OnClusterPolicy;
import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.StandaloneClusterPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClickHouseDdlBuilderTest {

  @Test
  void buildsAMinimalStandaloneCreateTable() {
    String sql =
        ClickHouseDdlBuilder.createTable("`analytics`.`events`")
            .onCluster(StandaloneClusterPolicy.INSTANCE)
            .column("`id`", "Int64")
            .column("`name`", "Nullable(String)")
            .engine("MergeTree")
            .orderBy(List.of("`id`"))
            .build();

    assertThat(sql)
        .isEqualTo(
            "CREATE TABLE `analytics`.`events` "
                + "(`id` Int64, `name` Nullable(String)) "
                + "ENGINE = MergeTree ORDER BY (`id`)");
  }

  @Test
  void fallsBackToAnEmptyTupleWhenNoOrderIsGiven() {
    String sql =
        ClickHouseDdlBuilder.createTable("`t`")
            .onCluster(StandaloneClusterPolicy.INSTANCE)
            .column("`id`", "Int64")
            .engine("MergeTree")
            .build();

    assertThat(sql).endsWith("ENGINE = MergeTree ORDER BY tuple()");
  }

  @Test
  void refusesToBuildATableWithNoColumns() {
    ClickHouseDdlBuilder builder =
        ClickHouseDdlBuilder.createTable("`t`")
            .onCluster(StandaloneClusterPolicy.INSTANCE)
            .engine("MergeTree");

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no columns");
  }

  @Test
  void addsTheOnClusterClauseImmediatelyAfterTheTableName() {
    String sql =
        ClickHouseDdlBuilder.createTable("`t`")
            .onCluster(new OnClusterPolicy("c", "/p", "{replica}"))
            .column("`id`", "Int64")
            .engine("MergeTree")
            .build();

    assertThat(sql).startsWith("CREATE TABLE `t` ON CLUSTER `c` (`id` Int64)");
  }

  @Test
  void resolvesTheEngineThroughTheClusterPolicy() {
    String sql =
        ClickHouseDdlBuilder.createTable("`t`")
            .onCluster(new OnClusterPolicy("c", "/p", "{replica}"))
            .column("`id`", "Int64")
            .engine("MergeTree")
            .build();

    assertThat(sql).contains("ENGINE = ReplicatedMergeTree('/p', '{replica}')");
  }

  @Test
  void rendersEveryOptionalClauseInClickHouseOrder() {
    String sql =
        ClickHouseDdlBuilder.createTable("`t`")
            .onCluster(StandaloneClusterPolicy.INSTANCE)
            .column("`id`", "Int64")
            .columnWithDefault("`created`", "DateTime64(3)", "now64(3)")
            .engine("MergeTree")
            .primaryKey(List.of("`id`"))
            .orderBy(List.of("`id`", "`created`"))
            .partitionBy("toYYYYMM(`created`)")
            .ttl("`created` + INTERVAL 30 DAY")
            .settings("index_granularity = 8192")
            .comment("event stream")
            .build();

    assertThat(sql)
        .isEqualTo(
            "CREATE TABLE `t` "
                + "(`id` Int64, `created` DateTime64(3) DEFAULT now64(3)) "
                + "ENGINE = MergeTree "
                + "PRIMARY KEY (`id`) "
                + "ORDER BY (`id`, `created`) "
                + "PARTITION BY toYYYYMM(`created`) "
                + "TTL `created` + INTERVAL 30 DAY "
                + "SETTINGS index_granularity = 8192 "
                + "COMMENT 'event stream'");
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseDdlBuilderTest`
Expected: FAIL — `ClickHouseDdlBuilder` не существует.

- [ ] **Step 3: Реализовать исключение**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.exception;

import liquibase.exception.UnexpectedLiquibaseException;

/**
 * Бросается, когда changeset требует того, чего в ClickHouse нет.
 *
 * <p>Расширение сознательно падает вместо генерации SQL, который сервер отвергнет или, хуже,
 * примет с другим смыслом. В сообщении всегда указывается работающая альтернатива.
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
```

- [ ] **Step 4: Реализовать `ClickHouseDdlBuilder`**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sql;

import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterPolicy;
import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.StandaloneClusterPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Собирает CREATE TABLE для ClickHouse.
 *
 * <p>Порядок секций задан грамматикой ClickHouse и не является свободным: ENGINE, PRIMARY KEY,
 * ORDER BY, PARTITION BY, TTL, SETTINGS, COMMENT.
 */
public final class ClickHouseDdlBuilder {

  private final String qualifiedTableName;
  private final List<String> columns = new ArrayList<>();
  private ClusterPolicy clusterPolicy = StandaloneClusterPolicy.INSTANCE;
  private String engine = "MergeTree";
  private List<String> primaryKey = List.of();
  private List<String> orderBy = List.of();
  private String partitionBy;
  private String ttl;
  private String settings;
  private String comment;

  private ClickHouseDdlBuilder(String qualifiedTableName) {
    this.qualifiedTableName = qualifiedTableName;
  }

  public static ClickHouseDdlBuilder createTable(String qualifiedTableName) {
    return new ClickHouseDdlBuilder(qualifiedTableName);
  }

  public ClickHouseDdlBuilder onCluster(ClusterPolicy clusterPolicy) {
    this.clusterPolicy = clusterPolicy;
    return this;
  }

  public ClickHouseDdlBuilder column(String quotedName, String type) {
    columns.add(quotedName + " " + type);
    return this;
  }

  public ClickHouseDdlBuilder columnWithDefault(
      String quotedName, String type, String defaultExpression) {
    columns.add(quotedName + " " + type + " DEFAULT " + defaultExpression);
    return this;
  }

  public ClickHouseDdlBuilder engine(String engine) {
    this.engine = engine;
    return this;
  }

  public ClickHouseDdlBuilder primaryKey(List<String> quotedColumns) {
    this.primaryKey = List.copyOf(quotedColumns);
    return this;
  }

  public ClickHouseDdlBuilder orderBy(List<String> quotedColumns) {
    this.orderBy = List.copyOf(quotedColumns);
    return this;
  }

  public ClickHouseDdlBuilder partitionBy(String expression) {
    this.partitionBy = expression;
    return this;
  }

  public ClickHouseDdlBuilder ttl(String expression) {
    this.ttl = expression;
    return this;
  }

  public ClickHouseDdlBuilder settings(String settings) {
    this.settings = settings;
    return this;
  }

  public ClickHouseDdlBuilder comment(String comment) {
    this.comment = comment;
    return this;
  }

  public String build() {
    if (columns.isEmpty()) {
      throw new IllegalStateException(
          "Cannot create ClickHouse table " + qualifiedTableName + " with no columns");
    }

    StringBuilder sql = new StringBuilder("CREATE TABLE ").append(qualifiedTableName);
    sql.append(clusterPolicy.onClusterClause());
    sql.append(" (").append(String.join(", ", columns)).append(")");
    sql.append(" ENGINE = ").append(clusterPolicy.resolveEngine(engine));

    if (!primaryKey.isEmpty()) {
      sql.append(" PRIMARY KEY ").append(tuple(primaryKey));
    }

    sql.append(" ORDER BY ").append(orderBy.isEmpty() ? "tuple()" : tuple(orderBy));

    if (partitionBy != null) {
      sql.append(" PARTITION BY ").append(partitionBy);
    }
    if (ttl != null) {
      sql.append(" TTL ").append(ttl);
    }
    if (settings != null) {
      sql.append(" SETTINGS ").append(settings);
    }
    if (comment != null) {
      sql.append(" COMMENT ").append(Identifiers.literal(comment));
    }

    return sql.toString();
  }

  private static String tuple(List<String> quotedColumns) {
    StringJoiner joiner = new StringJoiner(", ", "(", ")");
    quotedColumns.forEach(joiner::add);
    return joiner.toString();
  }
}
```

- [ ] **Step 5: Реализовать базу генераторов**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator;

import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterPolicy;
import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterPolicyFactory;
import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import java.util.Arrays;
import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.AbstractSqlGenerator;
import liquibase.statement.SqlStatement;

/**
 * Общая база всех ClickHouse-генераторов.
 *
 * <p>Берёт на себя приоритет, отбор по типу БД и доступ к текущей топологии, чтобы конкретные
 * генераторы занимались исключительно своим оператором.
 */
public abstract class AbstractClickHouseSqlGenerator<T extends SqlStatement>
    extends AbstractSqlGenerator<T> {

  @Override
  public int getPriority() {
    return PRIORITY_DATABASE;
  }

  @Override
  public boolean supports(T statement, Database database) {
    return database instanceof ClickHouseDatabase;
  }

  @Override
  public ValidationErrors validate(T statement, Database database, SqlGeneratorChain<T> chain) {
    return new ValidationErrors();
  }

  protected ClusterPolicy clusterPolicy() {
    return ClusterPolicyFactory.fromConfiguration();
  }

  protected Sql[] sql(String... statements) {
    return Arrays.stream(statements).map(UnparsedSql::new).toArray(Sql[]::new);
  }

  /**
   * Возвращает имя таблицы, уточнённое базой ClickHouse. Схемы в ClickHouse нет, поэтому в имени
   * не более двух частей.
   */
  protected String qualifiedTableName(Database database, String catalogName, String tableName) {
    String catalog =
        catalogName == null || catalogName.isBlank()
            ? database.getDefaultCatalogName()
            : catalogName;

    return catalog == null || catalog.isBlank()
        ? Identifiers.quote(tableName)
        : Identifiers.quote(catalog) + "." + Identifiers.quote(tableName);
  }
}
```

- [ ] **Step 6: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseDdlBuilderTest`
Expected: PASS, 5 тестов.

- [ ] **Step 7: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add DDL builder and ClickHouse SQL generator base

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Генераторы `CREATE TABLE` и `DROP TABLE`

**Files:**
- Create: `.../sqlgenerator/table/CreateTableGeneratorClickHouse.java`
- Create: `.../sqlgenerator/table/DropTableGeneratorClickHouse.java`
- Create: `.../sqlgenerator/table/TableColumns.java`
- Create: `.../sql/SqlValues.java`
- Create: `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.sqlgenerator.SqlGenerator`
- Test: `.../sqlgenerator/table/CreateTableGeneratorClickHouseTest.java`

**Interfaces:**
- Consumes: `AbstractClickHouseSqlGenerator`, `ClickHouseDdlBuilder` (Task 6); `ClickHouseTypes.nullable` (Task 5).
- Produces:
  - `TableColumns.renderType(CreateTableStatement statement, String columnName, Database database)` → `String` — тип колонки с уже наложенной обёрткой `Nullable(...)`, используется также в Task 8
  - `SqlValues.render(Object value, Database database)` → `String` — значение в виде SQL-литерала; используется также в Task 10

> **Правило nullability.** `CreateTableStatement.getNotNullColumns()` содержит колонки, объявленные NOT NULL. Все остальные в ClickHouse обязаны стать `Nullable(T)` — иначе Liquibase-changeset, валидный на PostgreSQL, потеряет возможность хранить NULL и молча начнёт писать нули и пустые строки.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.core.CreateTableStatement;
import liquibase.statement.core.DropTableStatement;
import org.junit.jupiter.api.Test;

class CreateTableGeneratorClickHouseTest {

  private final Database database = new ClickHouseDatabase();

  private String generate(liquibase.statement.SqlStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return String.join("; ", Arrays.stream(sql).map(Sql::toSql).toList());
  }

  private CreateTableStatement events() {
    CreateTableStatement statement = new CreateTableStatement("analytics", null, "events");
    statement.addColumn(
        "id",
        liquibase.datatype.DataTypeFactory.getInstance().fromDescription("bigint", database),
        null,
        new liquibase.statement.ColumnConstraint[] {
          new liquibase.statement.NotNullConstraint("id")
        });
    statement.addColumn(
        "name",
        liquibase.datatype.DataTypeFactory.getInstance().fromDescription("varchar(50)", database));
    return statement;
  }

  @Test
  void createsAMergeTreeTableOrderedByTuple() {
    assertThat(generate(events()))
        .isEqualTo(
            "CREATE TABLE `analytics`.`events` "
                + "(`id` Int64, `name` Nullable(String)) "
                + "ENGINE = MergeTree ORDER BY tuple()");
  }

  @Test
  void usesThePrimaryKeyAsTheSortingKey() {
    CreateTableStatement statement = events();
    statement.addPrimaryKeyColumn(
        "id",
        liquibase.datatype.DataTypeFactory.getInstance().fromDescription("bigint", database),
        null,
        "pk_events",
        null);

    assertThat(generate(statement))
        .contains("PRIMARY KEY (`id`)")
        .contains("ORDER BY (`id`)");
  }

  @Test
  void dropsATableUnconditionally() {
    assertThat(generate(new DropTableStatement("analytics", null, "events", false)))
        .isEqualTo("DROP TABLE IF EXISTS `analytics`.`events`");
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=CreateTableGeneratorClickHouseTest`
Expected: FAIL — генераторы ещё не зарегистрированы, Liquibase отдаёт SQL стандартного генератора.

- [ ] **Step 3: Реализовать `TableColumns`**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table;

import io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseTypes;
import liquibase.database.Database;
import liquibase.statement.core.CreateTableStatement;

/** Приведение колонок Liquibase к колонкам ClickHouse. */
final class TableColumns {

  private TableColumns() {}

  /**
   * В ClickHouse колонка по умолчанию NOT NULL — обратно SQL-стандарту. Поэтому всё, что не
   * объявлено NOT NULL явно, оборачивается в Nullable.
   */
  static String renderType(CreateTableStatement statement, String columnName, Database database) {
    String type =
        statement.getColumnTypes().get(columnName).toDatabaseDataType(database).toSql();

    return statement.getNotNullColumns().containsKey(columnName)
        ? type
        : ClickHouseTypes.nullable(type);
  }
}
```

- [ ] **Step 4: Реализовать генераторы**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.ClickHouseDdlBuilder;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.SqlValues;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.List;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.CreateTableStatement;

public class CreateTableGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<CreateTableStatement> {

  @Override
  public Sql[] generateSql(
      CreateTableStatement statement, Database database, SqlGeneratorChain<CreateTableStatement> chain) {

    ClickHouseDdlBuilder builder =
        ClickHouseDdlBuilder.createTable(
                qualifiedTableName(database, statement.getCatalogName(), statement.getTableName()))
            .onCluster(clusterPolicy())
            .engine(ClickHouseConfiguration.TABLE_ENGINE.getCurrentValue());

    for (String columnName : statement.getColumns()) {
      String type = TableColumns.renderType(statement, columnName, database);
      Object defaultValue = statement.getDefaultValue(columnName);

      if (defaultValue == null) {
        builder.column(Identifiers.quote(columnName), type);
      } else {
        builder.columnWithDefault(
            Identifiers.quote(columnName),
            type,
            SqlValues.render(defaultValue, database));
      }
    }

    List<String> keyColumns = sortingKey(statement);
    if (!keyColumns.isEmpty()) {
      builder.primaryKey(keyColumns).orderBy(keyColumns);
    }

    if (statement.getRemarks() != null) {
      builder.comment(statement.getRemarks());
    }

    return sql(builder.build());
  }

  private static List<String> sortingKey(CreateTableStatement statement) {
    if (statement.getPrimaryKeyConstraint() == null
        || statement.getPrimaryKeyConstraint().getColumns().isEmpty()) {
      return List.of();
    }
    return statement.getPrimaryKeyConstraint().getColumns().stream()
        .map(Identifiers::quote)
        .toList();
  }
}
```

`SqlValues.java` — единая точка превращения Java-значения в SQL-литерал. Обрати внимание: метода
`Database.objectToSql` в Liquibase НЕТ — преобразование живёт на `LiquibaseDataType`:

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sql;

import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;

/** Единая точка рендеринга значений в SQL-литералы. */
public final class SqlValues {

  private SqlValues() {}

  public static String render(Object value, Database database) {
    if (value == null) {
      return "NULL";
    }
    return DataTypeFactory.getInstance().fromObject(value, database).objectToSql(value, database);
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.DropTableStatement;

public class DropTableGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<DropTableStatement> {

  @Override
  public Sql[] generateSql(
      DropTableStatement statement, Database database, SqlGeneratorChain<DropTableStatement> chain) {

    return sql(
        "DROP TABLE IF EXISTS "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause());
  }
}
```

- [ ] **Step 5: Зарегистрировать генераторы**

Создай `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.sqlgenerator.SqlGenerator`:

```
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table.CreateTableGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table.DropTableGeneratorClickHouse
```

Каждая последующая задача, добавляющая генератор, ДОПИСЫВАЕТ строку в этот же файл.

- [ ] **Step 6: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=CreateTableGeneratorClickHouseTest`
Expected: PASS, 3 теста.

- [ ] **Step 7: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add CREATE TABLE and DROP TABLE generators

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: Генераторы операций с колонками

**Files:**
- Create: `.../sqlgenerator/column/AddColumnGeneratorClickHouse.java`
- Create: `.../sqlgenerator/column/DropColumnGeneratorClickHouse.java`
- Create: `.../sqlgenerator/column/RenameColumnGeneratorClickHouse.java`
- Create: `.../sqlgenerator/column/ModifyDataTypeGeneratorClickHouse.java`
- Modify: `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.sqlgenerator.SqlGenerator`
- Test: `.../sqlgenerator/column/ColumnGeneratorsTest.java`

**Interfaces:**
- Consumes: `AbstractClickHouseSqlGenerator` (Task 6), `ClickHouseTypes.nullable` (Task 5).
- Produces: генераторы для `AddColumnStatement`, `DropColumnStatement`, `RenameColumnStatement`, `ModifyDataTypeStatement`.

> **`AddColumnStatement` бывает составным.** Если `getColumns()` непустой, оператор описывает несколько колонок сразу, и каждую надо развернуть в собственное `ADD COLUMN`. ClickHouse не принимает несколько `ADD COLUMN` в одном `ALTER`, поэтому генерируется несколько операторов.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import java.util.List;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.AddColumnStatement;
import liquibase.statement.core.DropColumnStatement;
import liquibase.statement.core.ModifyDataTypeStatement;
import liquibase.statement.core.RenameColumnStatement;
import org.junit.jupiter.api.Test;

class ColumnGeneratorsTest {

  private final Database database = new ClickHouseDatabase();

  private List<String> generate(SqlStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return Arrays.stream(sql).map(Sql::toSql).toList();
  }

  @Test
  void addsANullableColumn() {
    AddColumnStatement statement =
        new AddColumnStatement("analytics", null, "events", "country", "varchar(2)", null);

    assertThat(generate(statement))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` ADD COLUMN `country` Nullable(String)");
  }

  @Test
  void addsANotNullColumnWithoutTheNullableWrapper() {
    AddColumnStatement statement =
        new AddColumnStatement(
            "analytics",
            null,
            "events",
            "country",
            "varchar(2)",
            null,
            new liquibase.statement.NotNullConstraint("country"));

    assertThat(generate(statement))
        .containsExactly("ALTER TABLE `analytics`.`events` ADD COLUMN `country` String");
  }

  @Test
  void expandsACompositeAddColumnIntoSeparateStatements() {
    AddColumnStatement first =
        new AddColumnStatement("analytics", null, "events", "a", "int", null);
    AddColumnStatement second =
        new AddColumnStatement("analytics", null, "events", "b", "int", null);

    assertThat(generate(new AddColumnStatement(first, second)))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` ADD COLUMN `a` Nullable(Int32)",
            "ALTER TABLE `analytics`.`events` ADD COLUMN `b` Nullable(Int32)");
  }

  @Test
  void dropsAColumn() {
    assertThat(generate(new DropColumnStatement("analytics", null, "events", "country")))
        .containsExactly("ALTER TABLE `analytics`.`events` DROP COLUMN `country`");
  }

  @Test
  void renamesAColumn() {
    assertThat(
            generate(
                new RenameColumnStatement(
                    "analytics", null, "events", "country", "country_code", null)))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` RENAME COLUMN `country` TO `country_code`");
  }

  @Test
  void modifiesAColumnType() {
    assertThat(
            generate(
                new ModifyDataTypeStatement("analytics", null, "events", "country", "varchar(8)")))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` MODIFY COLUMN `country` Nullable(String)");
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ColumnGeneratorsTest`
Expected: FAIL — используются генераторы по умолчанию.

- [ ] **Step 3: Реализовать генераторы**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column;

import io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseTypes;
import io.github.mnem0c0der.liquibase.ext.clickhouse.exception.UnsupportedClickHouseFeatureException;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.ArrayList;
import java.util.List;
import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.AddColumnStatement;

public class AddColumnGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<AddColumnStatement> {

  @Override
  public Sql[] generateSql(
      AddColumnStatement statement, Database database, SqlGeneratorChain<AddColumnStatement> chain) {

    List<AddColumnStatement> columns =
        statement.getColumns().isEmpty() ? List.of(statement) : statement.getColumns();

    List<String> statements = new ArrayList<>(columns.size());
    for (AddColumnStatement column : columns) {
      if (column.isAutoIncrement()) {
        throw UnsupportedClickHouseFeatureException.autoIncrement();
      }
      statements.add(
          "ALTER TABLE "
              + qualifiedTableName(database, column.getCatalogName(), column.getTableName())
              + clusterPolicy().onClusterClause()
              + " ADD COLUMN "
              + Identifiers.quote(column.getColumnName())
              + " "
              + renderType(column, database));
    }

    return sql(statements.toArray(String[]::new));
  }

  private static String renderType(AddColumnStatement column, Database database) {
    String type =
        DataTypeFactory.getInstance()
            .fromDescription(column.getColumnType(), database)
            .toDatabaseDataType(database)
            .toSql();

    return column.isNullable() ? ClickHouseTypes.nullable(type) : type;
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.ArrayList;
import java.util.List;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.DropColumnStatement;

public class DropColumnGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<DropColumnStatement> {

  @Override
  public Sql[] generateSql(
      DropColumnStatement statement,
      Database database,
      SqlGeneratorChain<DropColumnStatement> chain) {

    List<DropColumnStatement> columns =
        statement.getColumns().isEmpty() ? List.of(statement) : statement.getColumns();

    List<String> statements = new ArrayList<>(columns.size());
    for (DropColumnStatement column : columns) {
      statements.add(
          "ALTER TABLE "
              + qualifiedTableName(database, column.getCatalogName(), column.getTableName())
              + clusterPolicy().onClusterClause()
              + " DROP COLUMN "
              + Identifiers.quote(column.getColumnName()));
    }

    return sql(statements.toArray(String[]::new));
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.RenameColumnStatement;

public class RenameColumnGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<RenameColumnStatement> {

  @Override
  public Sql[] generateSql(
      RenameColumnStatement statement,
      Database database,
      SqlGeneratorChain<RenameColumnStatement> chain) {

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " RENAME COLUMN "
            + Identifiers.quote(statement.getOldColumnName())
            + " TO "
            + Identifiers.quote(statement.getNewColumnName()));
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column;

import io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseTypes;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.ModifyDataTypeStatement;

public class ModifyDataTypeGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<ModifyDataTypeStatement> {

  @Override
  public Sql[] generateSql(
      ModifyDataTypeStatement statement,
      Database database,
      SqlGeneratorChain<ModifyDataTypeStatement> chain) {

    String type =
        DataTypeFactory.getInstance()
            .fromDescription(statement.getNewDataType(), database)
            .toDatabaseDataType(database)
            .toSql();

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " MODIFY COLUMN "
            + Identifiers.quote(statement.getColumnName())
            + " "
            + ClickHouseTypes.nullable(type));
  }
}
```

- [ ] **Step 4: Дописать регистрацию**

Добавь в `META-INF/services/liquibase.sqlgenerator.SqlGenerator`:

```
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column.AddColumnGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column.DropColumnGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column.RenameColumnGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column.ModifyDataTypeGeneratorClickHouse
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ColumnGeneratorsTest`
Expected: PASS, 6 тестов.

- [ ] **Step 6: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add column DDL generators

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: Переименование таблиц и data-skipping индексы

**Files:**
- Create: `.../sqlgenerator/table/RenameTableGeneratorClickHouse.java`
- Create: `.../sqlgenerator/index/CreateIndexGeneratorClickHouse.java`
- Create: `.../sqlgenerator/index/DropIndexGeneratorClickHouse.java`
- Modify: `META-INF/services/liquibase.sqlgenerator.SqlGenerator`
- Test: `.../sqlgenerator/index/IndexGeneratorsTest.java`

**Interfaces:**
- Consumes: `AbstractClickHouseSqlGenerator` (Task 6), `Identifiers` (Task 4).
- Produces: генераторы для `RenameTableStatement`, `CreateIndexStatement`, `DropIndexStatement`.

> **В ClickHouse нет индексов в привычном смысле.** Есть data-skipping-индексы: они не ускоряют точечный поиск, а позволяют пропускать гранулы при сканировании. Из `CreateIndexStatement` нельзя узнать тип индекса, поэтому используется `minmax` с `GRANULARITY 1` — единственный тип, осмысленный для любого типа колонки.
>
> **`ADD INDEX` не применяется к уже записанным данным.** Поэтому генератор ВСЕГДА выдаёт два оператора: `ADD INDEX`, затем `MATERIALIZE INDEX`. Без второго индекс будет работать только для новых партов, и пользователь об этом не узнает.
>
> **Уникальный индекс невозможен.** ClickHouse не умеет проверять уникальность. Запрос уникального индекса — ошибка, а не повод молча создать неуникальный.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import java.util.List;
import liquibase.change.AddColumnConfig;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.CreateIndexStatement;
import liquibase.statement.core.DropIndexStatement;
import liquibase.statement.core.RenameTableStatement;
import org.junit.jupiter.api.Test;

class IndexGeneratorsTest {

  private final Database database = new ClickHouseDatabase();

  private List<String> generate(SqlStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return Arrays.stream(sql).map(Sql::toSql).toList();
  }

  private static AddColumnConfig column(String name) {
    AddColumnConfig config = new AddColumnConfig();
    config.setName(name);
    return config;
  }

  private CreateIndexStatement index(Boolean unique) {
    return new CreateIndexStatement(
        "idx_country", "analytics", null, "events", unique, null, column("country"));
  }

  @Test
  void renamesATable() {
    assertThat(generate(new RenameTableStatement("analytics", null, "events", "events_v2")))
        .containsExactly("RENAME TABLE `analytics`.`events` TO `analytics`.`events_v2`");
  }

  @Test
  void addsAndMaterialisesADataSkippingIndex() {
    assertThat(generate(index(false)))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` ADD INDEX `idx_country` (`country`)"
                + " TYPE minmax GRANULARITY 1",
            "ALTER TABLE `analytics`.`events` MATERIALIZE INDEX `idx_country`");
  }

  @Test
  void refusesToFakeAUniqueIndex() {
    assertThatThrownBy(() -> generate(index(true)))
        .hasMessageContaining("unique indexes")
        .hasMessageContaining("ReplacingMergeTree");
  }

  @Test
  void dropsAnIndex() {
    assertThat(generate(new DropIndexStatement("idx_country", "analytics", null, "events", null)))
        .containsExactly("ALTER TABLE `analytics`.`events` DROP INDEX `idx_country`");
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=IndexGeneratorsTest`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.RenameTableStatement;

public class RenameTableGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<RenameTableStatement> {

  @Override
  public Sql[] generateSql(
      RenameTableStatement statement,
      Database database,
      SqlGeneratorChain<RenameTableStatement> chain) {

    return sql(
        "RENAME TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getOldTableName())
            + " TO "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getNewTableName())
            + clusterPolicy().onClusterClause());
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.index;

import io.github.mnem0c0der.liquibase.ext.clickhouse.exception.UnsupportedClickHouseFeatureException;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.Arrays;
import java.util.stream.Collectors;
import liquibase.change.AddColumnConfig;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.CreateIndexStatement;

/**
 * Создаёт data-skipping-индекс.
 *
 * <p>Всегда выдаёт два оператора: ADD INDEX действует только на новые парты, поэтому сразу за ним
 * идёт MATERIALIZE INDEX, применяющий индекс к уже записанным данным.
 */
public class CreateIndexGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<CreateIndexStatement> {

  private static final String DEFAULT_INDEX_TYPE = "minmax";
  private static final int DEFAULT_GRANULARITY = 1;

  @Override
  public Sql[] generateSql(
      CreateIndexStatement statement,
      Database database,
      SqlGeneratorChain<CreateIndexStatement> chain) {

    if (Boolean.TRUE.equals(statement.isUnique())) {
      throw UnsupportedClickHouseFeatureException.uniqueIndexes();
    }

    String table =
        qualifiedTableName(database, statement.getTableCatalogName(), statement.getTableName());
    String indexName = Identifiers.quote(statement.getIndexName());
    String onCluster = clusterPolicy().onClusterClause();

    String columns =
        Arrays.stream(statement.getColumns())
            .map(AddColumnConfig::getName)
            .map(Identifiers::quote)
            .collect(Collectors.joining(", "));

    return sql(
        "ALTER TABLE "
            + table
            + onCluster
            + " ADD INDEX "
            + indexName
            + " ("
            + columns
            + ") TYPE "
            + DEFAULT_INDEX_TYPE
            + " GRANULARITY "
            + DEFAULT_GRANULARITY,
        "ALTER TABLE " + table + onCluster + " MATERIALIZE INDEX " + indexName);
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.index;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.DropIndexStatement;

public class DropIndexGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<DropIndexStatement> {

  @Override
  public Sql[] generateSql(
      DropIndexStatement statement, Database database, SqlGeneratorChain<DropIndexStatement> chain) {

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getTableCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " DROP INDEX "
            + Identifiers.quote(statement.getIndexName()));
  }
}
```

- [ ] **Step 4: Дописать регистрацию**

```
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.table.RenameTableGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.index.CreateIndexGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.index.DropIndexGeneratorClickHouse
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=IndexGeneratorsTest`
Expected: PASS, 4 теста.

- [ ] **Step 6: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add rename table and data-skipping index generators

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: DML — `INSERT`, и мутации для `UPDATE` / `DELETE`

**Files:**
- Create: `.../sqlgenerator/dml/InsertGeneratorClickHouse.java`
- Create: `.../sqlgenerator/dml/UpdateGeneratorClickHouse.java`
- Create: `.../sqlgenerator/dml/DeleteGeneratorClickHouse.java`
- Modify: `META-INF/services/liquibase.sqlgenerator.SqlGenerator`
- Test: `.../sqlgenerator/dml/DmlGeneratorsTest.java`

**Interfaces:**
- Consumes: `SqlValues.render` (Task 7), `ClickHouseConfiguration.MUTATIONS_SYNC` (Task 3).
- Produces: генераторы для `InsertStatement`, `UpdateStatement`, `DeleteStatement`.

> **`UPDATE` и `DELETE` в ClickHouse — мутации.** Синтаксис `ALTER TABLE ... UPDATE/DELETE`, выполнение асинхронное. Настройка `mutations_sync = 2` заставляет сервер дождаться применения на всех репликах — без неё Liquibase сочтёт changeset применённым раньше, чем данные действительно изменятся, и следующий changeset может прочитать старые данные.
>
> **`WHERE` обязателен.** ClickHouse отвергает мутацию без `WHERE`. Для changeset без условия подставляется `WHERE 1 = 1`.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import java.util.List;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.DeleteStatement;
import liquibase.statement.core.InsertStatement;
import liquibase.statement.core.UpdateStatement;
import org.junit.jupiter.api.Test;

class DmlGeneratorsTest {

  private final Database database = new ClickHouseDatabase();

  private List<String> generate(SqlStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return Arrays.stream(sql).map(Sql::toSql).toList();
  }

  @Test
  void insertsARow() {
    InsertStatement statement = new InsertStatement("analytics", null, "events");
    statement.addColumnValue("id", 1L);
    statement.addColumnValue("name", "launch");

    assertThat(generate(statement))
        .containsExactly(
            "INSERT INTO `analytics`.`events` (`id`, `name`) VALUES (1, 'launch')");
  }

  @Test
  void updatesThroughASynchronousMutation() {
    UpdateStatement statement = new UpdateStatement("analytics", null, "events");
    statement.addNewColumnValue("name", "renamed");
    statement.setWhereClause("id = 1");

    assertThat(generate(statement))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` UPDATE `name` = 'renamed' WHERE id = 1"
                + " SETTINGS mutations_sync = 2");
  }

  @Test
  void suppliesATautologyWhenAnUpdateHasNoWhereClause() {
    UpdateStatement statement = new UpdateStatement("analytics", null, "events");
    statement.addNewColumnValue("name", "renamed");

    assertThat(generate(statement).get(0)).contains("WHERE 1 = 1");
  }

  @Test
  void deletesThroughASynchronousMutation() {
    DeleteStatement statement = new DeleteStatement("analytics", null, "events");
    statement.setWhere("id = 1");

    assertThat(generate(statement))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` DELETE WHERE id = 1 SETTINGS mutations_sync = 2");
  }
}
```

> `DeleteStatement` имеет два параллельных поля условия: `where` и `whereClause`. Литеральное условие хранится в `where`,
> поэтому и тест, и генератор используют `setWhere` / `getWhere`.

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=DmlGeneratorsTest`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.SqlValues;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.Map;
import java.util.stream.Collectors;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.InsertStatement;

public class InsertGeneratorClickHouse extends AbstractClickHouseSqlGenerator<InsertStatement> {

  @Override
  public Sql[] generateSql(
      InsertStatement statement, Database database, SqlGeneratorChain<InsertStatement> chain) {

    Map<String, Object> values = statement.getColumnValues();

    String columns =
        values.keySet().stream().map(Identifiers::quote).collect(Collectors.joining(", "));
    String literals =
        values.values().stream()
            .map(value -> SqlValues.render(value, database))
            .collect(Collectors.joining(", "));

    return sql(
        "INSERT INTO "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + " ("
            + columns
            + ") VALUES ("
            + literals
            + ")");
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;

/** Общая для мутаций часть: ClickHouse требует WHERE и должен дождаться применения. */
final class Mutations {

  static final String TAUTOLOGY = "1 = 1";

  private Mutations() {}

  static String whereOrTautology(String whereClause) {
    return whereClause == null || whereClause.isBlank() ? TAUTOLOGY : whereClause;
  }

  static String synchronousSettings() {
    return " SETTINGS mutations_sync = " + ClickHouseConfiguration.MUTATIONS_SYNC.getCurrentValue();
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.SqlValues;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import java.util.stream.Collectors;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.UpdateStatement;

public class UpdateGeneratorClickHouse extends AbstractClickHouseSqlGenerator<UpdateStatement> {

  @Override
  public Sql[] generateSql(
      UpdateStatement statement, Database database, SqlGeneratorChain<UpdateStatement> chain) {

    String assignments =
        statement.getNewColumnValues().entrySet().stream()
            .map(
                entry ->
                    Identifiers.quote(entry.getKey())
                        + " = "
                        + SqlValues.render(entry.getValue(), database))
            .collect(Collectors.joining(", "));

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " UPDATE "
            + assignments
            + " WHERE "
            + Mutations.whereOrTautology(statement.getWhereClause())
            + Mutations.synchronousSettings());
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.DeleteStatement;

public class DeleteGeneratorClickHouse extends AbstractClickHouseSqlGenerator<DeleteStatement> {

  @Override
  public Sql[] generateSql(
      DeleteStatement statement, Database database, SqlGeneratorChain<DeleteStatement> chain) {

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " DELETE WHERE "
            + Mutations.whereOrTautology(statement.getWhere())
            + Mutations.synchronousSettings());
  }
}
```

- [ ] **Step 4: Дописать регистрацию**

```
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml.InsertGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml.UpdateGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml.DeleteGeneratorClickHouse
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=DmlGeneratorsTest`
Expected: PASS, 4 теста.

- [ ] **Step 6: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add DML generators using ClickHouse mutations

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: Явный отказ для неподдерживаемых конструкций

**Files:**
- Create: `.../sqlgenerator/unsupported/UnsupportedFeatureGenerators.java`
- Modify: `META-INF/services/liquibase.sqlgenerator.SqlGenerator`
- Test: `.../sqlgenerator/unsupported/UnsupportedFeatureGeneratorsTest.java`

**Interfaces:**
- Consumes: `AbstractClickHouseSqlGenerator` (Task 6), `UnsupportedClickHouseFeatureException` (Task 6).
- Produces: вложенные публичные статические классы-генераторы `UnsupportedFeatureGenerators.ForeignKey`, `.PrimaryKey`, `.AutoIncrement`, `.UniqueConstraint`, `.Sequence`.

> **Почему все пять в одном файле.** Каждый — три строки, и все они реализуют одно правило. Пять почти пустых файлов ухудшили бы читаемость, ничего не выиграв. Это тот случай, когда «файлы, меняющиеся вместе, живут вместе» важнее формального «класс на файл».

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.unsupported;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import liquibase.database.Database;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.AddAutoIncrementStatement;
import liquibase.statement.core.AddForeignKeyConstraintStatement;
import liquibase.statement.core.AddPrimaryKeyStatement;
import liquibase.statement.core.AddUniqueConstraintStatement;
import liquibase.statement.core.CreateSequenceStatement;
import java.math.BigInteger;
import liquibase.change.ColumnConfig;
import org.junit.jupiter.api.Test;

class UnsupportedFeatureGeneratorsTest {

  private final Database database = new ClickHouseDatabase();

  private void assertRefused(SqlStatement statement, String feature, String hint) {
    assertThatThrownBy(() -> SqlGeneratorFactory.getInstance().generateSql(statement, database))
        .hasMessageContaining(feature)
        .hasMessageContaining(hint);
  }

  @Test
  void refusesForeignKeys() {
    assertRefused(
        new AddForeignKeyConstraintStatement(
            "fk",
            "analytics",
            null,
            "events",
            new ColumnConfig[] {new ColumnConfig().setName("user_id")},
            "analytics",
            null,
            "users",
            new ColumnConfig[] {new ColumnConfig().setName("id")}),
        "foreign key constraints",
        "application");
  }

  @Test
  void refusesPrimaryKeyConstraints() {
    assertRefused(
        new AddPrimaryKeyStatement("analytics", null, "events", "id", "pk"),
        "adding a primary key to an existing table",
        "ORDER BY");
  }

  @Test
  void refusesAutoIncrement() {
    assertRefused(
        new AddAutoIncrementStatement(
            "analytics",
            null,
            "events",
            "id",
            "bigint",
            BigInteger.ONE,
            BigInteger.ONE,
            Boolean.FALSE,
            null),
        "auto-increment columns",
        "generateUUIDv4");
  }

  @Test
  void refusesUniqueConstraints() {
    assertRefused(
        new AddUniqueConstraintStatement(
            "analytics", null, "events", new ColumnConfig[] {new ColumnConfig().setName("id")}, "uq"),
        "unique constraints",
        "ReplacingMergeTree");
  }

  @Test
  void refusesSequences() {
    assertRefused(
        new CreateSequenceStatement("analytics", null, "seq"), "sequences", "generateUUIDv4");
  }

  @Test
  void everyRefusalPointsAtTheDocumentedAlternatives() {
    assertThatThrownBy(
            () ->
                SqlGeneratorFactory.getInstance()
                    .generateSql(new CreateSequenceStatement("analytics", null, "seq"), database))
        .hasMessageContaining("#unsupported-features");
  }
}
```

> Сигнатуры выше сверены с 4.31.1. Обрати внимание на две ловушки: используется `liquibase.change.ColumnConfig`,
> а не `liquibase.statement.ColumnConfig`; у `AddPrimaryKeyStatement` две перегрузки, и `null` четвёртым аргументом сделает вызов неоднозначным —
> передавай имя колонки строкой.

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=UnsupportedFeatureGeneratorsTest`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

```java
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
 * Генераторы, которые сознательно отказываются работать.
 *
 * <p>Перечисленные конструкции в ClickHouse отсутствуют. Молчаливое игнорирование создало бы
 * схему, отличающуюся от описанной в changelog, поэтому расширение останавливает миграцию и
 * сообщает работающую альтернативу.
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
```

- [ ] **Step 4: Дописать регистрацию**

```
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.unsupported.UnsupportedFeatureGenerators$ForeignKey
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.unsupported.UnsupportedFeatureGenerators$PrimaryKey
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.unsupported.UnsupportedFeatureGenerators$AutoIncrement
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.unsupported.UnsupportedFeatureGenerators$UniqueConstraint
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.unsupported.UnsupportedFeatureGenerators$Sequence
```

Обрати внимание на `$` — это вложенные классы, и `ServiceLoader` требует именно двоичное имя.

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=UnsupportedFeatureGeneratorsTest`
Expected: PASS, 5 тестов.

- [ ] **Step 6: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: refuse unsupported ClickHouse features with actionable messages

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 12: Таблица `DATABASECHANGELOG` на `ReplacingMergeTree`

**Files:**
- Create: `.../sqlgenerator/changelog/CreateDatabaseChangeLogTableGeneratorClickHouse.java`
- Create: `.../changelog/ChangeLogTable.java`
- Modify: `META-INF/services/liquibase.sqlgenerator.SqlGenerator`
- Test: `.../sqlgenerator/changelog/CreateDatabaseChangeLogTableGeneratorClickHouseTest.java`

**Interfaces:**
- Consumes: `AbstractClickHouseSqlGenerator` (Task 6), `ClusterPolicy` (Task 4).
- Produces: `ChangeLogTable.ROW_VERSION_COLUMN` = `"ROWVERSION"`; `ChangeLogTable.qualifiedName(Database database)` → `String` — используется в Task 15.

> **Зачем колонка `ROWVERSION`.** `ReplacingMergeTree` схлопывает строки с одинаковым ключом сортировки, оставляя строку с наибольшим значением версии. Это даёт семантику «обновления» без `UPDATE`: чтобы изменить запись, достаточно вставить её заново с большей версией. `DEFAULT toUnixTimestamp64Milli(now64(3))` означает, что обычные вставки Liquibase, ничего не знающие об этой колонке, продолжают работать без изменений.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.core.CreateDatabaseChangeLogTableStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateDatabaseChangeLogTableGeneratorClickHouseTest {

  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    database = new ClickHouseDatabase();
    database.setDefaultCatalogName("analytics");
  }

  private String generate() {
    Sql[] sql =
        SqlGeneratorFactory.getInstance()
            .generateSql(new CreateDatabaseChangeLogTableStatement(), database);
    return String.join("; ", Arrays.stream(sql).map(Sql::toSql).toList());
  }

  @Test
  void createsTheTrackingTableAsAReplacingMergeTree() {
    assertThat(generate())
        .startsWith("CREATE TABLE IF NOT EXISTS `analytics`.`DATABASECHANGELOG` (")
        .contains("`ID` String")
        .contains("`AUTHOR` String")
        .contains("`FILENAME` String")
        .contains("`DATEEXECUTED` DateTime64(3)")
        .contains("`ORDEREXECUTED` Int32")
        .contains("`EXECTYPE` String")
        .contains("`MD5SUM` Nullable(String)")
        .contains("`TAG` Nullable(String)")
        .contains("`DEPLOYMENT_ID` Nullable(String)")
        .contains("`ROWVERSION` UInt64 DEFAULT toUnixTimestamp64Milli(now64(3))")
        .endsWith(
            "ENGINE = ReplacingMergeTree(`ROWVERSION`) ORDER BY (`ID`, `AUTHOR`, `FILENAME`)");
  }

  @Test
  void usesAReplicatedEngineAndOnClusterWhenClustered() throws Exception {
    String sql =
        liquibase.Scope.child(
            java.util.Map.of("liquibase.clickhouse.cluster", "analytics_cluster"),
            this::generate);

    assertThat(sql)
        .contains("`DATABASECHANGELOG` ON CLUSTER `analytics_cluster` (")
        .contains("ENGINE = ReplicatedReplacingMergeTree(");
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=CreateDatabaseChangeLogTableGeneratorClickHouseTest`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.changelog;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import liquibase.database.Database;

/** Разделяемое описание таблицы истории changelog. */
public final class ChangeLogTable {

  public static final String ROW_VERSION_COLUMN = "ROWVERSION";

  private ChangeLogTable() {}

  public static String qualifiedName(Database database) {
    String catalog =
        database.getLiquibaseCatalogName() == null
            ? database.getDefaultCatalogName()
            : database.getLiquibaseCatalogName();

    String table = Identifiers.quote(database.getDatabaseChangeLogTableName());
    return catalog == null || catalog.isBlank()
        ? table
        : Identifiers.quote(catalog) + "." + table;
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.changelog;

import io.github.mnem0c0der.liquibase.ext.clickhouse.changelog.ChangeLogTable;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.CreateDatabaseChangeLogTableStatement;

/**
 * Создаёт DATABASECHANGELOG.
 *
 * <p>Движок ReplacingMergeTree выбран потому, что Liquibase местами обновляет уже записанные
 * строки (tag, clearCheckSums), а UPDATE в ClickHouse — тяжёлая асинхронная мутация. Вместо неё
 * запись перевставляется с большей версией, а FINAL при чтении оставляет только последнюю.
 */
public class CreateDatabaseChangeLogTableGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<CreateDatabaseChangeLogTableStatement> {

  private static final String COLUMNS =
      String.join(
          ", ",
          "`ID` String",
          "`AUTHOR` String",
          "`FILENAME` String",
          "`DATEEXECUTED` DateTime64(3)",
          "`ORDEREXECUTED` Int32",
          "`EXECTYPE` String",
          "`MD5SUM` Nullable(String)",
          "`DESCRIPTION` Nullable(String)",
          "`COMMENTS` Nullable(String)",
          "`TAG` Nullable(String)",
          "`LIQUIBASE` Nullable(String)",
          "`CONTEXTS` Nullable(String)",
          "`LABELS` Nullable(String)",
          "`DEPLOYMENT_ID` Nullable(String)",
          "`" + ChangeLogTable.ROW_VERSION_COLUMN + "` UInt64"
              + " DEFAULT toUnixTimestamp64Milli(now64(3))");

  @Override
  public Sql[] generateSql(
      CreateDatabaseChangeLogTableStatement statement,
      Database database,
      SqlGeneratorChain<CreateDatabaseChangeLogTableStatement> chain) {

    String engine =
        clusterPolicy()
            .resolveEngine("ReplacingMergeTree(`" + ChangeLogTable.ROW_VERSION_COLUMN + "`)");

    return sql(
        "CREATE TABLE IF NOT EXISTS "
            + ChangeLogTable.qualifiedName(database)
            + clusterPolicy().onClusterClause()
            + " ("
            + COLUMNS
            + ") ENGINE = "
            + engine
            + " ORDER BY (`ID`, `AUTHOR`, `FILENAME`)");
  }
}
```

- [ ] **Step 4: Дописать регистрацию**

```
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.changelog.CreateDatabaseChangeLogTableGeneratorClickHouse
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=CreateDatabaseChangeLogTableGeneratorClickHouseTest`
Expected: PASS, 2 теста.

- [ ] **Step 6: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: create DATABASECHANGELOG as a ReplacingMergeTree table

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 12b: Оставшиеся генераторы стандартных changeType

**Files:**
- Create: `.../sqlgenerator/changelog/TagDatabaseGeneratorClickHouse.java`
- Create: `.../sqlgenerator/column/SetNullableGeneratorClickHouse.java`
- Create: `.../sqlgenerator/column/AddDefaultValueGeneratorClickHouse.java`
- Create: `.../sqlgenerator/column/DropDefaultValueGeneratorClickHouse.java`
- Create: `.../sqlgenerator/dml/InsertSetGeneratorClickHouse.java`
- Modify: `META-INF/services/liquibase.sqlgenerator.SqlGenerator`
- Test: `.../sqlgenerator/RemainingGeneratorsTest.java`

**Interfaces:**
- Consumes: `AbstractClickHouseSqlGenerator` (Task 6), `ChangeLogTable` (Task 12), `ClickHouseTypes.nullable` (Task 5), `SqlValues.render` (Task 7).
- Produces: генераторы для `TagDatabaseStatement`, `SetNullableStatement`, `AddDefaultValueStatement`, `DropDefaultValueStatement`, `InsertSetStatement`.

> **Без `TagDatabaseGeneratorClickHouse` интеграционный тест из Task 16 упадёт.** У `TagDatabaseStatement` есть собственный генератор по умолчанию, который выдаёт `UPDATE DATABASECHANGELOG SET TAG = ...`. Переопределения `tag()` в history-сервисе (Task 15) для этого недостаточно: changeSet `<tagDatabase>` идёт не через сервис, а через оператор. Здесь тег проставляется вставкой новой версии последней строки.
>
> **`loadData` идёт через `InsertSetStatement`.** ClickHouse понимает многострочный `VALUES`, поэтому весь набор укладывается в один `INSERT` — это заметно быстрее, чем построчная вставка.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.Arrays;
import java.util.List;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.AddDefaultValueStatement;
import liquibase.statement.core.DropDefaultValueStatement;
import liquibase.statement.core.InsertSetStatement;
import liquibase.statement.core.InsertStatement;
import liquibase.statement.core.SetNullableStatement;
import liquibase.statement.core.TagDatabaseStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemainingGeneratorsTest {

  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    database = new ClickHouseDatabase();
    database.setDefaultCatalogName("analytics");
  }

  private List<String> generate(SqlStatement statement) {
    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, database);
    return Arrays.stream(sql).map(Sql::toSql).toList();
  }

  @Test
  void tagsTheLastChangeSetByInsertingANewRowVersion() {
    String sql = generate(new TagDatabaseStatement("v1")).get(0);

    assertThat(sql)
        .startsWith("INSERT INTO `analytics`.`DATABASECHANGELOG` SELECT * EXCEPT")
        .contains("'v1' AS `TAG`")
        .contains("FROM `analytics`.`DATABASECHANGELOG` FINAL")
        .endsWith("ORDER BY `ORDEREXECUTED` DESC LIMIT 1")
        .doesNotContainIgnoringCase("UPDATE");
  }

  @Test
  void makesAColumnNullable() {
    assertThat(
            generate(
                new SetNullableStatement("analytics", null, "events", "name", "varchar(50)", true)))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` MODIFY COLUMN `name` Nullable(String)");
  }

  @Test
  void makesAColumnNotNullable() {
    assertThat(
            generate(
                new SetNullableStatement("analytics", null, "events", "name", "varchar(50)", false)))
        .containsExactly("ALTER TABLE `analytics`.`events` MODIFY COLUMN `name` String");
  }

  @Test
  void addsAColumnDefault() {
    assertThat(
            generate(
                new AddDefaultValueStatement(
                    "analytics", null, "events", "country", "varchar(2)", "KZ")))
        .containsExactly(
            "ALTER TABLE `analytics`.`events` MODIFY COLUMN `country` Nullable(String)"
                + " DEFAULT 'KZ'");
  }

  @Test
  void removesAColumnDefault() {
    assertThat(
            generate(
                new DropDefaultValueStatement(
                    "analytics", null, "events", "country", "varchar(2)")))
        .containsExactly("ALTER TABLE `analytics`.`events` MODIFY COLUMN `country` REMOVE DEFAULT");
  }

  @Test
  void batchesLoadDataIntoASingleMultiRowInsert() {
    InsertSetStatement set = new InsertSetStatement("analytics", null, "events");

    InsertStatement first = new InsertStatement("analytics", null, "events");
    first.addColumnValue("id", 1L);
    first.addColumnValue("name", "a");

    InsertStatement second = new InsertStatement("analytics", null, "events");
    second.addColumnValue("id", 2L);
    second.addColumnValue("name", "b");

    set.addInsertStatement(first);
    set.addInsertStatement(second);

    assertThat(generate(set))
        .containsExactly(
            "INSERT INTO `analytics`.`events` (`id`, `name`) VALUES (1, 'a'), (2, 'b')");
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=RemainingGeneratorsTest`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.changelog;

import io.github.mnem0c0der.liquibase.ext.clickhouse.changelog.ChangeLogTable;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.TagDatabaseStatement;

/**
 * Проставляет тег последней применённой записи changelog.
 *
 * <p>Генератор по умолчанию выполняет UPDATE, которого в ClickHouse нет. Вместо него последняя
 * строка перевставляется с новым тегом и большей версией; FINAL при чтении оставит именно её.
 */
public class TagDatabaseGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<TagDatabaseStatement> {

  @Override
  public Sql[] generateSql(
      TagDatabaseStatement statement,
      Database database,
      SqlGeneratorChain<TagDatabaseStatement> chain) {

    String table = ChangeLogTable.qualifiedName(database);
    String versionColumn = "`" + ChangeLogTable.ROW_VERSION_COLUMN + "`";

    return sql(
        "INSERT INTO "
            + table
            + " SELECT * EXCEPT ("
            + versionColumn
            + ", `TAG`), "
            + Identifiers.literal(statement.getTag())
            + " AS `TAG`, toUnixTimestamp64Milli(now64(3)) AS "
            + versionColumn
            + " FROM "
            + table
            + " FINAL ORDER BY `ORDEREXECUTED` DESC LIMIT 1");
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column;

import io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseTypes;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.SetNullableStatement;

public class SetNullableGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<SetNullableStatement> {

  @Override
  public Sql[] generateSql(
      SetNullableStatement statement,
      Database database,
      SqlGeneratorChain<SetNullableStatement> chain) {

    String type =
        DataTypeFactory.getInstance()
            .fromDescription(statement.getColumnDataType(), database)
            .toDatabaseDataType(database)
            .toSql();

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " MODIFY COLUMN "
            + Identifiers.quote(statement.getColumnName())
            + " "
            + (statement.isNullable() ? ClickHouseTypes.nullable(type) : type));
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column;

import io.github.mnem0c0der.liquibase.ext.clickhouse.datatype.ClickHouseTypes;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.SqlValues;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.AddDefaultValueStatement;

public class AddDefaultValueGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<AddDefaultValueStatement> {

  @Override
  public Sql[] generateSql(
      AddDefaultValueStatement statement,
      Database database,
      SqlGeneratorChain<AddDefaultValueStatement> chain) {

    String type =
        DataTypeFactory.getInstance()
            .fromDescription(statement.getColumnDataType(), database)
            .toDatabaseDataType(database)
            .toSql();

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " MODIFY COLUMN "
            + Identifiers.quote(statement.getColumnName())
            + " "
            + ClickHouseTypes.nullable(type)
            + " DEFAULT "
            + SqlValues.render(statement.getDefaultValue(), database));
  }
}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column;

import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.AbstractClickHouseSqlGenerator;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.DropDefaultValueStatement;

public class DropDefaultValueGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<DropDefaultValueStatement> {

  @Override
  public Sql[] generateSql(
      DropDefaultValueStatement statement,
      Database database,
      SqlGeneratorChain<DropDefaultValueStatement> chain) {

    return sql(
        "ALTER TABLE "
            + qualifiedTableName(database, statement.getCatalogName(), statement.getTableName())
            + clusterPolicy().onClusterClause()
            + " MODIFY COLUMN "
            + Identifiers.quote(statement.getColumnName())
            + " REMOVE DEFAULT");
  }
}
```

```java
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
 * Укладывает весь набор строк loadData в один многострочный INSERT.
 *
 * <p>ClickHouse оптимизирован под крупные пакетные вставки: построчная вставка создала бы по
 * отдельному парту на строку и заметно нагрузила бы слияния.
 */
public class InsertSetGeneratorClickHouse
    extends AbstractClickHouseSqlGenerator<InsertSetStatement> {

  @Override
  public Sql[] generateSql(
      InsertSetStatement statement, Database database, SqlGeneratorChain<InsertSetStatement> chain) {

    List<InsertStatement> rows = statement.getStatements();
    if (rows.isEmpty()) {
      return EMPTY_SQL;
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
```

- [ ] **Step 4: Дописать регистрацию**

```
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.changelog.TagDatabaseGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column.SetNullableGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column.AddDefaultValueGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.column.DropDefaultValueGeneratorClickHouse
io.github.mnem0c0der.liquibase.ext.clickhouse.sqlgenerator.dml.InsertSetGeneratorClickHouse
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=RemainingGeneratorsTest`
Expected: PASS, 6 тестов.

- [ ] **Step 6: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add tag, nullability, default value and batch insert generators

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---
### Task 13: `OptimisticLockArbiter` — чистая логика захвата блокировки

**Files:**
- Create: `.../lock/LockCandidate.java`
- Create: `.../lock/OptimisticLockArbiter.java`
- Test: `.../lock/OptimisticLockArbiterTest.java`

**Interfaces:**
- Consumes: ничего. Класс сознательно не зависит ни от Liquibase, ни от JDBC.
- Produces:
  - `record LockCandidate(String lockId, boolean locked, Instant grantedAt, long version, String lockedBy)`
  - `new OptimisticLockArbiter(Duration staleAfter)`
  - `arbiter.currentHolder(Collection<LockCandidate> rows, Instant now)` → `Optional<LockCandidate>`
  - `arbiter.hasWon(Collection<LockCandidate> rows, String myLockId, Instant now)` → `boolean`
  - `arbiter.isStale(LockCandidate candidate, Instant now)` → `boolean`

> **Это самый важный класс проекта и он обязан остаться чистой функцией.** Конкурентную логику нельзя надёжно проверить, «запустив пару раз» — гонка либо воспроизводится раз в сто прогонов, либо не воспроизводится вовсе. Вынеся правило выбора победителя в функцию без побочных эффектов, мы проверяем все интересные ситуации детерминированно и мгновенно.
>
> **Правило победителя: минимальная версия, при равенстве — лексикографически меньший `lockId`.** Версия монотонно растёт, поэтому «минимальная версия» означает «вставился первым». Тай-брейк по `lockId` нужен потому, что две вставки могут получить одну миллисекунду; без него два процесса могли бы одновременно счесть себя победителями.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OptimisticLockArbiterTest {

  private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
  private final OptimisticLockArbiter arbiter = new OptimisticLockArbiter(Duration.ofMinutes(5));

  private static LockCandidate claim(String lockId, long version, Instant grantedAt) {
    return new LockCandidate(lockId, true, grantedAt, version, "host/" + lockId);
  }

  private static LockCandidate release(String lockId, long version) {
    return new LockCandidate(lockId, false, NOW, version, "host/" + lockId);
  }

  @Test
  void noRowsMeansNobodyHoldsTheLock() {
    assertThat(arbiter.currentHolder(List.of(), NOW)).isEmpty();
  }

  @Test
  void aSingleClaimantHoldsTheLock() {
    assertThat(arbiter.currentHolder(List.of(claim("a", 1, NOW)), NOW))
        .map(LockCandidate::lockId)
        .contains("a");
  }

  @Test
  void theEarliestClaimWins() {
    List<LockCandidate> rows = List.of(claim("b", 2, NOW), claim("a", 1, NOW));

    assertThat(arbiter.currentHolder(rows, NOW)).map(LockCandidate::lockId).contains("a");
    assertThat(arbiter.hasWon(rows, "a", NOW)).isTrue();
    assertThat(arbiter.hasWon(rows, "b", NOW)).isFalse();
  }

  @Test
  void identicalVersionsAreBrokenByLockIdSoExactlyOneProcessWins() {
    List<LockCandidate> rows = List.of(claim("zzz", 7, NOW), claim("aaa", 7, NOW));

    assertThat(arbiter.hasWon(rows, "aaa", NOW)).isTrue();
    assertThat(arbiter.hasWon(rows, "zzz", NOW)).isFalse();
  }

  @Test
  void aLaterReleaseRowRetractsAnEarlierClaim() {
    List<LockCandidate> rows =
        List.of(claim("a", 1, NOW), release("a", 2), claim("b", 3, NOW));

    assertThat(arbiter.currentHolder(rows, NOW)).map(LockCandidate::lockId).contains("b");
  }

  @Test
  void anOlderClaimDoesNotResurrectAReleasedLock() {
    List<LockCandidate> rows = List.of(release("a", 5), claim("a", 4, NOW));

    assertThat(arbiter.currentHolder(rows, NOW)).isEmpty();
  }

  @Test
  void aStaleClaimIsIgnoredSoACrashedMigrationCannotDeadlockTheDatabase() {
    LockCandidate abandoned = claim("crashed", 1, NOW.minus(Duration.ofHours(1)));
    LockCandidate fresh = claim("healthy", 2, NOW);

    assertThat(arbiter.isStale(abandoned, NOW)).isTrue();
    assertThat(arbiter.isStale(fresh, NOW)).isFalse();
    assertThat(arbiter.currentHolder(List.of(abandoned, fresh), NOW))
        .map(LockCandidate::lockId)
        .contains("healthy");
  }

  @Test
  void aClaimExactlyAtTheStalenessBoundaryIsStillValid() {
    LockCandidate borderline = claim("a", 1, NOW.minus(Duration.ofMinutes(5)));

    assertThat(arbiter.isStale(borderline, NOW)).isFalse();
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=OptimisticLockArbiterTest`
Expected: FAIL — классы не существуют.

- [ ] **Step 3: Реализовать**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.lock;

import java.time.Instant;

/**
 * Одна строка таблицы блокировок.
 *
 * @param lockId уникальный идентификатор попытки захвата
 * @param locked true — заявка на захват, false — освобождение
 * @param grantedAt момент заявки, используется для определения протухших блокировок
 * @param version монотонно растущая версия строки; ReplacingMergeTree оставляет наибольшую
 * @param lockedBy человекочитаемое описание владельца для диагностики
 */
public record LockCandidate(
    String lockId, boolean locked, Instant grantedAt, long version, String lockedBy) {}
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.lock;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Определяет владельца блокировки по набору строк-претендентов.
 *
 * <p>Класс намеренно не зависит ни от Liquibase, ни от JDBC: вся конкурентная логика
 * проверяется обычными юнит-тестами, без базы и без воспроизведения гонок.
 */
public final class OptimisticLockArbiter {

  private static final Comparator<LockCandidate> EARLIEST_THEN_SMALLEST_ID =
      Comparator.comparingLong(LockCandidate::version).thenComparing(LockCandidate::lockId);

  private final Duration staleAfter;

  public OptimisticLockArbiter(Duration staleAfter) {
    this.staleAfter = staleAfter;
  }

  public boolean isStale(LockCandidate candidate, Instant now) {
    return candidate.grantedAt().plus(staleAfter).isBefore(now);
  }

  /**
   * Возвращает текущего владельца, если он есть.
   *
   * <p>Строки сначала схлопываются по lockId с сохранением наибольшей версии — так освобождение
   * гарантированно перекрывает более раннюю заявку, даже если ReplacingMergeTree ещё не выполнил
   * слияние.
   */
  public Optional<LockCandidate> currentHolder(Collection<LockCandidate> rows, Instant now) {
    Map<String, LockCandidate> latestPerLockId =
        rows.stream()
            .collect(
                Collectors.toMap(
                    LockCandidate::lockId,
                    Function.identity(),
                    (left, right) -> left.version() >= right.version() ? left : right));

    return latestPerLockId.values().stream()
        .filter(LockCandidate::locked)
        .filter(candidate -> !isStale(candidate, now))
        .min(EARLIEST_THEN_SMALLEST_ID);
  }

  public boolean hasWon(Collection<LockCandidate> rows, String myLockId, Instant now) {
    return currentHolder(rows, now)
        .map(holder -> holder.lockId().equals(myLockId))
        .orElse(false);
  }
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=OptimisticLockArbiterTest`
Expected: PASS, 8 тестов.

- [ ] **Step 5: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add pure OptimisticLockArbiter for changelog locking

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 14: `LockRepository` и `ClickHouseLockService`

**Files:**
- Create: `.../lock/LockRepository.java`
- Create: `.../lock/ClickHouseLockService.java`
- Create: `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.lockservice.LockService`
- Test: `.../lock/ClickHouseLockServiceTest.java`

**Interfaces:**
- Consumes: `OptimisticLockArbiter`, `LockCandidate` (Task 13); `ClickHouseConfiguration.LOCK_*` (Task 3); `ClusterPolicy` (Task 4); `Identifiers` (Task 4).
- Produces:
  - `LockRepository` с методами `void createTableIfMissing()`, `void insert(LockCandidate candidate)`, `List<LockCandidate> readAll()`, `long nextVersion()`, `void dropTable()`
  - `ClickHouseLockService implements liquibase.lockservice.LockService`

> **Все переопределения SPI объявляют `throws DatabaseException`, а не `LiquibaseException`.** В Liquibase 5.0.x эти методы объявлены с более широким `throws LiquibaseException`; сужение при переопределении легально, и класс остаётся корректным в обеих линейках. Обратный порядок не скомпилируется против 4.31.1. Это прямое следствие Global Constraints и главный механизм поддержки Spring Boot 3 и 4 одним артефактом.
>
> **Почему сервис не ходит через `LockDatabaseChangeLogStatement`.** Стандартные операторы блокировки Liquibase выражают семантику `UPDATE ... WHERE LOCKED = 0`, которой в ClickHouse не существует. Репозиторий формирует собственный SQL напрямую — это честнее, чем притворяться, что стандартный оператор здесь применим.

- [ ] **Step 1: Написать падающий тест**

Юнит-тест проверяет поведение сервиса, которое не требует БД: отключение блокировки флагом и корректность отбора по типу СУБД. Работа с реальным ClickHouse проверяется в Task 17.

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.lock;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
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
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseLockServiceTest`
Expected: FAIL.

- [ ] **Step 3: Реализовать `LockRepository`**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.lock;

import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterPolicy;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.executor.Executor;
import liquibase.executor.ExecutorService;
import liquibase.statement.core.RawSqlStatement;

/**
 * Единственное место, где расширение читает и пишет таблицу блокировок.
 *
 * <p>Таблица работает в режиме «только добавление»: и захват, и освобождение — это INSERT новой
 * строки с большей версией. ReplacingMergeTree при чтении с FINAL оставляет последнюю версию для
 * каждого lockId.
 */
public final class LockRepository {

  private static final String LOCK_TABLE = "DATABASECHANGELOGLOCK";

  private final Database database;
  private final ClusterPolicy clusterPolicy;

  public LockRepository(Database database, ClusterPolicy clusterPolicy) {
    this.database = database;
    this.clusterPolicy = clusterPolicy;
  }

  public void createTableIfMissing() throws DatabaseException {
    String engine = clusterPolicy.resolveEngine("ReplacingMergeTree(`LOCKVERSION`)");

    execute(
        "CREATE TABLE IF NOT EXISTS "
            + qualifiedName()
            + clusterPolicy.onClusterClause()
            + " (`ID` Int32, `LOCKID` String, `LOCKED` UInt8, `LOCKGRANTED` DateTime64(3),"
            + " `LOCKEDBY` String, `LOCKVERSION` UInt64)"
            + " ENGINE = "
            + engine
            + " ORDER BY (`LOCKID`)");
  }

  public void insert(LockCandidate candidate) throws DatabaseException {
    execute(
        "INSERT INTO "
            + qualifiedName()
            + " (`ID`, `LOCKID`, `LOCKED`, `LOCKGRANTED`, `LOCKEDBY`, `LOCKVERSION`) VALUES (1, "
            + Identifiers.literal(candidate.lockId())
            + ", "
            + (candidate.locked() ? 1 : 0)
            + ", fromUnixTimestamp64Milli("
            + candidate.grantedAt().toEpochMilli()
            + "), "
            + Identifiers.literal(candidate.lockedBy())
            + ", "
            + candidate.version()
            + ")");
  }

  public List<LockCandidate> readAll() throws DatabaseException {
    List<Map<String, ?>> rows =
        executor()
            .queryForList(
                new RawSqlStatement(
                    "SELECT `LOCKID`, `LOCKED`, toUnixTimestamp64Milli(`LOCKGRANTED`) AS"
                        + " `GRANTEDMILLIS`, `LOCKEDBY`, `LOCKVERSION` FROM "
                        + qualifiedName()
                        + " FINAL"));

    List<LockCandidate> candidates = new ArrayList<>(rows.size());
    for (Map<String, ?> row : rows) {
      candidates.add(
          new LockCandidate(
              String.valueOf(value(row, "LOCKID")),
              ((Number) value(row, "LOCKED")).intValue() != 0,
              Instant.ofEpochMilli(((Number) value(row, "GRANTEDMILLIS")).longValue()),
              ((Number) value(row, "LOCKVERSION")).longValue(),
              String.valueOf(value(row, "LOCKEDBY"))));
    }
    return candidates;
  }

  /** Версии монотонны во времени, поэтому за основу берётся текущее время в миллисекундах. */
  public long nextVersion() throws DatabaseException {
    long now = System.currentTimeMillis();
    long highest =
        readAll().stream().mapToLong(LockCandidate::version).max().orElse(0L);
    return Math.max(now, highest + 1);
  }

  public void dropTable() throws DatabaseException {
    execute("DROP TABLE IF EXISTS " + qualifiedName() + clusterPolicy.onClusterClause());
  }

  private String qualifiedName() {
    String catalog =
        database.getLiquibaseCatalogName() == null
            ? database.getDefaultCatalogName()
            : database.getLiquibaseCatalogName();

    String table = Identifiers.quote(LOCK_TABLE);
    return catalog == null || catalog.isBlank()
        ? table
        : Identifiers.quote(catalog) + "." + table;
  }

  private void execute(String sql) throws DatabaseException {
    executor().execute(new RawSqlStatement(sql));
  }

  private Executor executor() {
    return Scope.getCurrentScope()
        .getSingleton(ExecutorService.class)
        .getExecutor("jdbc", database);
  }

  /** Драйверы по-разному регистрируют имена колонок, поэтому имя ищется без учёта регистра. */
  private static Object value(Map<String, ?> row, String column) {
    Object direct = row.get(column);
    if (direct != null) {
      return direct;
    }
    return row.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(column))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Column " + column + " missing from result"));
  }
}
```

- [ ] **Step 4: Реализовать `ClickHouseLockService`**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.lock;

import io.github.mnem0c0der.liquibase.ext.clickhouse.cluster.ClusterPolicyFactory;
import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.exception.LockException;
import liquibase.lockservice.DatabaseChangeLogLock;
import liquibase.lockservice.LockService;

/**
 * Блокировка changelog для ClickHouse.
 *
 * <p>В ClickHouse нет транзакций и нет атомарного UPDATE с числом затронутых строк, поэтому
 * применён оптимистичный протокол: заявка вставляется, затем состояние перечитывается, и
 * победитель определяется детерминированным правилом. Проигравший откатывает свою заявку и
 * повторяет попытку.
 *
 * <p>Все переопределения объявляют узкий {@code throws DatabaseException}: так класс компилируется
 * против Liquibase 4.31.1 и остаётся корректным переопределением в 5.0.x.
 */
public class ClickHouseLockService implements LockService {

  private Database database;
  private LockRepository repository;
  private OptimisticLockArbiter arbiter;

  private volatile boolean hasLock;
  private String heldLockId;

  private long changeLogLockWaitMillis = Duration.ofMinutes(5).toMillis();
  private long changeLogLockRecheckMillis =
      ClickHouseConfiguration.LOCK_POLL_INTERVAL_MILLIS.getCurrentValue();

  @Override
  public int getPriority() {
    return PRIORITY_DATABASE;
  }

  @Override
  public boolean supports(Database database) {
    return database instanceof ClickHouseDatabase;
  }

  @Override
  public void setDatabase(Database database) {
    this.database = database;
    this.repository = new LockRepository(database, ClusterPolicyFactory.fromConfiguration());
    this.arbiter =
        new OptimisticLockArbiter(
            Duration.ofSeconds(ClickHouseConfiguration.LOCK_TIMEOUT_SECONDS.getCurrentValue()));
  }

  @Override
  public void setChangeLogLockWaitTime(long changeLogLockWaitTime) {
    this.changeLogLockWaitMillis = changeLogLockWaitTime;
  }

  @Override
  public void setChangeLogLockRecheckTime(long changeLogLockRecheckTime) {
    this.changeLogLockRecheckMillis = changeLogLockRecheckTime;
  }

  @Override
  public boolean hasChangeLogLock() {
    return hasLock;
  }

  @Override
  public void init() throws DatabaseException {
    if (lockingDisabled()) {
      return;
    }
    repository.createTableIfMissing();
  }

  @Override
  public boolean acquireLock() throws LockException {
    if (hasLock) {
      return true;
    }
    if (lockingDisabled()) {
      hasLock = true;
      return true;
    }

    try {
      repository.createTableIfMissing();

      String lockId = UUID.randomUUID().toString();
      LockCandidate claim =
          new LockCandidate(
              lockId, true, Instant.now(), repository.nextVersion(), describeThisProcess());

      repository.insert(claim);
      Thread.sleep(changeLogLockRecheckMillis);

      List<LockCandidate> rows = repository.readAll();
      if (arbiter.hasWon(rows, lockId, Instant.now())) {
        hasLock = true;
        heldLockId = lockId;
        warnIfPreempting(rows);
        return true;
      }

      retract(claim);
      return false;

    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new LockException("Interrupted while acquiring the ClickHouse changelog lock", interrupted);
    } catch (DatabaseException failure) {
      throw new LockException(failure);
    }
  }

  @Override
  public void waitForLock() throws LockException {
    long deadline = System.currentTimeMillis() + changeLogLockWaitMillis;

    while (System.currentTimeMillis() < deadline) {
      if (acquireLock()) {
        return;
      }
      try {
        long jitter = ThreadLocalRandom.current().nextLong(changeLogLockRecheckMillis + 1);
        Thread.sleep(changeLogLockRecheckMillis + jitter);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new LockException("Interrupted while waiting for the ClickHouse changelog lock", interrupted);
      }
    }

    throw new LockException(
        "Could not acquire the ClickHouse changelog lock within "
            + changeLogLockWaitMillis
            + " ms. Another migration is in progress, or a previous run left a stale lock;"
            + " inspect DATABASECHANGELOGLOCK and use liquibase releaseLocks if needed.");
  }

  @Override
  public void releaseLock() throws LockException {
    if (!hasLock) {
      return;
    }
    if (lockingDisabled()) {
      hasLock = false;
      return;
    }

    try {
      repository.insert(
          new LockCandidate(
              heldLockId, false, Instant.now(), repository.nextVersion(), describeThisProcess()));
    } catch (DatabaseException failure) {
      throw new LockException(failure);
    } finally {
      hasLock = false;
      heldLockId = null;
    }
  }

  @Override
  public DatabaseChangeLogLock[] listLocks() throws LockException {
    if (lockingDisabled()) {
      return new DatabaseChangeLogLock[0];
    }

    try {
      Optional<LockCandidate> holder = arbiter.currentHolder(repository.readAll(), Instant.now());

      return holder
          .map(
              candidate ->
                  new DatabaseChangeLogLock[] {
                    new DatabaseChangeLogLock(
                        1, Date.from(candidate.grantedAt()), candidate.lockedBy())
                  })
          .orElseGet(() -> new DatabaseChangeLogLock[0]);

    } catch (DatabaseException failure) {
      throw new LockException(failure);
    }
  }

  @Override
  public void forceReleaseLock() throws LockException, DatabaseException {
    repository.createTableIfMissing();

    for (LockCandidate candidate : repository.readAll()) {
      if (candidate.locked()) {
        repository.insert(
            new LockCandidate(
                candidate.lockId(),
                false,
                Instant.now(),
                repository.nextVersion(),
                describeThisProcess()));
      }
    }
    hasLock = false;
    heldLockId = null;
  }

  @Override
  public void reset() {
    hasLock = false;
    heldLockId = null;
  }

  @Override
  public void destroy() throws DatabaseException {
    repository.dropTable();
    reset();
  }

  private void retract(LockCandidate claim) throws DatabaseException {
    repository.insert(
        new LockCandidate(
            claim.lockId(), false, Instant.now(), repository.nextVersion(), claim.lockedBy()));
  }

  private void warnIfPreempting(List<LockCandidate> rows) {
    rows.stream()
        .filter(LockCandidate::locked)
        .filter(candidate -> arbiter.isStale(candidate, Instant.now()))
        .forEach(
            stale ->
                Scope.getCurrentScope()
                    .getLog(ClickHouseLockService.class)
                    .warning(
                        "Preempting a stale ClickHouse changelog lock held by "
                            + stale.lockedBy()
                            + " since "
                            + stale.grantedAt()
                            + ". The previous migration most likely crashed. Verify that the"
                            + " schema is in the state you expect."));
  }

  private boolean lockingDisabled() {
    return !Boolean.TRUE.equals(ClickHouseConfiguration.LOCK_ENABLED.getCurrentValue());
  }

  private static String describeThisProcess() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException unknown) {
      host = "unknown-host";
    }
    return host + " (" + ProcessHandle.current().pid() + ")";
  }
}
```

- [ ] **Step 5: Зарегистрировать через SPI**

Создай `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.lockservice.LockService`:

```
io.github.mnem0c0der.liquibase.ext.clickhouse.lock.ClickHouseLockService
```

- [ ] **Step 6: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseLockServiceTest`
Expected: PASS, 4 теста.

- [ ] **Step 7: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add append-only optimistic changelog lock service

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 15: `ClickHouseChangeLogHistoryService`

**Files:**
- Create: `.../changelog/ClickHouseChangeLogHistoryService.java`
- Create: `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.changelog.ChangeLogHistoryService`
- Test: `.../changelog/ClickHouseChangeLogHistoryServiceTest.java`

**Interfaces:**
- Consumes: `ChangeLogTable` (Task 12), `ClickHouseDatabase` (Task 2).
- Produces: `ClickHouseChangeLogHistoryService extends liquibase.changelog.StandardChangeLogHistoryService`.

> **Что именно переопределяется и почему.** `StandardChangeLogHistoryService` читает таблицу истории обычным SELECT — в ClickHouse это вернёт и старые, и новые версии строк, потому что слияния ReplacingMergeTree происходят когда угодно. Поэтому переопределяется `queryDatabaseChangeLogTable`, добавляющий `FINAL`. Операции `tag` и `clearAllCheckSums` в базовом классе выполняются через `UPDATE`; здесь они переписаны на вставку строки с большей версией.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import java.util.ServiceLoader;
import liquibase.changelog.ChangeLogHistoryService;
import liquibase.changelog.StandardChangeLogHistoryService;
import liquibase.database.core.PostgresDatabase;
import org.junit.jupiter.api.Test;

class ClickHouseChangeLogHistoryServiceTest {

  private final ClickHouseChangeLogHistoryService service =
      new ClickHouseChangeLogHistoryService();

  @Test
  void isRegisteredAsAServiceProvider() {
    assertThat(ServiceLoader.load(ChangeLogHistoryService.class))
        .anyMatch(ClickHouseChangeLogHistoryService.class::isInstance);
  }

  @Test
  void supportsOnlyClickHouse() {
    assertThat(service.supports(new ClickHouseDatabase())).isTrue();
    assertThat(service.supports(new PostgresDatabase())).isFalse();
  }

  @Test
  void outranksTheStandardService() {
    assertThat(service.getPriority())
        .isGreaterThan(new StandardChangeLogHistoryService().getPriority());
  }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -q -pl liquibase-clickhouse test -Dtest=ClickHouseChangeLogHistoryServiceTest`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.changelog;

import io.github.mnem0c0der.liquibase.ext.clickhouse.database.ClickHouseDatabase;
import io.github.mnem0c0der.liquibase.ext.clickhouse.sql.Identifiers;
import java.util.List;
import java.util.Map;
import liquibase.Scope;
import liquibase.changelog.StandardChangeLogHistoryService;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.executor.Executor;
import liquibase.executor.ExecutorService;
import liquibase.statement.core.RawSqlStatement;

/**
 * История changelog поверх ReplacingMergeTree.
 *
 * <p>ClickHouse не обновляет строки на месте, поэтому «изменение» записи — это вставка новой
 * версии, а чтение выполняется с модификатором FINAL, который схлопывает версии.
 */
public class ClickHouseChangeLogHistoryService extends StandardChangeLogHistoryService {

  @Override
  public int getPriority() {
    return PRIORITY_DATABASE;
  }

  @Override
  public boolean supports(Database database) {
    return database instanceof ClickHouseDatabase;
  }

  /**
   * Добавляет FINAL: без него вернутся и устаревшие версии строк, потому что момент слияния
   * ReplacingMergeTree не определён.
   */
  @Override
  public List<Map<String, ?>> queryDatabaseChangeLogTable(Database database)
      throws DatabaseException {
    return executor(database)
        .queryForList(
            new RawSqlStatement(
                "SELECT * FROM " + ChangeLogTable.qualifiedName(database) + " FINAL"
                    + " ORDER BY `ORDEREXECUTED` ASC"));
  }

  /** Проставляет тег последней применённой записи, вставляя её новую версию. */
  @Override
  public void tag(String tagString) throws DatabaseException {
    Database database = getDatabase();
    String table = ChangeLogTable.qualifiedName(database);
    String versionColumn = "`" + ChangeLogTable.ROW_VERSION_COLUMN + "`";

    executor(database)
        .execute(
            new RawSqlStatement(
                "INSERT INTO "
                    + table
                    + " SELECT * EXCEPT ("
                    + versionColumn
                    + ", `TAG`), "
                    + Identifiers.literal(tagString)
                    + " AS `TAG`, toUnixTimestamp64Milli(now64(3)) AS "
                    + versionColumn
                    + " FROM "
                    + table
                    + " FINAL ORDER BY `ORDEREXECUTED` DESC LIMIT 1"));
  }

  /** Сбрасывает контрольные суммы, вставляя новые версии всех строк с пустым MD5SUM. */
  @Override
  public void clearAllCheckSums() throws LiquibaseException {
    Database database = getDatabase();
    String table = ChangeLogTable.qualifiedName(database);
    String versionColumn = "`" + ChangeLogTable.ROW_VERSION_COLUMN + "`";

    executor(database)
        .execute(
            new RawSqlStatement(
                "INSERT INTO "
                    + table
                    + " SELECT * EXCEPT ("
                    + versionColumn
                    + ", `MD5SUM`), NULL AS `MD5SUM`,"
                    + " toUnixTimestamp64Milli(now64(3)) AS "
                    + versionColumn
                    + " FROM "
                    + table
                    + " FINAL"));

    reset();
  }

  private static Executor executor(Database database) {
    return Scope.getCurrentScope()
        .getSingleton(ExecutorService.class)
        .getExecutor("jdbc", database);
  }
}
```

> **Порядок колонок в `SELECT * EXCEPT (...)`.** ClickHouse подставляет колонки в порядке объявления таблицы, а исключённые дописываются в конец выражения. Поэтому в Task 12 `ROWVERSION` объявлена последней, а `TAG` и `MD5SUM` — среди остальных. Если порядок колонок в Task 12 изменится, эти два запроса надо перепроверить интеграционным тестом из Task 16.

- [ ] **Step 4: Зарегистрировать через SPI**

Создай `liquibase-clickhouse/src/main/resources/META-INF/services/liquibase.changelog.ChangeLogHistoryService`:

```
io.github.mnem0c0der.liquibase.ext.clickhouse.changelog.ClickHouseChangeLogHistoryService
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `mvn -q -pl liquibase-clickhouse verify`
Expected: PASS — все юнит-тесты модуля.

- [ ] **Step 6: Коммит**

```bash
git add liquibase-clickhouse/src
git commit -m "feat: add ClickHouse changelog history service

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 16: Интеграционный модуль и полный цикл миграции на реальном ClickHouse

**Files:**
- Modify: `pom.xml` (добавить модуль)
- Create: `liquibase-clickhouse-integration-tests/pom.xml`
- Create: `liquibase-clickhouse-integration-tests/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/it/ClickHouseTestSupport.java`
- Create: `liquibase-clickhouse-integration-tests/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/it/StandaloneMigrationIT.java`
- Create: `liquibase-clickhouse-integration-tests/src/test/resources/changelogs/full-lifecycle.xml`

**Interfaces:**
- Consumes: весь артефакт `liquibase-clickhouse` (Tasks 2–15).
- Produces:
  - `ClickHouseTestSupport.startServer()` → `org.testcontainers.clickhouse.ClickHouseContainer`
  - `ClickHouseTestSupport.connect(ClickHouseContainer container)` → `java.sql.Connection`
  - `ClickHouseTestSupport.openLiquibase(Connection connection, String changelogPath)` → `liquibase.Liquibase` (реализует `AutoCloseable`, поэтому всегда используется в try-with-resources)
  - `ClickHouseTestSupport.queryColumn(Connection connection, String sql)` → `List<String>`
  - Профили Maven `liquibase-4` и `liquibase-5`, переключающие `${liquibase.version}`.

> **Матрица версий реализуется профилями, а не отдельными модулями.** Один и тот же набор тестов прогоняется против Liquibase 4.31.1 и 5.0.4 — именно это доказывает, что артефакт действительно обслуживает и Spring Boot 3, и Spring Boot 4. Дублировать тесты под каждую версию было бы прямым нарушением DRY.
>
> **Имя образа.** Модуль Testcontainers `org.testcontainers:clickhouse` существует только в линейке 1.x, поэтому в parent-POM зафиксирован `testcontainers.version` = `1.21.4`, а не BOM 2.x.

- [ ] **Step 1: Подключить модуль и создать его POM**

В `pom.xml` добавь в `<modules>`:

```xml
<module>liquibase-clickhouse-integration-tests</module>
```

Создай `liquibase-clickhouse-integration-tests/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.github.mnem0c0der</groupId>
    <artifactId>liquibase-clickhouse-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>liquibase-clickhouse-integration-tests</artifactId>
  <name>liquibase-clickhouse integration tests</name>

  <properties>
    <maven.deploy.skip>true</maven.deploy.skip>
    <clickhouse.image>clickhouse/clickhouse-server:26.8</clickhouse.image>
  </properties>

  <dependencies>
    <dependency>
      <groupId>io.github.mnem0c0der</groupId>
      <artifactId>liquibase-clickhouse</artifactId>
      <version>${project.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.liquibase</groupId>
      <artifactId>liquibase-core</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.clickhouse</groupId>
      <artifactId>clickhouse-jdbc</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>clickhouse</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
          <systemPropertyVariables>
            <clickhouse.image>${clickhouse.image}</clickhouse.image>
          </systemPropertyVariables>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>

  <profiles>
    <profile>
      <id>liquibase-4</id>
      <activation><activeByDefault>true</activeByDefault></activation>
      <properties><liquibase.version>4.31.1</liquibase.version></properties>
    </profile>
    <profile>
      <id>liquibase-5</id>
      <properties><liquibase.version>5.0.4</liquibase.version></properties>
    </profile>
  </profiles>
</project>
```

- [ ] **Step 2: Написать помощник для тестов**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.DockerImageName;

/** Общая обвязка интеграционных тестов. */
public final class ClickHouseTestSupport {

  private ClickHouseTestSupport() {}

  public static ClickHouseContainer startServer() {
    String image = System.getProperty("clickhouse.image", "clickhouse/clickhouse-server:26.8");
    ClickHouseContainer container =
        new ClickHouseContainer(DockerImageName.parse(image));
    container.start();
    return container;
  }

  public static Connection connect(ClickHouseContainer container) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("user", container.getUsername());
    properties.setProperty("password", container.getPassword());
    return DriverManager.getConnection(container.getJdbcUrl(), properties);
  }

  public static Liquibase openLiquibase(Connection connection, String changelogPath)
      throws Exception {
    return new Liquibase(
        changelogPath,
        new ClassLoaderResourceAccessor(),
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
  }

  public static List<String> queryColumn(Connection connection, String sql) throws Exception {
    List<String> values = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        values.add(resultSet.getString(1));
      }
    }
    return values;
  }
}
```

- [ ] **Step 3: Написать changelog и интеграционный тест**

Создай `src/test/resources/changelogs/full-lifecycle.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

  <changeSet id="1-create-events" author="liquibase-clickhouse">
    <createTable tableName="events">
      <column name="id" type="bigint">
        <constraints nullable="false"/>
      </column>
      <column name="name" type="varchar(100)"/>
      <column name="created" type="timestamp"/>
    </createTable>
  </changeSet>

  <changeSet id="2-add-country" author="liquibase-clickhouse">
    <addColumn tableName="events">
      <column name="country" type="varchar(2)"/>
    </addColumn>
  </changeSet>

  <changeSet id="3-seed" author="liquibase-clickhouse">
    <insert tableName="events">
      <column name="id" valueNumeric="1"/>
      <column name="name" value="launch"/>
      <column name="country" value="KZ"/>
    </insert>
  </changeSet>

  <changeSet id="4-rename-column" author="liquibase-clickhouse">
    <renameColumn tableName="events" oldColumnName="country" newColumnName="country_code"/>
  </changeSet>

  <changeSet id="5-index" author="liquibase-clickhouse">
    <createIndex tableName="events" indexName="idx_events_name">
      <column name="name"/>
    </createIndex>
  </changeSet>

  <changeSet id="6-tag" author="liquibase-clickhouse">
    <tagDatabase tag="v1"/>
  </changeSet>
</databaseChangeLog>
```

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;

class StandaloneMigrationIT {

  private static ClickHouseContainer clickhouse;

  @BeforeAll
  static void startClickHouse() {
    clickhouse = ClickHouseTestSupport.startServer();
  }

  @AfterAll
  static void stopClickHouse() {
    if (clickhouse != null) {
      clickhouse.stop();
    }
  }

  @Test
  void appliesAFullChangelogAndRecordsEveryChangeSet() throws Exception {
    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      try (Liquibase liquibase =
          ClickHouseTestSupport.openLiquibase(connection, "changelogs/full-lifecycle.xml")) {
        liquibase.update(new Contexts(), new LabelExpression());
      }

      List<String> applied =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT ID FROM DATABASECHANGELOG FINAL ORDER BY ORDEREXECUTED");

      assertThat(applied)
          .containsExactly(
              "1-create-events", "2-add-country", "3-seed", "4-rename-column", "5-index", "6-tag");
    }
  }

  @Test
  void createsTheTableWithAMergeTreeEngine() throws Exception {
    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      try (Liquibase liquibase =
          ClickHouseTestSupport.openLiquibase(connection, "changelogs/full-lifecycle.xml")) {
        liquibase.update(new Contexts(), new LabelExpression());
      }

      List<String> engines =
          ClickHouseTestSupport.queryColumn(
              connection,
              "SELECT engine FROM system.tables WHERE name = 'events' AND database = currentDatabase()");

      assertThat(engines).containsExactly("MergeTree");
    }
  }

  @Test
  void keepsNullableColumnsNullableAndNotNullColumnsPlain() throws Exception {
    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      try (Liquibase liquibase =
          ClickHouseTestSupport.openLiquibase(connection, "changelogs/full-lifecycle.xml")) {
        liquibase.update(new Contexts(), new LabelExpression());
      }

      List<String> types =
          ClickHouseTestSupport.queryColumn(
              connection,
              "SELECT type FROM system.columns WHERE table = 'events'"
                  + " AND database = currentDatabase() AND name IN ('id', 'name')"
                  + " ORDER BY name");

      assertThat(types).containsExactly("Nullable(String)", "Int64");
    }
  }

  @Test
  void isIdempotentWhenRunTwice() throws Exception {
    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      for (int run = 0; run < 2; run++) {
        try (Liquibase liquibase =
            ClickHouseTestSupport.openLiquibase(connection, "changelogs/full-lifecycle.xml")) {
          liquibase.update(new Contexts(), new LabelExpression());
        }
      }

      List<String> applied =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT ID FROM DATABASECHANGELOG FINAL ORDER BY ORDEREXECUTED");

      assertThat(applied).hasSize(6).doesNotHaveDuplicates();
    }
  }

  @Test
  void recordsTheTagOnTheLastChangeSet() throws Exception {
    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      try (Liquibase liquibase =
          ClickHouseTestSupport.openLiquibase(connection, "changelogs/full-lifecycle.xml")) {
        liquibase.update(new Contexts(), new LabelExpression());
      }

      List<String> tags =
          ClickHouseTestSupport.queryColumn(
              connection,
              "SELECT TAG FROM DATABASECHANGELOG FINAL WHERE TAG IS NOT NULL");

      assertThat(tags).contains("v1");
    }
  }
}
```

- [ ] **Step 4: Прогнать интеграционные тесты на обеих версиях Liquibase**

Run:
```bash
mvn -q clean verify -Pliquibase-4 && mvn -q clean verify -Pliquibase-5
```
Expected: PASS оба раза. Docker должен быть запущен.

Если тесты падают на Liquibase 5, но проходят на 4 — значит, в коде использован API, отсутствующий в одной из версий. Это ровно тот отказ, который матрица и должна ловить.

- [ ] **Step 5: Коммит**

```bash
git add pom.xml liquibase-clickhouse-integration-tests
git commit -m "test: add integration tests across the Liquibase 4 and 5 matrix

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 17: Ключевой тест — параллельные миграции

**Files:**
- Create: `liquibase-clickhouse-integration-tests/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/it/ConcurrentMigrationIT.java`

**Interfaces:**
- Consumes: `ClickHouseTestSupport` (Task 16), `ClickHouseLockService` (Task 14).
- Produces: ничего для последующих задач.

> **Это главный тест проекта.** Вся конструкция с append-only-блокировкой существует ради одного свойства: при одновременном запуске нескольких миграций changeset применяется ровно один раз. Если этот тест не проходит, расширение непригодно к использованию независимо от того, насколько хорош остальной код.
>
> **Тест обязан быть детерминированным.** Потоки синхронизируются на общем барьере, чтобы гонка действительно происходила, а не разносилась во времени случайно.

- [ ] **Step 1: Написать тест**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;

class ConcurrentMigrationIT {

  private static final int MIGRATORS = 8;

  private static ClickHouseContainer clickhouse;

  @BeforeAll
  static void startClickHouse() {
    clickhouse = ClickHouseTestSupport.startServer();
  }

  @AfterAll
  static void stopClickHouse() {
    if (clickhouse != null) {
      clickhouse.stop();
    }
  }

  @Test
  void appliesEveryChangeSetExactlyOnceUnderConcurrentMigrations() throws Exception {
    CyclicBarrier startLine = new CyclicBarrier(MIGRATORS);
    ExecutorService pool = Executors.newFixedThreadPool(MIGRATORS);

    try {
      List<Callable<Void>> migrators =
          IntStream.range(0, MIGRATORS)
              .<Callable<Void>>mapToObj(
                  index ->
                      () -> {
                        startLine.await(30, TimeUnit.SECONDS);
                        try (Connection connection = ClickHouseTestSupport.connect(clickhouse);
                            Liquibase liquibase =
                                ClickHouseTestSupport.openLiquibase(
                                    connection, "changelogs/full-lifecycle.xml")) {
                          liquibase.update(new Contexts(), new LabelExpression());
                        }
                        return null;
                      })
              .toList();

      for (Future<Void> result : pool.invokeAll(migrators, 5, TimeUnit.MINUTES)) {
        result.get();
      }
    } finally {
      pool.shutdownNow();
    }

    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      List<String> applied =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT ID FROM DATABASECHANGELOG FINAL ORDER BY ORDEREXECUTED");

      assertThat(applied)
          .as("every changeset must be recorded exactly once")
          .hasSize(6)
          .doesNotHaveDuplicates();

      List<String> seededRows =
          ClickHouseTestSupport.queryColumn(connection, "SELECT count() FROM events");

      assertThat(seededRows)
          .as("the seeding changeset must have inserted exactly one row")
          .containsExactly("1");
    }
  }

  @Test
  void releasesTheLockSoLaterMigrationsAreNotBlocked() throws Exception {
    try (Connection connection = ClickHouseTestSupport.connect(clickhouse)) {
      try (Liquibase liquibase =
          ClickHouseTestSupport.openLiquibase(connection, "changelogs/full-lifecycle.xml")) {
        liquibase.update(new Contexts(), new LabelExpression());
      }

      List<String> heldLocks =
          ClickHouseTestSupport.queryColumn(
              connection, "SELECT count() FROM DATABASECHANGELOGLOCK FINAL WHERE LOCKED = 1");

      assertThat(heldLocks).containsExactly("0");
    }
  }
}
```

- [ ] **Step 2: Прогнать тест на обеих версиях Liquibase**

Run:
```bash
mvn -q clean verify -Pliquibase-4 -Dit.test=ConcurrentMigrationIT && mvn -q clean verify -Pliquibase-5 -Dit.test=ConcurrentMigrationIT
```
Expected: PASS оба раза.

Если в `DATABASECHANGELOG` появились дубликаты — протокол захвата блокировки неверен. НЕ ослабляй проверку и НЕ добавляй повторных попыток в тест: разбирайся в `OptimisticLockArbiter` и `ClickHouseLockService`.

- [ ] **Step 3: Прогнать тест десять раз подряд, чтобы исключить случайное прохождение**

Run:
```bash
for i in $(seq 1 10); do mvn -q verify -Dit.test=ConcurrentMigrationIT || { echo "FAILED on run $i"; break; }; done
```
Expected: десять успешных прогонов подряд.

- [ ] **Step 4: Коммит**

```bash
git add liquibase-clickhouse-integration-tests
git commit -m "test: verify changesets apply exactly once under concurrent migrations

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 18: Кластер — Docker Compose и интеграционные тесты `ON CLUSTER`

**Files:**
- Create: `docker/standalone/docker-compose.yml`
- Create: `docker/cluster/docker-compose.yml`
- Create: `docker/cluster/config/common.xml`
- Create: `docker/cluster/config/macros/s1r1.xml`, `s1r2.xml`, `s2r1.xml`, `s2r2.xml`
- Create: `docker/cluster/config/keeper.xml`
- Create: `liquibase-clickhouse-integration-tests/src/test/java/io/github/mnem0c0der/liquibase/ext/clickhouse/it/ClusterMigrationIT.java`

**Interfaces:**
- Consumes: `OnClusterPolicy` (Task 4), `ClickHouseTestSupport` (Task 16).
- Produces: compose-файлы, пригодные и для CI, и для ручного запуска разработчиком.

> **Зачем реплики, а не просто два шарда.** `ON CLUSTER` без репликации проверил бы только рассылку DDL. Настоящий риск в другом: `ReplicatedMergeTree` требует согласованного пути в Keeper и уникального имени реплики, и ошибка в шаблоне пути проявляется лишь при наличии второй реплики. Топология 2 × 2 — минимальная, на которой эта ошибка вообще может проявиться.

- [ ] **Step 1: Создать compose для standalone (ручные запуски)**

`docker/standalone/docker-compose.yml`:

```yaml
services:
  clickhouse:
    image: clickhouse/clickhouse-server:26.8
    container_name: liquibase-clickhouse-standalone
    ports:
      - "8123:8123"
      - "9000:9000"
    environment:
      CLICKHOUSE_DB: analytics
      CLICKHOUSE_USER: liquibase
      CLICKHOUSE_PASSWORD: liquibase
      CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT: 1
    ulimits:
      nofile:
        soft: 262144
        hard: 262144
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://127.0.0.1:8123/ping"]
      interval: 5s
      timeout: 3s
      retries: 20
```

- [ ] **Step 2: Создать конфигурацию кластера**

`docker/cluster/config/common.xml` — топология и адрес Keeper, одинаковы для всех четырёх узлов:

```xml
<clickhouse>
  <remote_servers>
    <analytics_cluster>
      <shard>
        <internal_replication>true</internal_replication>
        <replica><host>ch-s1r1</host><port>9000</port></replica>
        <replica><host>ch-s1r2</host><port>9000</port></replica>
      </shard>
      <shard>
        <internal_replication>true</internal_replication>
        <replica><host>ch-s2r1</host><port>9000</port></replica>
        <replica><host>ch-s2r2</host><port>9000</port></replica>
      </shard>
    </analytics_cluster>
  </remote_servers>

  <zookeeper>
    <node><host>ch-keeper</host><port>9181</port></node>
  </zookeeper>
</clickhouse>
```

`docker/cluster/config/macros/s1r1.xml` — макросы, подставляемые в шаблон пути Keeper:

```xml
<clickhouse>
  <macros>
    <shard>1</shard>
    <replica>s1r1</replica>
  </macros>
</clickhouse>
```

Создай ещё три файла по тому же образцу, меняя только значения:

| Файл | `<shard>` | `<replica>` |
|---|---|---|
| `macros/s1r2.xml` | `1` | `s1r2` |
| `macros/s2r1.xml` | `2` | `s2r1` |
| `macros/s2r2.xml` | `2` | `s2r2` |

`docker/cluster/config/keeper.xml`:

```xml
<clickhouse>
  <keeper_server>
    <tcp_port>9181</tcp_port>
    <server_id>1</server_id>
    <log_storage_path>/var/lib/clickhouse/coordination/log</log_storage_path>
    <snapshot_storage_path>/var/lib/clickhouse/coordination/snapshots</snapshot_storage_path>
    <coordination_settings>
      <operation_timeout_ms>10000</operation_timeout_ms>
      <session_timeout_ms>30000</session_timeout_ms>
    </coordination_settings>
    <raft_configuration>
      <server>
        <id>1</id>
        <hostname>ch-keeper</hostname>
        <port>9234</port>
      </server>
    </raft_configuration>
  </keeper_server>
</clickhouse>
```

- [ ] **Step 3: Создать compose кластера**

`docker/cluster/docker-compose.yml`:

```yaml
services:
  ch-keeper:
    image: clickhouse/clickhouse-keeper:26.8
    container_name: ch-keeper
    volumes:
      - ./config/keeper.xml:/etc/clickhouse-keeper/keeper_config.xml:ro
    healthcheck:
      test: ["CMD-SHELL", "clickhouse-keeper-client -q ruok | grep -q imok"]
      interval: 5s
      timeout: 3s
      retries: 30

  ch-s1r1:
    image: clickhouse/clickhouse-server:26.8
    container_name: ch-s1r1
    depends_on:
      ch-keeper:
        condition: service_healthy
    ports:
      - "8123"
    volumes:
      - ./config/common.xml:/etc/clickhouse-server/config.d/common.xml:ro
      - ./config/macros/s1r1.xml:/etc/clickhouse-server/config.d/macros.xml:ro
    environment:
      CLICKHOUSE_DB: analytics
      CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT: 1
    ulimits:
      nofile: {soft: 262144, hard: 262144}
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://127.0.0.1:8123/ping"]
      interval: 5s
      timeout: 3s
      retries: 30

  ch-s1r2:
    image: clickhouse/clickhouse-server:26.8
    container_name: ch-s1r2
    depends_on:
      ch-keeper:
        condition: service_healthy
    ports:
      - "8123"
    volumes:
      - ./config/common.xml:/etc/clickhouse-server/config.d/common.xml:ro
      - ./config/macros/s1r2.xml:/etc/clickhouse-server/config.d/macros.xml:ro
    environment:
      CLICKHOUSE_DB: analytics
      CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT: 1
    ulimits:
      nofile: {soft: 262144, hard: 262144}
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://127.0.0.1:8123/ping"]
      interval: 5s
      timeout: 3s
      retries: 30

  ch-s2r1:
    image: clickhouse/clickhouse-server:26.8
    container_name: ch-s2r1
    depends_on:
      ch-keeper:
        condition: service_healthy
    ports:
      - "8123"
    volumes:
      - ./config/common.xml:/etc/clickhouse-server/config.d/common.xml:ro
      - ./config/macros/s2r1.xml:/etc/clickhouse-server/config.d/macros.xml:ro
    environment:
      CLICKHOUSE_DB: analytics
      CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT: 1
    ulimits:
      nofile: {soft: 262144, hard: 262144}
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://127.0.0.1:8123/ping"]
      interval: 5s
      timeout: 3s
      retries: 30

  ch-s2r2:
    image: clickhouse/clickhouse-server:26.8
    container_name: ch-s2r2
    depends_on:
      ch-keeper:
        condition: service_healthy
    ports:
      - "8123"
    volumes:
      - ./config/common.xml:/etc/clickhouse-server/config.d/common.xml:ro
      - ./config/macros/s2r2.xml:/etc/clickhouse-server/config.d/macros.xml:ro
    environment:
      CLICKHOUSE_DB: analytics
      CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT: 1
    ulimits:
      nofile: {soft: 262144, hard: 262144}
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://127.0.0.1:8123/ping"]
      interval: 5s
      timeout: 3s
      retries: 30
```

- [ ] **Step 4: Проверить кластер вручную**

Run:
```bash
docker compose -f docker/cluster/docker-compose.yml up -d --wait && \
docker exec ch-s1r1 clickhouse-client -q "SELECT host_name, shard_num, replica_num FROM system.clusters WHERE cluster = 'analytics_cluster' ORDER BY shard_num, replica_num"
```
Expected: четыре строки — `ch-s1r1 1 1`, `ch-s1r2 1 2`, `ch-s2r1 2 1`, `ch-s2r2 2 2`.

Если строк нет — `common.xml` не подхватился; проверь, что он смонтирован в `/etc/clickhouse-server/config.d/`.

- [ ] **Step 5: Написать интеграционный тест кластера**

```java
package io.github.mnem0c0der.liquibase.ext.clickhouse.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mnem0c0der.liquibase.ext.clickhouse.config.ClickHouseConfiguration;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class ClusterMigrationIT {

  private static final String CLUSTER = "analytics_cluster";

  @SuppressWarnings("resource")
  private static final DockerComposeContainer<?> CLUSTER_STACK =
      new DockerComposeContainer<>(new File("../docker/cluster/docker-compose.yml"))
          .withLocalCompose(true)
          .withExposedService(
              "ch-s1r1", 8123, Wait.forHttp("/ping").withStartupTimeout(Duration.ofMinutes(3)))
          .withExposedService(
              "ch-s1r2", 8123, Wait.forHttp("/ping").withStartupTimeout(Duration.ofMinutes(3)));

  @BeforeAll
  static void startCluster() {
    CLUSTER_STACK.start();
  }

  @AfterAll
  static void stopCluster() {
    CLUSTER_STACK.stop();
  }

  private static Connection connectTo(String service) throws Exception {
    String url =
        "jdbc:clickhouse://"
            + CLUSTER_STACK.getServiceHost(service, 8123)
            + ":"
            + CLUSTER_STACK.getServicePort(service, 8123)
            + "/analytics";

    Properties properties = new Properties();
    properties.setProperty("user", "default");
    properties.setProperty("password", "");
    return DriverManager.getConnection(url, properties);
  }

  @Test
  void replicatesTheSchemaToEveryReplicaWhenAClusterIsConfigured() throws Exception {
    Scope.child(
        Map.of(ClickHouseConfiguration.CLUSTER.getKey(), CLUSTER),
        () -> {
          try (Connection connection = connectTo("ch-s1r1");
              Liquibase liquibase =
                  ClickHouseTestSupport.openLiquibase(connection, "changelogs/full-lifecycle.xml")) {
            liquibase.update(new Contexts(), new LabelExpression());
          }
        });

    try (Connection secondReplica = connectTo("ch-s1r2")) {
      List<String> engines =
          ClickHouseTestSupport.queryColumn(
              secondReplica,
              "SELECT engine FROM system.tables WHERE name = 'events' AND database = 'analytics'");

      assertThat(engines)
          .as("ON CLUSTER DDL must have reached the second replica")
          .containsExactly("ReplicatedMergeTree");

      List<String> applied =
          ClickHouseTestSupport.queryColumn(
              secondReplica,
              "SELECT ID FROM analytics.DATABASECHANGELOG FINAL ORDER BY ORDEREXECUTED");

      assertThat(applied).hasSize(6).doesNotHaveDuplicates();
    }
  }
}
```

- [ ] **Step 6: Прогнать кластерные тесты на обеих версиях Liquibase**

Run:
```bash
mvn -q clean verify -Pliquibase-4 -Dit.test=ClusterMigrationIT && mvn -q clean verify -Pliquibase-5 -Dit.test=ClusterMigrationIT
```
Expected: PASS оба раза.

- [ ] **Step 7: Коммит**

```bash
git add docker liquibase-clickhouse-integration-tests
git commit -m "test: add clustered ClickHouse compose stack and ON CLUSTER integration test

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 19: Демо-приложения Spring Boot 3 и Spring Boot 4

**Files:**
- Modify: `pom.xml` (добавить оба модуля)
- Create: `examples/spring-boot-3-demo/pom.xml`, `.../DemoApplication.java`, `.../application.yml`, `.../db/changelog/db.changelog-master.xml`, `.../DemoApplicationIT.java`
- Create: `examples/spring-boot-4-demo/` — те же файлы

**Interfaces:**
- Consumes: артефакт `liquibase-clickhouse`.
- Produces: доказательство того, что штатный autoconfig `spring.liquibase` работает без единой строки кода поверх обеих линеек Boot.

> **Дублирование между двумя демо — сознательное решение, а не недосмотр.** Файлы обоих модулей совпадают
> побайтово, кроме версии BOM — и именно это и есть проверяемое утверждение: расширению не нужно ни одного
> различия между Spring Boot 3 и 4. Вынесение общей части в третий модуль скрыло бы именно то, что демо
> призваны показать, и лишило бы их ценности самодостаточного примера для копирования. Решение
> подтверждено владельцем репозитория. Находку «DRY-нарушение между демо-модулями» заводить не нужно.
>
> **Обе демо в одной задаче намеренно.** Они отличаются только версией BOM Spring Boot; рецензировать их по отдельности бессмысленно — они принимаются или отклоняются вместе.
>
> **Подключение через `dependencyManagement`, а не `<parent>`.** У модулей уже есть родитель — parent-POM проекта. Импорт BOM `spring-boot-dependencies` даёт то же управление версиями и позволяет двум линейкам Boot сосуществовать в одном реакторе.

- [ ] **Step 1: Подключить модули**

В корневом `pom.xml` добавь в `<modules>`:

```xml
<module>examples/spring-boot-3-demo</module>
<module>examples/spring-boot-4-demo</module>
```

и в `<properties>`:

```xml
<spring-boot-3.version>3.5.16</spring-boot-3.version>
<spring-boot-4.version>4.1.1</spring-boot-4.version>
```

- [ ] **Step 2: Создать POM демо для Spring Boot 3**

`examples/spring-boot-3-demo/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.github.mnem0c0der</groupId>
    <artifactId>liquibase-clickhouse-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>

  <artifactId>spring-boot-3-demo</artifactId>
  <name>liquibase-clickhouse Spring Boot 3 demo</name>

  <properties>
    <maven.deploy.skip>true</maven.deploy.skip>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot-3.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.liquibase</groupId>
      <artifactId>liquibase-core</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.mnem0c0der</groupId>
      <artifactId>liquibase-clickhouse</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>com.clickhouse</groupId>
      <artifactId>clickhouse-jdbc</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>clickhouse</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: Создать приложение и конфигурацию**

`examples/spring-boot-3-demo/src/main/java/io/github/mnem0c0der/liquibase/clickhouse/demo/DemoApplication.java`:

```java
package io.github.mnem0c0der.liquibase.clickhouse.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Демонстрация того, что расширение не требует никакого кода: достаточно положить артефакт на
 * classpath, и штатная автоконфигурация Liquibase из Spring Boot работает с ClickHouse.
 */
@SpringBootApplication
public class DemoApplication {

  public static void main(String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
```

`examples/spring-boot-3-demo/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:clickhouse://localhost:8123/analytics
    username: liquibase
    password: liquibase
    driver-class-name: com.clickhouse.jdbc.ClickHouseDriver
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml
```

`examples/spring-boot-3-demo/src/main/resources/db/changelog/db.changelog-master.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

  <changeSet id="1-create-orders" author="demo">
    <createTable tableName="orders">
      <column name="id" type="bigint">
        <constraints nullable="false"/>
      </column>
      <column name="customer" type="varchar(120)"/>
      <column name="amount" type="decimal(18,2)"/>
      <column name="created" type="timestamp"/>
    </createTable>
  </changeSet>
</databaseChangeLog>
```

- [ ] **Step 4: Написать e2e-тест демо**

`examples/spring-boot-3-demo/src/test/java/io/github/mnem0c0der/liquibase/clickhouse/demo/DemoApplicationIT.java`:

```java
package io.github.mnem0c0der.liquibase.clickhouse.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class DemoApplicationIT {

  @Container
  static final ClickHouseContainer CLICKHOUSE =
      new ClickHouseContainer("clickhouse/clickhouse-server:26.8");

  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", CLICKHOUSE::getJdbcUrl);
    registry.add("spring.datasource.username", CLICKHOUSE::getUsername);
    registry.add("spring.datasource.password", CLICKHOUSE::getPassword);
  }

  @Test
  void springBootAutoconfigurationRunsTheChangelogAgainstClickHouse() {
    Long tables =
        jdbcTemplate.queryForObject(
            "SELECT count() FROM system.tables WHERE name = 'orders'"
                + " AND database = currentDatabase()",
            Long.class);

    assertThat(tables).isEqualTo(1L);
  }

  @Test
  void theChangelogIsRecorded() {
    Long applied =
        jdbcTemplate.queryForObject(
            "SELECT count() FROM DATABASECHANGELOG FINAL", Long.class);

    assertThat(applied).isEqualTo(1L);
  }
}
```

- [ ] **Step 5: Создать демо для Spring Boot 4**

Скопируй каталог `examples/spring-boot-3-demo` в `examples/spring-boot-4-demo` и внеси ровно три изменения:

1. В `pom.xml` — `<artifactId>spring-boot-4-demo</artifactId>`, `<name>liquibase-clickhouse Spring Boot 4 demo</name>` и версия BOM `${spring-boot-4.version}` вместо `${spring-boot-3.version}`.
2. В `pom.xml` — добавь явное переопределение версии Liquibase сразу после блока `dependencyManagement`, чтобы демо использовало ту же 5.0.4, что и матрица CI:

```xml
  <properties>
    <maven.deploy.skip>true</maven.deploy.skip>
    <liquibase.version>5.0.4</liquibase.version>
  </properties>
```

3. Больше ничего. Java-код, `application.yml`, changelog и тест идентичны — в этом и состоит проверяемое утверждение: расширение не требует различий между Boot 3 и Boot 4.

- [ ] **Step 6: Собрать и прогнать обе демо**

Run:
```bash
mvn -q clean verify -pl examples/spring-boot-3-demo,examples/spring-boot-4-demo -am
```
Expected: PASS. В логах Boot 3 должна фигурировать Liquibase 4.31.1, в логах Boot 4 — 5.0.4.

Проверить фактические версии:
```bash
mvn -q -pl examples/spring-boot-3-demo dependency:list | grep liquibase-core
mvn -q -pl examples/spring-boot-4-demo dependency:list | grep liquibase-core
```
Expected: `4.31.1` и `5.0.4` соответственно. Если обе одинаковые — переопределение версии не сработало.

- [ ] **Step 7: Коммит**

```bash
git add pom.xml examples
git commit -m "test: add Spring Boot 3 and Spring Boot 4 demo applications

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 20: CI — матрица версий и топологий

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/codeql.yml`
- Create: `.github/dependabot.yml`

**Interfaces:**
- Consumes: профили `liquibase-4` / `liquibase-5` (Task 16), compose-файлы (Task 18).
- Produces: ничего для последующих задач.

> **Матрица — это и есть доказательство совместимости.** Утверждение «один артефакт обслуживает Spring Boot 3 и 4» держится не на рассуждении о `throws`, а на том, что весь интеграционный набор зелёный на обеих версиях Liquibase. Если матрицу сузить, утверждение перестанет быть проверяемым.
>
> **Кластерные тесты вынесены в отдельный job.** Они поднимают пять контейнеров и идут заметно дольше; смешивать их с быстрой матрицей значило бы замедлить обратную связь по каждому push.

- [ ] **Step 1: Создать основной workflow**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  unit:
    name: Unit tests and static checks
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Verify formatting and run unit tests
        run: mvn -B -ntp clean verify -pl liquibase-clickhouse
      - name: Assert the published artifact has no runtime dependencies
        run: |
          mvn -B -ntp -pl liquibase-clickhouse dependency:list \
            -DincludeScope=runtime -DoutputFile=runtime-deps.txt
          count=$(grep -cE '^\s+\S+:\S+:' liquibase-clickhouse/runtime-deps.txt || true)
          if [ "$count" != "0" ]; then
            echo "Expected zero runtime dependencies, found $count:"
            cat liquibase-clickhouse/runtime-deps.txt
            exit 1
          fi

  integration:
    name: Liquibase ${{ matrix.liquibase }} / ClickHouse ${{ matrix.clickhouse }}
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        liquibase: ['4', '5']
        clickhouse: ['26.8', '25.8']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Run integration tests
        run: >
          mvn -B -ntp clean verify
          -pl liquibase-clickhouse,liquibase-clickhouse-integration-tests -am
          -Pliquibase-${{ matrix.liquibase }}
          -Dclickhouse.image=clickhouse/clickhouse-server:${{ matrix.clickhouse }}
          -Dit.test='!ClusterMigrationIT'

  cluster:
    name: Clustered ClickHouse (Liquibase ${{ matrix.liquibase }})
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        liquibase: ['4', '5']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Run clustered integration tests
        run: >
          mvn -B -ntp clean verify
          -pl liquibase-clickhouse,liquibase-clickhouse-integration-tests -am
          -Pliquibase-${{ matrix.liquibase }}
          -Dit.test=ClusterMigrationIT

  demos:
    name: Spring Boot ${{ matrix.boot }} demo
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        boot: ['3', '4']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Build and test the demo
        run: mvn -B -ntp clean verify -pl examples/spring-boot-${{ matrix.boot }}-demo -am
```

- [ ] **Step 2: Создать CodeQL workflow**

`.github/workflows/codeql.yml`:

```yaml
name: CodeQL

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  schedule:
    - cron: '0 6 * * 1'

jobs:
  analyze:
    runs-on: ubuntu-latest
    permissions:
      security-events: write
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - uses: github/codeql-action/init@v3
        with:
          languages: java
      - name: Build
        run: mvn -B -ntp clean compile -pl liquibase-clickhouse
      - uses: github/codeql-action/analyze@v3
```

- [ ] **Step 3: Настроить Dependabot**

`.github/dependabot.yml`:

```yaml
version: 2
updates:
  - package-ecosystem: maven
    directory: "/"
    schedule:
      interval: weekly
    open-pull-requests-limit: 10

  - package-ecosystem: github-actions
    directory: "/"
    schedule:
      interval: weekly
```

- [ ] **Step 4: Проверить workflow локально насколько возможно**

Run:
```bash
mvn -B -ntp clean verify -pl liquibase-clickhouse,liquibase-clickhouse-integration-tests -am -Pliquibase-5 -Dit.test='!ClusterMigrationIT'
```
Expected: PASS — та же команда, что выполняется в CI.

- [ ] **Step 5: Коммит**

```bash
git add .github
git commit -m "ci: add version matrix, cluster, demo and CodeQL workflows

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 21: Релизная обвязка и документация

**Files:**
- Modify: `pom.xml` (профиль `release`)
- Create: `.github/workflows/release.yml`
- Create: `RELEASING.md`
- Create: `CONTRIBUTING.md`
- Create: `CHANGELOG.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: всё предыдущее.
- Produces: репозиторий в состоянии «остаётся запушить тег».

> **Публикация в Maven Central НЕ выполняется в рамках этой задачи.** Это необратимая outward-facing операция, требующая GPG-ключа и токенов Central, принадлежащих владельцу репозитория. Задача завершается тем, что всё готово, а `RELEASING.md` описывает точные шаги.
>
> **GPG-подпись живёт в профиле `release`.** Иначе обычный `mvn verify` у любого контрибьютора требовал бы ключа.

- [ ] **Step 1: Добавить профиль `release` в корневой `pom.xml`**

```xml
  <profiles>
    <profile>
      <id>release</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-source-plugin</artifactId>
            <executions>
              <execution>
                <id>attach-sources</id>
                <goals><goal>jar-no-fork</goal></goals>
              </execution>
            </executions>
          </plugin>

          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-javadoc-plugin</artifactId>
            <executions>
              <execution>
                <id>attach-javadocs</id>
                <goals><goal>jar</goal></goals>
              </execution>
            </executions>
          </plugin>

          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-gpg-plugin</artifactId>
            <executions>
              <execution>
                <id>sign-artifacts</id>
                <phase>verify</phase>
                <goals><goal>sign</goal></goals>
                <configuration>
                  <gpgArguments>
                    <arg>--pinentry-mode</arg>
                    <arg>loopback</arg>
                  </gpgArguments>
                </configuration>
              </execution>
            </executions>
          </plugin>

          <plugin>
            <groupId>org.sonatype.central</groupId>
            <artifactId>central-publishing-maven-plugin</artifactId>
            <version>${central-publishing-plugin.version}</version>
            <extensions>true</extensions>
            <configuration>
              <publishingServerId>central</publishingServerId>
              <autoPublish>false</autoPublish>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
```

`autoPublish` намеренно оставлен в `false`: артефакт попадает в Central Portal как черновик, и финальное подтверждение владелец делает вручную. Это последний рубеж перед необратимой публикацией.

- [ ] **Step 2: Создать release workflow**

`.github/workflows/release.yml`:

```yaml
name: Release

on:
  push:
    tags: ['v*']

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
          server-id: central
          server-username: CENTRAL_TOKEN_USERNAME
          server-password: CENTRAL_TOKEN_PASSWORD
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: GPG_PASSPHRASE

      - name: Verify the tag matches the project version
        run: |
          tag="${GITHUB_REF_NAME#v}"
          version=$(mvn -B -ntp -q -DforceStdout help:evaluate -Dexpression=project.version)
          if [ "$tag" != "$version" ]; then
            echo "Tag $tag does not match project version $version"
            exit 1
          fi

      - name: Build, test and publish
        run: mvn -B -ntp clean deploy -Prelease -pl liquibase-clickhouse -am -DskipTests=false
        env:
          CENTRAL_TOKEN_USERNAME: ${{ secrets.CENTRAL_TOKEN_USERNAME }}
          CENTRAL_TOKEN_PASSWORD: ${{ secrets.CENTRAL_TOKEN_PASSWORD }}
          GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

- [ ] **Step 3: Написать `RELEASING.md`**

```markdown
# Releasing

Publishing to Maven Central is performed by the repository owner. It is
irreversible: a released version can never be modified or removed.

## One-time setup

1. Claim the `io.github.mnem0c0der` namespace at https://central.sonatype.com
   by adding the verification TXT record or repository it asks for.
2. Generate a publishing token (Account -> Generate User Token).
3. Create a GPG key and publish the public half:

   ```bash
   gpg --gen-key
   gpg --list-secret-keys --keyid-format=long
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>
   ```

4. Add four repository secrets in GitHub:

   | Secret | Value |
   |---|---|
   | `CENTRAL_TOKEN_USERNAME` | token username from step 2 |
   | `CENTRAL_TOKEN_PASSWORD` | token password from step 2 |
   | `GPG_PRIVATE_KEY` | full armored private key from step 3 |
   | `GPG_PASSPHRASE` | passphrase for that key |

## Cutting a release

1. Make sure `main` is green in CI.
2. Set the release version and update `CHANGELOG.md`:

   ```bash
   mvn -B versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
   git commit -am "chore: release 1.0.0"
   ```

3. Tag and push. The tag must match the project version exactly.

   ```bash
   git tag v1.0.0
   git push origin main v1.0.0
   ```

4. The Release workflow builds, signs and uploads the artifact as a draft
   deployment in the Central Portal.
5. Review the deployment at https://central.sonatype.com and press Publish.
   **Nothing reaches Maven Central until this manual step.**
6. Bump to the next snapshot:

   ```bash
   mvn -B versions:set -DnewVersion=1.1.0-SNAPSHOT -DgenerateBackupPoms=false
   git commit -am "chore: back to snapshot"
   git push
   ```

## Verifying a release locally before tagging

```bash
mvn -B clean verify -Prelease -pl liquibase-clickhouse -am
```

This signs the artifacts without uploading anything.
```

- [ ] **Step 4: Переписать `README.md`**

README должен отвечать на четыре вопроса читателя, пришедшего из Maven Central: как подключить, как настроить, что поддерживается и что нет.

```markdown
# liquibase-clickhouse

Liquibase extension for ClickHouse. Supports standalone and clustered
deployments, and works with both Liquibase 4.31+ and Liquibase 5.x — which
means both Spring Boot 3 and Spring Boot 4.

## Installation

```xml
<dependency>
  <groupId>io.github.mnem0c0der</groupId>
  <artifactId>liquibase-clickhouse</artifactId>
  <version>1.0.0</version>
</dependency>
```

The extension declares no runtime dependencies. Bring your own
`liquibase-core` and `com.clickhouse:clickhouse-jdbc` — any 4.31+ or 5.x
Liquibase will do.

With Spring Boot, adding the dependency is the whole setup: the standard
`spring.liquibase` autoconfiguration picks the extension up from the classpath.

## Configuration

Every key can be set in `liquibase.properties`, as a `-D` system property, or
as an environment variable (`LIQUIBASE_CLICKHOUSE_CLUSTER` and so on).

| Key | Default | Meaning |
|---|---|---|
| `liquibase.clickhouse.cluster` | unset | Cluster name. When set, DDL runs `ON CLUSTER` and MergeTree engines become Replicated. Leave unset for standalone. |
| `liquibase.clickhouse.tableEngine` | `MergeTree` | Default engine for `createTable`. |
| `liquibase.clickhouse.zookeeperPath` | `/clickhouse/tables/{shard}/{database}/{table}` | Keeper path template for Replicated engines. |
| `liquibase.clickhouse.replicaName` | `{replica}` | Replica name template. |
| `liquibase.clickhouse.mutationsSync` | `2` | `mutations_sync` applied to `update` and `delete`. |
| `liquibase.clickhouse.lock.timeoutSeconds` | `300` | Age at which a held lock is treated as stale and may be preempted. |
| `liquibase.clickhouse.lock.pollIntervalMillis` | `500` | Delay between lock attempts. |
| `liquibase.clickhouse.lock.enabled` | `true` | Set to `false` only when a single migration process can ever run. |

## How it differs from a transactional database

ClickHouse has no transactions and no atomic `UPDATE ... WHERE`. Two
consequences are worth knowing about.

**Changelog locking is optimistic.** `DATABASECHANGELOGLOCK` is an append-only
`ReplacingMergeTree` table. Acquiring the lock inserts a claim, re-reads the
table and picks a deterministic winner; losers back off and retry. A lock older
than `lock.timeoutSeconds` is preempted with a warning, so a crashed migration
cannot deadlock the database forever.

**`update` and `delete` become mutations.** They are issued as
`ALTER TABLE ... UPDATE/DELETE` with `mutations_sync = 2`, so Liquibase does not
move on before the change is actually visible. Mutations are expensive; prefer
insert-only changelogs for large tables.

## Unsupported features

These raise an error rather than generating SQL that ClickHouse would reject or,
worse, silently accept with a different meaning.

| Change | Why | What to do instead |
|---|---|---|
| `addForeignKeyConstraint` | ClickHouse has no foreign keys | Enforce integrity in the application, or denormalise |
| `addPrimaryKey` | The sorting key is fixed at creation | Declare it in `createTable`, or create a new table and copy |
| `addAutoIncrement` | No sequences or identity columns | Generate ids in the application, or `DEFAULT generateUUIDv4()` |
| `addUniqueConstraint` | No uniqueness enforcement | `ReplacingMergeTree`, or deduplicate before insert |
| `createSequence` | No sequences | As above |
| `createIndex` with `unique="true"` | No unique indexes | As above |

`createIndex` creates a data-skipping index (`TYPE minmax GRANULARITY 1`) and
materialises it over existing parts.

## Nullability

ClickHouse columns are `NOT NULL` by default — the opposite of the SQL standard.
A column that is not explicitly `NOT NULL` in your changelog is created as
`Nullable(T)`, so a changelog written for PostgreSQL keeps its meaning.

## Development

```bash
mvn clean verify                                   # unit tests
mvn clean verify -Pliquibase-5                     # integration tests on Liquibase 5
docker compose -f docker/standalone/docker-compose.yml up -d
docker compose -f docker/cluster/docker-compose.yml up -d --wait
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
```

- [ ] **Step 5: Создать `CONTRIBUTING.md` и `CHANGELOG.md`**

`CONTRIBUTING.md`:

```markdown
# Contributing

## Requirements

- JDK 21
- Maven 3.9+
- Docker (for integration tests)

## Before opening a pull request

```bash
mvn spotless:apply
mvn clean verify
mvn clean verify -Pliquibase-5
```

## Rules that CI enforces

- The published `liquibase-clickhouse` module must have **zero** runtime
  dependencies. `liquibase-core` and `clickhouse-jdbc` are `provided`.
- Code compiles against Liquibase 4.31.1. Using API that only exists in 5.x
  breaks Spring Boot 3 support.
- Overrides of `LockService` methods declare `throws DatabaseException`, not
  `LiquibaseException`. The narrower clause is valid in both Liquibase lines.
- Anything ClickHouse cannot do raises `UnsupportedClickHouseFeatureException`
  with a concrete alternative. Never emit SQL that only looks correct.
```

`CHANGELOG.md`:

```markdown
# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- ClickHouse `Database` implementation, data type mapping and DDL/DML
  generators for the standard Liquibase change types.
- Optional clustered mode: `ON CLUSTER` DDL and `Replicated*` engines behind
  `liquibase.clickhouse.cluster`.
- Append-only optimistic changelog locking with stale-lock preemption.
- `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK` backed by `ReplacingMergeTree`.
- Verified support for Liquibase 4.31.x and 5.0.x from a single artifact,
  covering Spring Boot 3 and Spring Boot 4.
```

- [ ] **Step 6: Проверить подпись артефактов локально**

> Этот шаг требует настроенного GPG-ключа. Если ключа ещё нет, пропусти шаг и вернись к нему после выполнения одноразовой настройки из `RELEASING.md`.

Run: `mvn -B clean verify -Prelease -pl liquibase-clickhouse -am`
Expected: PASS, и в `liquibase-clickhouse/target/` появились файлы `*.jar`, `*-sources.jar`, `*-javadoc.jar` и `*.asc` для каждого из них.

- [ ] **Step 7: Финальная проверка всего проекта**

Run:
```bash
mvn -B clean verify -Pliquibase-4 && mvn -B clean verify -Pliquibase-5
```
Expected: PASS оба раза, включая демо и все интеграционные тесты.

- [ ] **Step 8: Коммит**

```bash
git add pom.xml .github README.md RELEASING.md CONTRIBUTING.md CHANGELOG.md
git commit -m "docs: add release tooling, README, contributing guide and changelog

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Что остаётся за пределами этого плана

Фазы 3 и 4 из спеки вынесены в отдельные планы, каждый из которых даёт самостоятельно релизуемый результат:

- **План 2 (v1.1):** собственный XSD-namespace — `clickhouse:createTable` с полным контролем движка, материализованные представления, словари, операции с партициями, `optimizeTable`, rollback для всех собственных changeType.
- **План 3 (v1.2):** snapshot-генераторы поверх `system.tables`, `system.columns`, `system.data_skipping_indices`, включающие `generateChangeLog` и `diff`.

Оба плана опираются на абстракции, созданные здесь (`ClusterPolicy`, `ClickHouseDdlBuilder`, `AbstractClickHouseSqlGenerator`), и не требуют их изменения.
