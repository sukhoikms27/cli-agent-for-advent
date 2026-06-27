# 06 — Верификация

> Все проверки Day 19 — build, test, shadowJar, raw `tools/list`. Команды воспроизводимы; вывод
> зафиксирован в момент сдачи (2026-06-28).

## 1. Build (оба модуля) — green

```bash
./gradlew build
```
**Результат:** `BUILD SUCCESSFUL`. Клиентский модуль (`src/`) компилируется и проходит тесты
(единственное изменение — лог tool-call'а, см. [`04`](./04-agent-tool-logging.md)).

## 2. Тесты `:mcp-server` — 52 green

```bash
./gradlew :mcp-server:test
```
**Результат:** `BUILD SUCCESSFUL`. 52 теста, 0 failures, 0 errors (см. таблицу в [`05-tests.md`](./05-tests.md)).

## 3. Тесты корневого модуля (клиент) — green

```bash
./gradlew :test
```
**Результат:** `BUILD SUCCESSFUL`. `AgentToolUseLoopTest` и пр. — green (лог в stdout не ломает assert'ы).

## 4. shadowJar — green

```bash
./gradlew :mcp-server:shadowJar
```
**Результат:** fat-jar `mcp-server/build/libs/mcp-server-0.1.0-all.jar` собран (для VPS-деплоя).

## 5. Raw `tools/list` = 11 tools (БЕЗ LLM)

> На машине системная Java — 8 (`1.8.0_51`), а код скомпилирован под Java 17. Поэтому запускаем
> fat-jar **через JDK 17** напрямую (Gradle toolchain: `~/.jdks/ms-17.0.17`), а не через сгенерированный
> `.bat` (он берёт системную Java 8 → `UnsupportedClassVersionError`).

```bash
JAR=mcp-server/build/libs/mcp-server-0.1.0-all.jar
JDK17=~/.jdks/ms-17.0.17/bin/java   # путь из ./gradlew :mcp-server:javaToolchains

printf '%s\n%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"s","version":"0"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | CLI_AGENT_MCP_MODE=stdio $JDK17 -jar $JAR 2>/dev/null
```

**Результат — 11 tools** (7 Day 17–18 + 4 новых Day 19):
```
collect_weather          (Day 18)
format_report            ← NEW Day 19
get_current_weather      (Day 18)
get_repo                 (Day 17)
get_weather_summary      (Day 18)
list_notes               ← NEW Day 19
list_weather_subscriptions (Day 18)
save_to_file             ← NEW Day 19
search_wikipedia         ← NEW Day 19
subscribe_weather        (Day 18)
unsubscribe_weather      (Day 18)
```

Schema `search_wikipedia` (фрагмент дампа):
```json
{"name":"search_wikipedia","inputSchema":{"properties":{
  "query":{"type":"string","description":"Тема или поисковая фраза..."},
  "language":{"type":"string","default":"en","enum":["en","ru"]}
},"required":["query"]}}
```

## 6. 0 регрессий Day 17/18

- `get_repo` — в `tools/list`, schema не изменилась (Day 17).
- 6 погодных tools — все в `tools/list` (Day 18).
- `WeatherStoreTest`/`WeatherClientTest`/`WeatherSchedulerTest`/`WeatherAggregateTest` — green.
- `McpClientServerIntegrationTest` (Day 17, stdio E2E) — green.

## Чек-лист соответствия заданию

- [x] «несколько MCP-инструментов» — 4 новых (+ 7 = 11).
- [x] «первый получает данные» — `search_wikipedia` / `get_repo`.
- [x] «второй обрабатывает» — `format_report`.
- [x] «третий сохраняет результат» — `save_to_file`.
- [x] «автоматическое выполнение цепочки» — LLM через `runToolLoop` (демо в [`07`](./07-demo-scenario.md)).
- [x] «корректность передачи данных» — ответы search→аргументы format→аргументы save.
