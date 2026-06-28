# Server #2: npx `@modelcontextprotocol/server-filesystem`

> Источник второго MCP-сервера для demo Day 20 (Р1 — public npx-сервер). Это **внешний** сервер,
> не наш код — он подключается как subprocess через stdio, как в Day 16.

## Что это

[`@modelcontextprotocol/server-filesystem`](https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem) —
официальный reference MCP-сервер от Anthropic. Даёт tools для работы с локальной ФС:
`read_file`, `write_file`, `list_directory`, `create_directory`, `move_file`, `search_files`,
`get_file_info`, `list_allowed_directories` (≈8 tools). Read-write, но sandboxed в указанной директории.

## Регистрация через config.json (путь A)

`~/.config/cli-agent/config.json`:
```json
{
  "apiKey": "<z.ai key>",
  "maxToolRounds": 8,
  "mcp": [
    {
      "name": "local",
      "command": "java",
      "args": ["-jar", "mcp-server/build/libs/mcp-server-0.1.0-all.jar"]
    },
    {
      "name": "filesystem",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/notes"]
    }
  ]
}
```

`C:/notes` — корневая директория, которую сервер «видит» (sandbox). Замените на свой путь.

## Регистрация через REPL (путь B)

```
cli-agent> /mcp add filesystem -- npx -y @modelcontextprotocol/server-filesystem C:/notes
✓ Server 'filesystem' added to config.json. Restart REPL to apply.
```

## Требования

- **Node.js + npx** установлены (сервер скачивается через npx при первом запуске).
- **Сеть** — npx тянет пакет из npm registry (первый запуск; кэшируется).

## Проверка

```
cli-agent> /mcp
🔌 MCP servers (2):
  ✓ local       (stdio: java -jar ...) — 11 tools
  ✓ filesystem  (stdio: npx -y @modelcontextprotocol/server-filesystem C:/notes) — 8 tools
   /mcp list-tools <name> — list a server's tools
   /mcp add/remove <name> — manage servers in config.json

cli-agent> /mcp list-tools filesystem
🔌 Connecting to MCP server 'filesystem': stdio: npx -y @modelcontextprotocol/server-filesystem C:/notes
┌─ 🔌 MCP Tools (8) — filesystem ────────────┐
│ # │ Name                  │ Description     │
│ 1 │ read_file             │ …               │
│ 2 │ write_file            │ …               │
│ 3 │ list_directory        │ …               │
│ ...                                         │
└─────────────────────────────────────────────┘
```

## Альтернативы (если npx недоступен)

- **Self-contained `:mcp-server-external`** субпроект — офлайн, но больше кода (вне scope Day 20).
- **Remote VPS-сервер** из Day 17-vps (`McpServerConfig` с `url`+`token`).

В этом окружении npx недоступен → live-демо документируется как сценарий
([`07-demo-scenario.md`](./07-demo-scenario.md)); unit-тесты `CompositeMcpToolExecutorTest`
покрывают routing/collision/degradation с mock-executor'ами без реального сервера.
