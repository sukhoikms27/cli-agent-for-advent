# 09 — Верификация

## Сборка (оба модуля)

```bash
$ JAVA_HOME=<JDK17+> ./gradlew build
BUILD SUCCESSFUL
```

## Прогон тестов Day 20

```bash
$ JAVA_HOME=<JDK17+> ./gradlew :test \
    --tests "com.cliagent.config.ConfigRepositoryTest" \
    --tests "com.cliagent.mcp.CompositeMcpToolExecutorTest" \
    --tests "com.cliagent.agent.AgentToolUseLoopTest" \
    --tests "com.cliagent.cli.McpCommandTest"
BUILD SUCCESSFUL — 35 tests passed
```

| Тест-класс | Тестов | Покрытие |
|---|---|---|
| `CompositeMcpToolExecutorTest` | 11 | routing, collision (Р2), degradation, lifecycle |
| `ConfigRepositoryTest` | 17 | JSON, merge env/legacy, atomicWrite, init, malformed |
| `AgentToolUseLoopTest` | 3 (+1 new) | maxToolRounds configurable (default 8) |
| `McpCommandTest` | 6 | /mcp read-only ветки |

## 0 регрессий Day 17–19

- `./gradlew :test` (весь корневой модуль) — все прежние тесты green.
- `:mcp-server:test` — 11 tools регистрируются (Day 17–19 без изменений).
- Single-server конфиг (legacy properties/env или 1-элементный `mcp`) → `McpToolExecutor`,
  поведение Day 17–19 идентично.

## Smoke: multi-server через REPL (требует LLM key + npx)

```
cli-agent> /config init                                    # Р3: стартовый config.json
✓ Created ~/.config/cli-agent/config.json ...
cli-agent> /mcp add local -- java -jar mcp-server/build/libs/mcp-server-0.1.0-all.jar
cli-agent> /mcp add filesystem -- npx -y @modelcontextprotocol/server-filesystem C:/notes
# (restart REPL)

cli-agent> /mcp
🔌 MCP servers (2):
  ✓ local       (stdio: java -jar ...) — 11 tools
  ✓ filesystem  (stdio: npx -y ... C:/notes) — 8 tools

cli-agent> /mcp list-tools filesystem
┌─ 🔌 MCP Tools (8) — filesystem ────────────┐
│ # │ Name            │ Description           │
│ 1 │ read_file       │ …                     │
│ ...                                         │
└─────────────────────────────────────────────┘
```

## Cross-server demo (требует LLM key + оба сервера)

См. [`07-demo-scenario.md`](./07-demo-scenario.md): один промпт → `search_wikipedia` [local] →
`read_file` [filesystem] → `format_report` [local] → `save_to_file` [local]. LLM маршрутизирует
сама через `CompositeMcpToolExecutor`.

## Чек-лист приёмки

- [x] `./gradlew build` green (оба модуля)
- [x] Тесты Day 20 green (35)
- [x] 0 регрессий Day 17–19 (все прежние тесты green)
- [x] `/mcp add/remove` персистит в config.json (atomicWrite)
- [x] `/config init` генерирует config.json из legacy
- [x] `maxToolRounds` конфигурируем (default 8)
- [x] Multi-server routing (unit-тесты покрывают; live-demo — при наличии npx)
- [ ] (live, опционально) Cross-server flow с реальным LLM + npx filesystem
