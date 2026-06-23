# T4 — MCP-модели

## Цель
Создать доменные модели MCP, изолирующие CLI-слой от типов SDK (паттерн `llm/model/` — свои модели
для внешнего протокола). Используются в T5 (`McpClient`) и T6 (`handleMcp`).

## Изменения

### `src/main/kotlin/com/cliagent/mcp/McpTool.kt` (новый)
```kotlin
package com.cliagent.mcp

/**
 * Наше представление MCP-инструмента (день 16). Изолирует CLI-слой от типов MCP SDK:
 * [inputSchema] хранится как JSON-строка (как есть от сервера), чтобы не тянуть
 * JsonElement/ToolSchema в сигнатуры CLI. День 16 отображает только name + description.
 */
data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: String?
)
```

### `src/main/kotlin/com/cliagent/mcp/McpException.kt` (новый)
```kotlin
package com.cliagent.mcp

/** Ошибка MCP-соединения/handshake (день 16). */
class McpException(message: String) : Exception(message)
```

## Verify
`./gradlew build` — компилируется (классы пока не используются, только декларации).

## Коммит
`feat: add McpTool + McpException domain models (day16 T4)`.
