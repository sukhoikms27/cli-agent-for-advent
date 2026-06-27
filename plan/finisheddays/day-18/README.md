# День 18 — Планировщик и фоновые задачи (README / общий план)

> Периодический сбор погоды через Open-Meteo + MCP-tools (`collect_weather` /
> `get_current_weather` / `get_weather_summary`) + серверный `WeatherScheduler` (background loop).
> Агент 24/7 со сводкой. Корневой контекст: [`00-task.md`](./00-task.md).

## Карта файлов

### Батч 1 (этот набор — контекст + рефактор + хранилище + клиент + tools)

| # | Файл | Тип | Содержание |
|---|---|---|---|
| 00 | [`00-task.md`](./00-task.md) | контекст | Задание курса, контекст Day 17, scope in/out, развилки, маппинг «задание→реализация» |
| — | [`README.md`](./README.md) | индекс/план | Этот файл: навигация, порядок выполнения, чекпойнты, граф зависимостей, структура модуля, env |
| 01 | [`01-refactor-monolith.md`](./01-refactor-monolith.md) | реализация | Разбивка `GitHubMcpServer.kt` → `McpServerApp.kt` + `McpServerFactory.kt` + пакеты `util/`, `tools/`. Чистая расширяемая архитектура, **0 регрессий get_repo** |
| 02 | [`02-weather-storage.md`](./02-weather-storage.md) | реализация | `WeatherSnapshot` (`@Serializable`) + `WeatherStore` (JSON, atomic write, XDG) + `util/DataPaths` |
| 03 | [`03-weather-client.md`](./03-weather-client.md) | реализация | `WeatherClient` (Open-Meteo Ktor): geocode→lat/lon, forecast→snapshot. Валидация, CancellationException rethrow |
| 04 | [`04-weather-tools.md`](./04-weather-tools.md) | реализация | 3 MCP-tools (`collect_weather`, `get_current_weather`, `get_weather_summary`) + регистрация в factory |

### Батч 2 (следующий набор — scheduler + wiring + тесты + верификация + демо)

| # | Файл | Тип | Содержание |
|---|---|---|---|
| 05 | `05-weather-scheduler.md` | реализация | `WeatherScheduler` background coroutine: periodic collect→store. Env `CLI_AGENT_WEATHER_INTERVAL_SECONDS`/`_CITIES`. Stderr-логи, cancellation hygiene |
| 06 | `06-env-wiring.md` | реализация | Wiring в `main()`/`buildServer()`: создать client/store/scheduler, передать в factory, `.start()` scheduler. **Клиентский модуль не трогается** |
| 07 | `07-tests-infrastructure.md` | реализация | test-deps в `mcp-server/build.gradle.kts` (JUnit5, mockk, ktor-client-mock, coroutines-test), `tasks.withType<Test>{ useJUnitPlatform() }` |
| 08 | `08-tests-weather-storage.md` | тесты | `WeatherStoreTest` (tmp dir, @BeforeEach): append/loadRange/latest, atomic write, range filter |
| 09 | `09-tests-weather-client.md` | тесты | `WeatherClientTest` (Ktor MockEngine): geocode/forecast parse, невалидный город→null, HTTP-ошибка→null |
| 10 | `10-tests-scheduler.md` | тесты | `WeatherSchedulerTest` (`runTest` + fake client/store): N сборок за виртуальное время, respects cancellation |
| 11 | `11-tests-summary.md` | тесты+обзор | `WeatherSummaryTest` (агрегат `aggregate()` — чистая функция), сборная таблица всех тестов дня, регрессионные гарантии |
| 12 | `12-verification.md` | верификация | `./gradlew :mcp-server:test build shadowJar`, raw `tools/list` = 4 tools, демо-чеклист |
| 13 | `13-demo-scenario.md` | демо | Сценарии A–E «периодической работы локально»: scheduler копит снапшоты → agent `get_weather_summary`/`collect_weather` через REPL |

### Переработка — agent-driven расписание (R0–R5)

> После исходной реализации выяснилось: расписание должно **задавать сам агент через MCP-tools**, а не
> оператор через env (по подсказке куратора + требование пользователя). Подробности —
> [`R0-redesign-note.md`](./R0-redesign-note.md).

| # | Файл | Тип | Содержание |
|---|---|---|---|
| R0 | `R0-redesign-note.md` | обоснование | Почему env-driven → agent-driven; pull-модель (почему не callback); ответы на 4 развилки |
| R1 | `R1-scheduler-dynamic.md` | реализация | `WeatherScheduler` v2: динамический реестр подписок, subscribe/unsubscribe/list, per-city jobs с delay-first |
| R2 | `R2-subscription-tools.md` | реализация | 3 новых tools (`subscribe_weather`/`list_weather_subscriptions`/`unsubscribe_weather`) + расширенные сигнатуры |
| R3 | `R3-remove-env-wiring.md` | реализация | Убрать `CLI_AGENT_WEATHER_*` env, пустой scheduler, scope-проброс |
| R4 | `R4-update-docs.md` | docs | Обновить 00/README/05/06/12/13 под agent-driven модель |
| R5 | `R5-update-tests.md` | тесты+verify | Переписать `WeatherSchedulerTest` (8 тестов) + финальная верификация |

**Итог redesign'а:** 4 tools → **7 tools**; env `CLI_AGENT_WEATHER_*` удалён; расписание полностью
управляется агентом. Задачи 05/06 помечены как superseded (историческая справка).

## Порядок выполнения и чекпойнты

Каждый task-файл содержит раздел `## Критерии готовности` с конкретной проверочной командой.
Между задачами — чекпойнт: **не переходить дальше, пока предыдущий не green.**

| После задачи | Чекпойнт |
|---|---|
| **01** (рефактор) | `./gradlew :mcp-server:installDist build` green; raw `tools/list` → **1 tool** `get_repo`; behavior Day 17 не сломан |
| **02** (storage) | `./gradlew :mcp-server:build` green (storage компилируется) |
| **03** (client) | `./gradlew :mcp-server:build` green (client компилируется) |
| **04** (tools) | `./gradlew :mcp-server:build` green; raw `tools/list` → **4 tools** |
| **05** (scheduler) | `./gradlew :mcp-server:test --tests "*WeatherSchedulerTest"` green (батч 2) |
| **06** (wiring) | `./gradlew :mcp-server:shadowJar` green; http-запуск → stderr scheduler стартует, `weather/moscow.json` растёт (батч 2) |
| **07** (test-infra) | `./gradlew :mcp-server:test` запускается (0 тестов ok, infra готова) (батч 2) |
| **08–11** (тесты) | `./gradlew :mcp-server:test` — все новые green, 0 регрессий (батч 2) |
| **12** (верификация) | Полный `./gradlew build` (оба модуля) green; демо-чеклист отмечен (батч 2) |
| **13** (демо) | Сценарии A–E дают ожидаемый результат (часть требует LLM API key) (батч 2) |

### Граф зависимостей

```
01 (рефактор) ──┬─→ 02 (storage) ──┐
                └─→ 03 (client) ───┤
                                   ├─→ 04 (tools) ──→ 05 (scheduler) ──→ 06 (wiring)
                                   │
07 (test-infra) ──────────────────►├─→ 08←02, 09←03, 10←05, 11←04
                                   └─→ 12 (verify) ← ALL ──→ 13 (demo)
```

- 01 — фундамент для всего (разбивка + фабрика как точка расширения).
- 02 и 03 независимы между собой (могут делаться параллельно после 01).
- 04 зависит от 02+03 (tools используют storage и client).
- 05 зависит от 04 (scheduler вызывает тот же collect-путь, что и `collect_weather`).
- 06 — финальная сборка wiring'а (зависит от 04+05).
- 07 не зависит от main-кода; можно ставить в любой момент батча 2.
- 08–11 покрывают 02/03/05/04 соответственно.
- 12/13 — после всех.

## Итоговая структура модуля (после задач 01–06)

```
mcp-server/src/main/kotlin/com/cliagent/mcp/server/
├── McpServerApp.kt          # main() + transport selection (бывший GitHubMcpServer.main)
├── McpServerFactory.kt      # buildServer(...) — точка расширения (регистрирует ВСЕ tools)
├── util/
│   ├── Args.kt              # stringArg/numberArg/toolError/NAME_REGEX (бывшие helpers)
│   └── DataPaths.kt         # XDG-пути для данных сервера (weather dir) — NEW (задача 02)
├── tools/
│   ├── GitHubTools.kt       # get_repo (вынесен из мономолита, без изменений) — задача 01
│   └── WeatherTools.kt      # collect_weather / get_current_weather / get_weather_summary — NEW (04)
└── weather/                 # NEW (02–05)
    ├── WeatherClient.kt     # Open-Meteo (geocode + forecast) — задача 03
    ├── WeatherSnapshot.kt   # @Serializable data class — задача 02
    ├── WeatherStore.kt      # JSON persist (atomic write, XDG) — задача 02
    └── WeatherScheduler.kt  # background coroutine — задача 05 (батч 2)
```

**Сервер становится мульти-tool.** Добавление tool в будущем = новый файл в `tools/` + одна строка
в `McpServerFactory.buildServer(...)`. Архитектура готовит место для Day 19 (композиция нескольких
tools) и любых будущих интеграций.

## Конвенции кода (AGENTS.md, соблюдать во всех задачах)

- **Persistence:** JSON, один файл на город (`{snapshots:[...]}`), **atomic write** (temp + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`), XDG-пути.
- **Schema evolution:** поля `@Serializable` с **defaults**, никогда не удалять — старый JSON грузится.
- **Единый Json:** `Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; coerceInputValues = true }`.
- **Coroutines:** `CancellationException` **никогда не глотать** — всегда re-throw перед generic `catch (e: Exception)`.
- **stdio-режим:** stdout несёт **только JSON-RPC** — никаких `println` (ломает протокол); логи → stderr.
- **Безопасность (Week 04):** read-only, least-privilege, allowlist-валидация входов перед подстановкой в URL/filename (prompt/path-injection defense).
- **Tool-ошибки** → `CallToolResult(content=listOf(TextContent(msg)), isError=true)`, **не** exception — видны LLM для самокоррекции. Протокольные/транспортные ошибки → throw `McpException`.

## Справочник env-переменных

### Существующие (Day 17, не меняются)
| Env | Default | Назначение |
|---|---|---|
| `CLI_AGENT_MCP_MODE` | `stdio` | `http` \| `stdio` — выбор транспорта сервера |
| `CLI_AGENT_MCP_HOST` | `0.0.0.0` | bind-адрес (http-режим) |
| `CLI_AGENT_MCP_PORT` | `8080` | порт (http-режим) |
| `CLI_AGENT_MCP_PATH` | `/mcp` | эндпоинт (http-режим) |
| `CLI_AGENT_MCP_TOKEN` | — | bearer для auth /mcp (обязателен на VPS) |
| `CLI_AGENT_GITHUB_TOKEN` | — | PAT для `get_repo` (на сервере) |

### Погодные (Day 18 — agent-driven, НЕ env!)
> **После redesign (R0–R5) расписание погоды НЕ задаётся env-переменными.** Сервер стартует с пустым
> scheduler'ом; агент регистрирует periodic-сбор через MCP-tools (`subscribe_weather`,
> `list_weather_subscriptions`, `unsubscribe_weather`). Pull-модель: подписка → фоновый сбор 24/7 →
> результат через `get_weather_summary`. См. [`R0-redesign-note.md`](./R0-redesign-note.md).
>
> Ранние планы `05`/`06` описывали env `CLI_AGENT_WEATHER_CITIES`/`_INTERVAL_SECONDS` — **они удалены**
> (superseded). Конфигурация — только через tools.

### Клиентские (Day 17, не меняются — tools авто-подхватываются)
| Env | Назначение |
|---|---|
| `CLI_AGENT_MCP_URL` | remote-сервер (приоритет над `CLI_AGENT_MCP_COMMAND`) |
| `CLI_AGENT_MCP_COMMAND` | stdio-subprocess сервера |
| `CLI_AGENT_MCP_TOKEN` | bearer для подключения клиента |
| `CLI_AGENT_API_KEY` | z.ai (LLM) |

> **Важно:** погодные tools **не требуют** никаких клиентских изменений. `McpTool.toToolDefinition()`
> маппит любую MCP-schema в OpenAI tools, а `ContextAwareAgent.runToolLoop` уже поддерживает
> несколько tools и их chaining (`MAX_TOOL_ROUNDS=4`). day17-results явно anticipated «Day 18 —
> 2+ tools, LLM выбирает/чейнит». После redesign (R2) — **7 tools**.

## Чек-лист приёмки (соответствие заданию курса)

- [x] «сохранять данные» — `WeatherStore` JSON + atomic write (задача 02, тест 08)
- [x] «периодический сбор данных» — `WeatherScheduler` v2 реестр подписок (R1, тест R5) + `collect_weather` (04)
- [x] «выполняться по расписанию» — **agent-driven**: `subscribe_weather` (R2) + per-city jobs (R1), по подсказке куратора
- [x] «агрегированный результат» / «регулярный summary» — `get_weather_summary` avg/min/max (04, тест 11)
- [x] «24/7 агент со сводкой» — http-режим + systemd (06/R3, демо 13)
- [x] 0 регрессий Day 17 (`get_repo` работает идентично после рефактора — задача 01)
- [x] **Agent-driven расписание** — расписание задаёт агент через tools, а не оператор через env (R0–R5)
