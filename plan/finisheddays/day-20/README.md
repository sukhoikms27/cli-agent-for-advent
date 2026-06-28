# День 20 — Orchestration MCP (README / общий план)

> Регистрация нескольких MCP-серверов с маршрутизацией инструментов. Единый `config.json`
> с масштабируемым массивом `mcp`; `CompositeMcpToolExecutor` оркестрирует серверы без правок
> agent-слоя. Корневой контекст: [`00-task.md`](./00-task.md).

## Что произошло с архитектурой

Day 20 — **оркестрация нескольких MCP-серверов**. Ключевое архитектурное решение: **agent-слой
(`ContextAwareAgent`, `runToolLoop`) НЕ меняется** — он уже принимает один `ToolExecutor`.
Multi-server = **композиция в `mcp/`** через новый `CompositeMcpToolExecutor` (implements
`ToolExecutor`), агрегирующий N серверов с routing-таблицей.

Второе решение — **единый `config.json`** (XDG `~/.config/cli-agent/config.json`): масштабируемая
точка конфигурации с массивом серверов `mcp` (как в Claude Code). Добавление сервера = дописать
элемент, ноль кода.

| Было (Day 16–19) | Стало (Day 20) |
|---|---|
| 1 MCP-сервер, single `McpToolExecutor` | N серверов, `CompositeMcpToolExecutor` |
| `MAX_TOOL_ROUNDS = 4` (const) | `maxToolRounds` (constructor, default **8**) |
| Плоский config: properties + env | Единый `config.json` + env override + legacy fallback |
| `/mcp` single-server статус | `/mcp` multi-server сводка + `/mcp add/remove` + `/config init/show` |

## Карта файлов

| # | Файл | Тип | Содержание |
|---|---|---|---|
| 00 | [`00-task.md`](./00-task.md) | контекст | Задание курса, контекст Day 16–19, развилки, маппинг задание→реализация |
| — | [`README.md`](./README.md) | индекс/план | Этот файл: навигация, итоги, структура, env, чек-лист |
| 01 | [`01-config-json.md`](./01-config-json.md) | реализация | `AppPaths.configDir` + `AppConfig` @Serializable (mcp-массив, maxToolRounds) + `McpServerConfig` |
| 02 | [`02-config-repository.md`](./02-config-repository.md) | реализация | `ConfigRepository`: JSON-загрузка, merge env/legacy, atomicWrite, addMcpServer/removeMcpServer/initFromLegacy |
| 03 | [`03-composite-executor.md`](./03-composite-executor.md) | реализация | `CompositeMcpToolExecutor`: routing-таблица, prefix-on-collision, graceful degradation, lifecycle |
| 04 | [`04-cli-wiring.md`](./04-cli-wiring.md) | реализация | `ChatCommand`: composite при ≥2 серверах, `maxToolRounds` → агент, status-line MCP |
| 05 | [`05-cli-repl.md`](./05-cli-repl.md) | реализация | REPL `/mcp` (сводка/add/remove/list-tools) + `/config` (init/show/path) |
| 06 | [`06-second-server.md`](./06-second-server.md) | демо-setup | Server #2: npx `@modelcontextprotocol/server-filesystem` через config.json |
| 07 | [`07-demo-scenario.md`](./07-demo-scenario.md) | демо | Cross-server flow: search_wikipedia [local] → read_file [filesystem] → format_report → save |
| 08 | [`08-tests.md`](./08-tests.md) | тесты | `ConfigRepositoryTest` + `CompositeMcpToolExecutorTest` + `AgentToolUseLoopTest` (maxToolRounds) |
| 09 | [`09-verification.md`](./09-verification.md) | верификация | `./gradlew build` green, `/mcp` → 2+ сервера, demo, 0 регрессий |

## Итоги

- **Было (Day 17–19):** 1 сервер, 11 tools, single `McpToolExecutor`, `MAX_TOOL_ROUNDS=4`.
- **Стало (Day 20):** **N серверов** (`CompositeMcpToolExecutor`), `maxToolRounds` default 8,
  единый `config.json` с массивом `mcp`.
- **Новый код:** `mcp/McpServerConfig.kt`, `mcp/CompositeMcpToolExecutor.kt`, переписан
  `config/{AppConfig,ConfigRepository,AppPaths}.kt`, правки `cli/ChatCommand.kt` +
  `agent/ContextAwareAgent.kt` (maxToolRounds).
- **0 регрессий:** single-server конфиг → все 11 tools работают идентично; legacy properties/env
  fallback сохранён.

## Структура модуля (после Day 20)

```
src/main/kotlin/com/cliagent/
├── config/
│   ├── AppPaths.kt              # +configDir/configFile (XDG) — NEW
│   ├── AppConfig.kt             # @Serializable + mcp-массив + maxToolRounds — REWRITE
│   └── ConfigRepository.kt      # JSON-загрузка + merge env/legacy + atomicWrite — REWRITE
├── mcp/
│   ├── McpServerConfig.kt       # модель сервера + toTransport() — NEW
│   ├── CompositeMcpToolExecutor.kt  # multi-server оркестрация — NEW
│   ├── McpToolExecutor.kt       # single-server (без изменений)
│   ├── McpClient.kt             # (без изменений)
│   ├── McpTransportConfig.kt    # (без изменений)
│   └── ...
├── agent/
│   └── ContextAwareAgent.kt     # MAX_TOOL_ROUNDS const → maxToolRounds param (default 8)
└── cli/
    └── ChatCommand.kt           # composite wiring + /mcp add/remove + /config
```

## Добавление сервера (пути A + B)

**Путь A** — вручную отредактировать `config.json` (declarative):
```json
{
  "mcp": [
    { "name": "local",      "command": "java", "args": ["-jar", "mcp-server/build/libs/mcp-server-0.1.0-all.jar"] },
    { "name": "filesystem", "command": "npx",  "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/notes"] }
  ]
}
```

**Путь B** — REPL `/mcp add` / `/mcp remove` (пишет в config.json):
```
cli-agent> /mcp add filesystem -- npx -y @modelcontextprotocol/server-filesystem C:/notes
✓ Server 'filesystem' added to config.json. Restart REPL to apply.
cli-agent> /mcp remove filesystem
✓ Server 'filesystem' removed. Restart REPL to apply.
```

## Конвенции кода (AGENTS.md, соблюдены)

- **Persistence:** JSON (`config.json`), **atomic write** (temp + `Files.move(ATOMIC_MOVE)`), XDG-пути.
- **Schema evolution:** поля `@Serializable` с **defaults** — старые/неполные файлы грузятся.
- **Единый Json:** `Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; prettyPrint = true; coerceInputValues = true }`.
- **Coroutines:** `CancellationException` **никогда не глотать** — re-throw перед generic catch.
- **UTF-8 явно:** `Files.writeString(..., Charsets.UTF_8)` — фикс эмодзи/кириллицы на Windows (как Day 19).
- **DI-seam:** `executorFactory`/`configFile`/`localPropertiesFile` — для тестов без реальных ресурсов.

## Чек-лист приёмки (соответствие заданию курса)

- [x] «зарегистрируйте несколько MCP-серверов» — config.json массив + `/mcp add`
- [x] «агент выбирал нужный инструмент» — `CompositeMcpToolExecutor.definitions()` merge
- [x] «корректно маршрутизировал запросы» — routing-таблица `toolName → server`
- [x] «выполнял длинный флоу» — `maxToolRounds` default 8 + cross-server chaining (demo)
- [x] «инструменты с разных серверов» — demo search_wikipedia [local] + read_file [filesystem]
- [x] **0 регрессий** Day 17–19 — single-server → 11 tools идентично, legacy fallback
