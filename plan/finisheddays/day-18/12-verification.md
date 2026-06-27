# Задача 12. Верификация дня 18

## Цель

Финальная проверка: автоматические тесты + сборка + ручной smoke (raw JSON-RPC, scheduler копит
данные). Подтверждение, что все требования задания закрыты, регрессий Day 17 нет.

## Зависимости

Все задачи 01–11. Образцы верификации — `plan/finisheddays/day-17/day17-results.md` (raw JSON-RPC,
`./gradlew` команды).

## Автоматизация

```bash
# 1. Тесты модуля :mcp-server (~27 новых)
./gradlew :mcp-server:test

# 2. Полная сборка обоих модулей (main + test, compile + test)
./gradlew build

# 3. Fat-jar для http-деплоя (Day 17-vps)
./gradlew :mcp-server:shadowJar
# → mcp-server/build/libs/mcp-server-0.1.0-all.jar

# 4. Дистрибутив для stdio-режима
./gradlew :mcp-server:installDist
```

**Ожидание:** все четыре команды зелёные. Счётчик тестов: `:mcp-server:test` → ~27 (4 тест-класса
08–11). Корневой `build` → все существующие тесты дней 1–17 зелёные (0 регрессий).

## Smoke 1 — raw JSON-RPC: 4 tools ( Day 17 паттерн)

Запустить сервер в stdio и прогнать JSON-RPC вручную (как в Day 17 verification):

```bash
./gradlew :mcp-server:installDist
export CLI_AGENT_MCP_BIN="$PWD/mcp-server/build/install/mcp-server/bin/mcp-server"
export CLI_AGENT_GITHUB_TOKEN=<pat>

# initialize
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}' \
  | $CLI_AGENT_MCP_BIN
# → capabilities: tools:{}, serverInfo.name == "cli-agent-mcp" (переименован в задаче 01)

# tools/list — должно быть 4 tools
echo '{"jsonrpc":"2.0","id":2,"method":"notifications/initialized"}' | $CLI_AGENT_MCP_BIN  # ack
printf '%s\n%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"s","version":"0"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | $CLI_AGENT_MCP_BIN
```
**Ожидание:** `tools/list` возвращает **4 tools**: `get_repo` (Day 17), `collect_weather`,
`get_current_weather`, `get_weather_summary` (с schema `city`/`hours`).

## Smoke 2 — scheduler копит данные (http-режим, интервал 60с)

```bash
./gradlew :mcp-server:shadowJar

export XDG_DATA_HOME=/tmp/cliagent-day18-smoke
rm -rf $XDG_DATA_HOME

CLI_AGENT_MCP_MODE=http \
CLI_AGENT_MCP_PORT=8080 \
CLI_AGENT_MCP_TOKEN=dev-token \
CLI_AGENT_WEATHER_INTERVAL_SECONDS=60 \
CLI_AGENT_WEATHER_CITIES=Moscow \
CLI_AGENT_GITHUB_TOKEN=<pat> \
java -jar mcp-server/build/libs/mcp-server-0.1.0-all.jar &
SERVER_PID=$!

# Наблюдаем stderr ~3-5 минут:
# [weather] scheduler started: cities=[Moscow] interval=60s
# [weather] collected Moscow: 18°C, Преимущественно ясно ...      (цикл 1, немедленно)
# ... через 60с ...
# [weather] collected Moscow: ...                                  (цикл 2)

ls -la $XDG_DATA_HOME/cli-agent/weather/
# → moscow.json создан и растёт (snapshots:[...] наполняется)

# Проверяем содержимое:
cat $XDG_DATA_HOME/cli-agent/weather/moscow.json
# → {"snapshots":[{"timestamp":...,"city":"Moscow","temperature_celsius":...,...},...]}

kill $SERVER_PID
```
**Ожидание:** `[weather] scheduler started...`, затем `[weather] collected Moscow: ...` каждые 60с;
`moscow.json` растёт по числу снапшотов.

## Smoke 3 — get_repo не сломан (регрессия Day 17)

Через тот же сервер (bearer-auth) — raw `tools/call get_repo`:

```bash
printf '%s\n%s\n%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"s","version":"0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_repo","arguments":{"owner":"JetBrains","repo":"kotlin"}}}' \
  | $CLI_AGENT_MCP_BIN
```
**Ожидание:** `isError:false`, текст со звёздами/языком/описанием — идентично Day 17.
Без токена → `isError:true` (как раньше). **Поведение `get_repo` не изменилось** (pure refactor 01).

## Доп. проверки (grep / структура)

- `mcp-server/src/main/kotlin/com/cliagent/mcp/server/GitHubMcpServer.kt` — **удалён** (задача 01).
- В пакете `com.cliagent.mcp.server` файлы: `McpServerApp.kt`, `McpServerFactory.kt`,
  `util/Args.kt`, `util/DataPaths.kt`, `tools/GitHubTools.kt`, `tools/WeatherTools.kt`,
  `weather/WeatherClient.kt`, `weather/WeatherSnapshot.kt`, `weather/WeatherStore.kt`,
  `weather/WeatherScheduler.kt`.
- `mcp-server/build.gradle.kts`: `mainClass.set("com.cliagent.mcp.server.McpServerAppKt")` +
  test-deps (задача 07).
- `src/main/...` (клиентский модуль) — **0 diff** (Day 18 не трогает клиент).
- `println` в stdout-пути сервера — только JSON-RPC; погодные логи (`[weather]`) → stderr.

## Чек-лист приёмки (соответствие заданию курса)

- [ ] «сохранять данные (JSON/SQLite)» — `WeatherStore` JSON + atomic write (smoke 2: `moscow.json`)
- [ ] «периодический сбор данных» — `WeatherScheduler` (smoke 2) + `collect_weather` (tools/list)
- [ ] «выполняться по расписанию» — scheduler every 60s (smoke 2) + on-demand `collect_weather`
- [ ] «возвращать агрегированный результат» — `get_weather_summary` (tools/list, full demo в задаче 13)
- [ ] «24/7 агент со сводкой» — http-режим + scheduler (smoke 2; VPS-деплой Day 17-vps совместим)
- [ ] 0 регрессий Day 17 — `get_repo` работает идентично (smoke 3)
- [ ] Все тесты green (`:mcp-server:test` ~27 + корневые дни 1–17)

## Критерии готовности

- `./gradlew :mcp-server:test build shadowJar` — green.
- `./gradlew test` (корень) — 0 регрессий.
- Smoke 1: `tools/list` = 4 tools.
- Smoke 2: scheduler копит снапшоты в JSON.
- Smoke 3: `get_repo` идентичен Day 17.
- Чек-лист приёмки отмечен.

## Зависимости (задачи)

01–11. Расширяется в 13 (полноценный демо-сценарий через REPL + LLM).
