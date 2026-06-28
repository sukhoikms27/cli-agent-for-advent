# 08 — Тесты

## Сводка тестов Day 20

### `CompositeMcpToolExecutorTest` (11 тестов) — `src/test/kotlin/com/cliagent/mcp/`

Ядро оркестрации. Через `executorFactory` DI-seam подставляются mock `McpToolExecutor` (без реального
MCP-сервера):

| Тест | Что проверяет |
|---|---|
| `routes call to correct server by tool name` | routing: вызов → правильный сервер |
| `prefix-on-collision - shared tool name renamed` | Р2: коллизия → `server__tool`, raw при уникальности |
| `graceful degradation - unavailable server skipped` | упавший сервер skip + warning, остальные работают |
| `disabled server is skipped from discovery` | `enabled=false` → skip |
| `unknown tool name returns diagnostic, not exception` | LLM выдумала tool → диагностика |
| `definitions are cached` | executorFactory вызывается 1 раз (lazy + кэш) |
| `close closes all executors` | lifecycle: close всех |
| `serverSummary lists all enabled servers` | REPL-сводка |
| `empty servers yields empty definitions` | edge-case |
| `toTransport prefers url over command` | url приоритетнее command |

### `ConfigRepositoryTest` (17 тестов) — `src/test/kotlin/com/cliagent/config/`

Через `@TempDir` + constructor-параметры `configFile`/`localPropertiesFile` (DI-seam):

| Группа | Что проверяет |
|---|---|
| JSON-загрузка | apiKey/model/baseUrl/maxToolRounds/mcp из config.json |
| defaults | schema-evolution: неполный JSON → defaults (maxToolRounds=8) |
| malformed | битый JSON → graceful fallback (env/legacy дают поля) |
| legacy fallback | mcp.command/url/token → single-element mcp (0 регрессий) |
| приоритет | config.json mcp приоритет над legacy |
| save round-trip | save → loadConfigFile = оригинал |
| addMcpServer | append + replace-by-name + сохранение других секций |
| removeMcpServer | true/false + корректное удаление |
| initFromLegacy | создание файла из properties + не перезаписывает существующий |
| load throws | missing apiKey → IllegalStateException |
| toTransport | url wins over command |

### `AgentToolUseLoopTest` (обновлён) — `src/test/kotlin/com/cliagent/agent/`

`MAX_TOOL_ROUNDS` const → `maxToolRounds` параметр:
- `loop terminates at maxToolRounds` — явный `maxToolRounds=4` (детерминирован от конструктора).
- **NEW** `maxToolRounds is configurable - default 8` — проверка, что `maxToolRounds=3` рвёт цикл
  после 3 tool-вызовов.

### `McpCommandTest` (обновлён) — `src/test/kotlin/com/cliagent/cli/`

Под новую сигнатуру `handleMcp(input, servers)` (вместо transport/factory): read-only ветки
(empty-status, unknown subcommand, add/remove usage).

## Прогон

```
$ JAVA_HOME=... ./gradlew :test --tests "com.cliagent.config.ConfigRepositoryTest" \
    --tests "com.cliagent.mcp.CompositeMcpToolExecutorTest" \
    --tests "com.cliagent.agent.AgentToolUseLoopTest" \
    --tests "com.cliagent.cli.McpCommandTest"
BUILD SUCCESSFUL — 35 tests passed
```
