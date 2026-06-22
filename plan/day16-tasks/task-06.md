# T6 — CLI: slash-команда `/mcp`

## Цель
Добавить REPL-команду `/mcp` для вывода статуса конфигурации и подключения к MCP-серверу с выводом
списка инструментов. Per-invocation: создаёт `McpClient`, листает инструменты, закрывает — в `finally`.

## Изменения

### `src/main/kotlin/com/cliagent/cli/ChatCommand.kt`

**1. Ветка REPL `when`** (~строка 184, рядом с `/mode`, до `else`):
```kotlin
input.startsWith("/mcp") -> handleMcp(input, config.mcpCommand, ::McpClient)
```
`config` — `AppConfig` из `run()` (строка 84).

**2. `handleMcp`** — `internal suspend fun` (тестовый шов, как `dispatchFreeText:215`), рядом с
`handleMode`/`handleStrategy`:
```kotlin
internal suspend fun handleMcp(
    input: String,
    mcpCommand: String?,
    mcpClientFactory: (List<String>) -> McpClient = ::McpClient
)
```
Парсинг `input.trim().split("\\s+".toRegex())`, `when (parts[1])`:
- **Без подкоманды (`/mcp`)** → статус конфигурации:
  - `System.getenv("CLI_AGENT_MCP_COMMAND") != null` → «configured (env CLI_AGENT_MCP_COMMAND)»
  - `mcpCommand != null` → «configured (local.properties mcp.command)»
  - иначе → «no command configured» + подсказка с inline-примером.
- **`list-tools [cmd…]`** → определить команду сервера:
  - `parts.size >= 3` → override: `parts.drop(2)` (уже `List<String>`).
  - иначе → `mcpCommand?.takeIf{ it.isNotBlank() }?.trim()?.split("\\s+".toRegex())`.
  - пусто → `AppTerminal.warn(...)` + подсказка + `return`.
  - Создать `val client = mcpClientFactory(command)`; `try { ... } catch (e: Exception) {
    AppTerminal.err("MCP failed: ${e.message}") } finally { runCatching { client.close() } }`.
  - Внутри try: `AppTerminal.withSpinner("Connecting to MCP server…") { client.connect() }`
    (паттерн `dispatchFreeText:244,269`); `val tools = client.listTools()`; если пусто →
    «Server exposed 0 tools.»; иначе mordant `table { captionTop("🔌 MCP Tools (${tools.size})");
    header{ row("#","Name","Description") }; body{ tools.forEachIndexed { i,t -> row("${i+1}", t.name, truncate(t.description)) } } }`
    через `AppTerminal.println(table)`.
- **unknown** → `AppTerminal.println("Unknown /mcp command: ${parts[1]}. Use: list-tools")`.

**Truncate description** — приватный хелпер: первая строка (`lineSequence().firstOrNull()`),
обрезанная до ~80 символов + `…`.

**3. `printHelp()`** (~303-387) — добавить секцию (raw-string с `|`-префиксом), напр. после `/mode`:
```
|  /mcp                 — Show MCP config status
|  /mcp list-tools      — Connect to configured MCP server & list its tools
|  /mcp list-tools <cmd…> — Override server command for this call
|                          (e.g. /mcp list-tools npx -y @modelcontextprotocol/server-filesystem <dir>)
```

**Импорты:** `com.cliagent.mcp.McpClient`, `com.cliagent.mcp.McpException`.
`table` уже импортирован (строка 41).

## Verify
`./gradlew build` — зелёный (компилируется, существующие тесты проходят).

## Коммит
`feat: add /mcp REPL command (status + list-tools) (day16 T6)`.
