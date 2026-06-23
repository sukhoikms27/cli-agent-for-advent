# T3 — Конфигурация MCP

## Цель
Дать пользователю способ задать MCP-сервер (команду запуска) через env или `local.properties`,
по существующему шаблону `model`/`baseUrl`.

## Изменения

### `config/AppConfig.kt`
Добавить nullable-поле (default null — обратная совместимость, старая конфигурация грузится):
```kotlin
data class AppConfig(
    val apiKey: String,
    val model: String = "glm-5.1",
    val baseUrl: String = "https://api.z.ai/api/coding/paas/v4",
    val mcpCommand: String? = null
)
```
`String?` (не `List<String>`) — храним сырую командную строку; токенизация в месте вызова (T6).

### `config/ConfigRepository.kt`
В `load()`, перед `return AppConfig(...)`, по шаблону `model`/`baseUrl` (env → local.properties → null):
```kotlin
val mcpCommand = System.getenv("CLI_AGENT_MCP_COMMAND")
    ?: localProps.getProperty("mcp.command")
```
И в конструкторе `AppConfig`: `mcpCommand = mcpCommand`.

Ключ в `local.properties`: `mcp.command=npx -y @modelcontextprotocol/server-filesystem /tmp`.
Env: `CLI_AGENT_MCP_COMMAND`.

## Verify
`./gradlew build` — зелёный; существующие config-тесты проходят (новое поле nullable с default,
старые тесты не затронуты).

## Коммит
`feat: add mcpCommand to AppConfig (CLI_AGENT_MCP_COMMAND / mcp.command) (day16 T3)`.
