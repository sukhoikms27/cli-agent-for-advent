# 05 — REPL /mcp + /config

## Что сделано

`cli/ChatCommand.kt` — `handleMcp` переписана под multi-server + добавлен `handleConfig`.

### `/mcp` (multi-server UX)

| Команда | Действие |
|---|---|
| `/mcp` | Сводка всех серверов (имя, транспорт, кол-во tools, статус ✓/✗) с live discovery |
| `/mcp add <name> -- <cmd> <args…>` | Добавить stdio-сервер в config.json (путь B) |
| `/mcp add <name> --url <u> [--token <t>]` | Добавить remote-сервер в config.json |
| `/mcp remove <name>` | Убрать сервер из config.json |
| `/mcp list-tools [name\|<cmd…>]` | List tools: конкретный сервер по имени, или inline-override (Day 16 совместимость) |

`add`/`remove` пишут в config.json через `ConfigRepository.addMcpServer`/`removeMcpServer`
(atomicWrite). Изменения вступают в силу после перезапуска REPL (toolExecutor собран на старте).

### `/config` (управление config.json)

| Команда | Действие |
|---|---|
| `/config init` | Р3: сгенерировать стартовый config.json из env/local.properties (не перезаписывает существующий) |
| `/config show` | Путь файла + сводка (model, baseUrl, maxToolRounds, apiKey наличие, mcp-серверы) |
| `/config path` | Только путь config.json |

### Help обновлён

`/help` теперь документирует `/mcp add/remove`, `/config init/show/path`.

## Критерии готовности

- `./gradlew compileKotlin` green.
- `McpCommandTest` green (read-only ветки: empty-status, unknown subcommand, add/remove usage).
- `/mcp add filesystem -- npx …` → запись в config.json, `/mcp remove filesystem` → удаление.
