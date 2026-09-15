# liquibase-clickhouse — дизайн расширения

**Дата:** 2026-09-15
**Статус:** утверждён, готов к планированию реализации
**Артефакт:** `io.github.mnem0c0der:liquibase-clickhouse`
**Лицензия:** Apache License 2.0

## 1. Цель

Liquibase-расширение для ClickHouse, пригодное для публикации в Maven Central и
использования сторонними командами. Существующие аналоги (MEDIARITHMICS,
genestack) остановились на Liquibase 4.x; ниша под линейку 5.x свободна.

Расширение должно одинаково работать под Spring Boot 3 и Spring Boot 4, то есть
под Liquibase 4.31.x и 5.0.x одновременно.

## 2. Требования

### Функциональные

- Полный набор возможностей: стандартные changeType, кластерный и standalone
  режим, собственный XSD-namespace для ClickHouse-специфики, snapshot
  (`generateChangeLog` / `diff`), rollback для собственных changeType.
- Кластер поддерживается опционально и только по явной конфигурации. Без
  заданного `cluster` расширение работает в чистом standalone-режиме.
  Автоопределение кластера по `system.clusters` сознательно отвергнуто: риск
  неожиданного `ON CLUSTER` в проде выше пользы.
- Совместимость: один артефакт обслуживает Liquibase 4.31.x и 5.0.x.

### Нефункциональные

- Java 21 как язык и таргет сборки.
- Ни одной known-CVE зависимости.
- Публикуемый артефакт не имеет runtime-зависимостей.
- ООП/SOLID/DRY как явный критерий приёмки дизайна, не как лозунг.

## 3. Зафиксированные версии

Проверены на отсутствие уязвимостей через OSV (`api.osv.dev`) на 2026-09-15 —
все чистые.

| Компонент | Версия | Комментарий |
|---|---|---|
| Liquibase (compile) | 4.31.1 | нижняя поддерживаемая, scope `provided` |
| Liquibase (runtime matrix) | 4.31.1, 5.0.4 | обе проверяются в CI |
| clickhouse-jdbc | 0.10.0 | scope `provided` |
| Spring Boot 3 | 3.5.16 | тянет Liquibase 4.31.1 |
| Spring Boot 4 | 4.1.1 | тянет Liquibase 5.0.3; демо переопределяет на 5.0.4 |
| Testcontainers | 1.21.4 | |
| JUnit | 5.14.4 | не 6.x — во избежание конфликта с BOM Spring Boot 3 |
| AssertJ | 3.27.7 | 4.0.0-M1 — milestone, не берём |
| ClickHouse (образ) | 26.8 и 25.8 | |
| central-publishing-maven-plugin | 0.11.0 | |
| spotless-maven-plugin | 3.10.2 | |
| jacoco-maven-plugin | 0.8.15 | |

## 4. Ключевое исследование: совместимость 4.31.1 ↔ 5.0.4

Сравнение публичного API (`javap` по 722 классам в пакетах, релевантных
расширению) показало:

- **Ни один класс не удалён** в 5.0.4 относительно 4.31.1.
- Изменения в точках расширения (`AbstractJdbcDatabase`, `Database`,
  `StandardChangeLogHistoryService`, генераторы SQL) — **чисто аддитивные**.
- Единственное ломающее изменение: `LockService.init()`, `destroy()` и
  `forceReleaseLock()` расширили `throws DatabaseException` →
  `throws LiquibaseException`.

### Следствие для дизайна

Сужение `throws` при переопределении метода легально в Java, а в дескриптор
метода на уровне JVM `throws` не входит вообще. Поэтому класс, скомпилированный
против 4.31.1 с узким `throws DatabaseException`, остаётся корректным
переопределением и линкуется без ошибок под 5.0.x.

**Решение:** компилировать против Liquibase 4.31.1 (`provided`), выпускать один
артефакт, прогонять весь интеграционный набор на матрице 4.31.1 × 5.0.4.

Отвергнутые альтернативы:

- Два артефакта под каждую линейку — дублирование кода и релизного потока ради
  одной строки `throws`.
- Сборка только под 5.0.4 — вынуждает пользователей Spring Boot 3 переопределять
  `liquibase.version`, ломая «стоковую» конфигурацию Boot 3.

## 5. Структура репозитория

```
liquibase-clickhouse/                        parent pom
├── liquibase-clickhouse/                    единственный публикуемый модуль
├── liquibase-clickhouse-integration-tests/  Testcontainers, skip deploy
├── examples/spring-boot-3-demo/             Boot 3.5.16, skip deploy
├── examples/spring-boot-4-demo/             Boot 4.1.1,  skip deploy
├── docker/docker-compose.yml                standalone + кластер
└── docs/
```

Демо-модули подключают Spring Boot через `dependencyManagement`-импорт
`spring-boot-dependencies` BOM, а не через `<parent>`: две разные линейки Boot в
одном реакторе иначе не уживаются.

Java-пакет — `io.github.mnem0c0der.liquibase.ext.clickhouse`, а не
`liquibase.ext.clickhouse`. Причины: отсутствие split-package с `liquibase-core`,
корректный `Automatic-Module-Name`, соответствие groupId.

`liquibase-core` и `clickhouse-jdbc` — scope `provided`. Публикуемый jar не имеет
транзитивных зависимостей: он не может занести CVE в чужой проект и не навязывает
версию драйвера.

## 6. Архитектура

### 6.1 Database SPI

`ClickHouseDatabase extends AbstractJdbcDatabase`, регистрация через
`META-INF/services/liquibase.database.Database`.

Честно объявляет ограничения движка: `supportsDDLInTransaction() = false`, нет
sequences, нет tablespaces, нет FK и PK-constraint. База ClickHouse маппится на
Liquibase-catalog.

### 6.2 Конфигурация

`ClickHouseConfiguration` на базе `AutoloadedConfigurations` /
`ConfigurationDefinition`. Одно объявление ключа автоматически даёт
`liquibase.properties`, системное свойство `-D`, переменную окружения и
CLI-флаг — DRY на уровне конфигурации.

Ключи (префикс `liquibase.clickhouse.`):

| Ключ | Назначение | Умолчание |
|---|---|---|
| `cluster` | имя кластера; пусто → standalone | пусто |
| `tableEngine` | движок по умолчанию | `MergeTree`, в кластере `ReplicatedMergeTree` |
| `zookeeperPath` | путь для Replicated-движков | `/clickhouse/tables/{shard}/{database}/{table}` |
| `replicaName` | имя реплики | `{replica}` |
| `mutationsSync` | синхронность `ALTER ... UPDATE/DELETE` | `2` |
| `lock.timeoutSeconds` | TTL лока, после которого он считается протухшим | `300` |
| `lock.pollIntervalMillis` | пауза между попытками захвата | `500` |
| `lock.enabled` | аварийное отключение блокировки | `true` |

### 6.3 ClusterPolicy

Интерфейс с двумя реализациями: `StandaloneClusterPolicy` и `OnClusterPolicy`.
Отвечает ровно за два решения:

- добавлять ли `ON CLUSTER "<name>"` к DDL;
- переписывать ли `MergeTree` → `ReplicatedMergeTree(zookeeperPath, replicaName)`.

Генераторы SQL про кластер не знают. `ON CLUSTER` подставляется в одном месте —
это и есть точка соблюдения DRY и OCP: добавление третьей топологии не требует
правок в генераторах.

### 6.4 Слой генерации SQL

`AbstractClickHouseSqlGenerator<T extends SqlStatement>` — общая база: приоритет,
`supports()` (проверка `database instanceof ClickHouseDatabase`), доступ к
конфигурации и `ClusterPolicy`, общий `ClickHouseDdlBuilder` — fluent-сборка
`CREATE TABLE ... ENGINE ... ORDER BY ... PARTITION BY ... TTL ... SETTINGS`.

Далее около 25 узких генераторов, по одному на `SqlStatement` (SRP). Особые
случаи:

- `update` / `delete` → `ALTER TABLE ... UPDATE/DELETE` с `mutations_sync`;
- `createIndex` → data-skipping `ALTER TABLE ... ADD INDEX`;
- `createTable` без явного `ORDER BY` → `ORDER BY` из PK, иначе `tuple()`.

Неподдерживаемые в ClickHouse конструкции (FK, PK-constraint, autoincrement) не
превращаются в «почти правильный» SQL, а бросают
`UnsupportedClickHouseFeatureException` с текстом, объясняющим ClickHouse-альтернативу.
Молчаливая генерация ломающегося SQL — худший из возможных вариантов для
пользователя расширения.

### 6.5 Типы данных

Классы `LiquibaseDataType` с `@DataTypeInfo`, регистрация через
`META-INF/services/liquibase.datatype.LiquibaseDataType`.

| Liquibase / JDBC | ClickHouse |
|---|---|
| `VARCHAR`, `CLOB`, `BLOB` | `String` |
| `TINYINT` / `SMALLINT` / `INT` / `BIGINT` | `Int8` / `Int16` / `Int32` / `Int64` |
| `BOOLEAN` | `Bool` |
| `FLOAT` / `DOUBLE` | `Float32` / `Float64` |
| `DECIMAL(p,s)` | `Decimal(p,s)` |
| `DATE` | `Date32` |
| `TIMESTAMP` | `DateTime64(3)` |
| `UUID` | `UUID` |

В ClickHouse колонка по умолчанию NOT NULL — противоположно SQL-стандарту.
Поэтому nullable-колонка оборачивается в `Nullable(T)`.

### 6.6 Lock-сервис

Самая рискованная часть. В ClickHouse нет транзакций и нет атомарного
`UPDATE ... WHERE` с числом затронутых строк, а `StandardLockService` Liquibase
построен именно на этом.

Выбрана стратегия **append-only + оптимистичный захват**. Отвергнуты: мутации
`ALTER ... UPDATE` (тяжёлые, переписывают парты, и атомарности всё равно не дают)
и лок через ClickHouse Keeper (через JDBC не создать эфемерную ноду; требует
отдельного ZK-клиента и адресов Keeper, которые клиенту не всегда доступны).

Таблица `DATABASECHANGELOGLOCK` на `(Replicated)ReplacingMergeTree` с колонкой
версии.

Разрез на три класса, по одной ответственности:

- **`LockRepository`** — только SQL и IO.
- **`OptimisticLockArbiter`** — чистая функция: из набора строк-претендентов
  выбирает детерминированного победителя и определяет протухший лок. Ноль
  зависимостей от БД.
- **`ClickHouseLockService implements LockService`** — тонкий адаптер под SPI
  Liquibase.

Протокол захвата:

1. `INSERT` строки со своим уникальным `lockId`.
2. Пауза `lock.pollIntervalMillis`.
3. Перечитывание состояния через `argMax`.
4. `OptimisticLockArbiter` выбирает победителя по детерминированному правилу.
5. Победитель продолжает; проигравший откатывает свою строку и ретраит с
   jitter-backoff.

Лок старше `lock.timeoutSeconds` считается протухшим и вытесняется с громким
предупреждением в лог. Это защита от вечного deadlock после аварийного завершения
процесса миграции.

Вынос арбитра в чистую функцию — ключевое решение: конкурентная логика
покрывается детерминированными unit-тестами, а не проверяется «запустим и
посмотрим».

Методы SPI объявляются с узким `throws DatabaseException` (см. раздел 4).

### 6.7 История changelog

`ClickHouseChangeLogHistoryService`. Таблица `DATABASECHANGELOG` на
`ReplacingMergeTree`, чтение схлопывает версии через `FINAL` / `argMax`.

Операции, которые в обычной СУБД выполняются через `UPDATE` (`tag()`,
`clearAllCheckSums()`, `removeFromHistory()`), переписаны на вставку новой версии
строки.

### 6.8 Snapshot

Генераторы `SnapshotGenerator` поверх `system.tables`, `system.columns`,
`system.data_skipping_indices` для `Catalog`, `Table`, `Column`, `Index`, `View`.
Включают `generateChangeLog` и `diff`.

### 6.9 Собственный XSD-namespace

Гибридный подход: стандартные changeType работают «из коробки» с разумными
умолчаниями, поэтому существующие changelog переносятся почти без правок. Для
полного контроля и ClickHouse-специфики — собственный namespace.

- `clickhouse:createTable` — полный контроль над `ENGINE`, `ORDER BY`,
  `PARTITION BY`, `PRIMARY KEY`, `SAMPLE BY`, `TTL`, `SETTINGS`.
- ClickHouse-only changeType: материализованные представления, словари,
  операции с партициями (`attach` / `detach` / `drop`), `optimizeTable`.
- У каждого — `createInverses()` для rollback.

Регистрация через `META-INF/services/liquibase.change.Change` и
`META-INF/services/liquibase.parser.NamespaceDetails`.

Ограничение, принятое осознанно: Liquibase не позволяет добавлять атрибуты к
стандартным changeType, поэтому расширенная семантика доступна только через собственный
`clickhouse:createTable`, а не как дополнительные атрибуты стандартного
`createTable`.

## 7. Тестирование

### Unit (без БД)

- Генераторы SQL — сравнение с эталонной строкой.
- Маппинг типов, включая обёртку `Nullable(T)`.
- Разрешение конфигурации из разных источников.
- `OptimisticLockArbiter` — гонки нескольких претендентов, вытеснение протухшего
  лока, идемпотентность освобождения.

### Integration (Testcontainers)

Два профиля:

- standalone на `clickhouse/clickhouse-server:26.8` и на `25.8`;
- кластер: 2 шарда × 2 реплики + ClickHouse Keeper через compose.

**Обязательный ключевой тест:** N параллельных `Liquibase.update()` по одному
changelog → ровно один applier, ноль дублей в `DATABASECHANGELOG`. Это главный
тест проекта.

### End-to-end

Оба демо-приложения запускаются в `@SpringBootTest` с Testcontainers и
проверяют, что штатный autoconfig `spring.liquibase` проходит на реальном
ClickHouse — под Boot 3 и под Boot 4 соответственно.

## 8. Сборка и CI

Сборка: `maven.compiler.release=21`, `maven-enforcer-plugin` фиксирует минимум
Java и Maven, `spotless` (google-java-format), `jacoco` с порогом покрытия,
`Automatic-Module-Name` в манифесте.

CI (GitHub Actions), матрица:

| ось | значения |
|---|---|
| Liquibase | 4.31.1, 5.0.4 |
| топология | standalone, cluster |
| ClickHouse | 26.8, 25.8 |

Отдельные job: сборка обоих демо, CodeQL, Dependabot на зависимости и на actions.

## 9. Релиз

`central-publishing-maven-plugin` 0.11.0, полные POM-метаданные (Apache 2.0, SCM,
developers), `sources` и `javadoc` jar, GPG-подпись из GitHub Secrets, workflow по
тегу `v*`, `RELEASING.md` с точными шагами.

**Границы:** сама публикация в Maven Central выполняется владельцем репозитория.
Это необратимая outward-facing операция, требующая GPG-ключа и токенов Central.
Работа доводится до состояния «остаётся запушить тег».

## 10. Фазы реализации

Порядок выбран так, чтобы после каждой фазы существовал рабочий протестированный
артефакт, а самое багоопасное шло последним.

1. **Ядро** — скелет проекта, Database SPI, типы, lock-сервис, changelog-сервис,
   основные DDL/DML генераторы, standalone IT. Уже полностью рабочее расширение.
2. **Кластер** — `ClusterPolicy`, `ON CLUSTER`, `Replicated*`-движки, кластерные IT.
3. **Собственный XSD-namespace** — `clickhouse:createTable`, материализованные
   представления, словари, партиции, `optimizeTable`, rollback.
4. **Snapshot** — `generateChangeLog` и `diff`.
5. **Демо Boot 3/4, документация, релизная обвязка.**

Фазы 3 и 4 несут основную долю риска по багам. Если они начнут раздувать сроки,
их можно перенести в v1.1 без потери ценности фаз 1–2.

## 11. Вне области задачи

- Автоопределение кластера по `system.clusters`.
- Отдельный Spring Boot starter — в Spring Boot достаточно положить jar на
  classpath, отдельная сущность не оправдана.
- Выполнение самой публикации в Maven Central.
