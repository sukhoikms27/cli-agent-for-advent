# 01 — Единый config.json + McpServerConfig

## Что сделано

### `AppPaths.configDir`/`configFile` (XDG)

`config/AppPaths.kt`:
```kotlin
val configDir: Path = System.getenv("XDG_CONFIG_HOME")?.let { Path.of(it) }
    ?: Path.of(System.getProperty("user.home"), ".config", "cli-agent")
val configFile: Path get() = configDir.resolve("config.json")
```

### `AppConfig` → @Serializable с массивом `mcp` + `maxToolRounds`

`config/AppConfig.kt` — единая модель конфигурации (schema-evolution, все поля с defaults):
```kotlin
@Serializable
data class AppConfig(
    val apiKey: String = "",
    val model: String = "glm-5.1",
    val baseUrl: String = "https://api.z.ai/api/coding/paas/v4",
    val maxToolRounds: Int = 8,                    // NEW: default 8 (день 20)
    val mcp: List<McpServerConfig> = emptyList(),  // NEW: массив серверов
)
```

### `McpServerConfig` (новый)

`mcp/McpServerConfig.kt` — модель одного сервера:
```kotlin
@Serializable
data class McpServerConfig(
    val name: String,
    val command: String? = null,    // stdio
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,        // remote HTTP
    val token: String? = null,
    val enabled: Boolean = true,
) {
    fun toTransport(): McpTransportConfig   // url приоритетнее command
    fun transportLabel(): String            // для /mcp-вывода
}
```

## Формат config.json

```json
{
  "apiKey": "...",
  "model": "glm-5.1",
  "baseUrl": "https://api.z.ai/api/coding/paas/v4",
  "maxToolRounds": 8,
  "mcp": [
    { "name": "local",      "command": "java", "args": ["-jar", ".../mcp-server-0.1.0-all.jar"] },
    { "name": "filesystem", "command": "npx",  "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/notes"] },
    { "name": "vps",        "url": "https://mcp.example.com/mcp", "token": "secret-bearer" }
  ]
}
```

## Критерии готовности

- `./gradlew compileKotlin` green.
- `AppConfig` сериализуется/десериализуется (round-trip в `ConfigRepositoryTest`).
- Поля с defaults — пустой/неполный JSON грузится без ошибок (schema-evolution).
