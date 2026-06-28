# 04 — ChatCommand wiring

## Что сделано

`cli/ChatCommand.kt` — сборка `toolExecutor` по числу серверов (стр. ~124–142 заменены):

```kotlin
val mcpServers = config.mcp.filter { it.enabled }
val toolExecutor: ToolExecutor? = when {
    mcpServers.size >= 2 -> CompositeMcpToolExecutor(servers = mcpServers, logger = AppTerminal::println)
    mcpServers.size == 1 -> McpToolExecutor(mcpServers.first().toTransport())
    else -> null   // Day 1–16 (tools off)
}
val mcpServerCount = mcpServers.size
```

`maxToolRounds` пробрасывается в агента:
```kotlin
val agent = ContextAwareAgent(
    ...,
    maxToolRounds = config.maxToolRounds,   // NEW (default 8)
    ...
)
```

Status-line теперь показывает MCP-индикацию:
```
CLI Agent v0.8 | Chat: ... | MCP: 2 server(s) | MaxToolRounds: 8 | ...
```

## 0 регрессий

- `mcpServers.size == 1` → single `McpToolExecutor` (поведение Day 17–19 идентично).
- `size == 0` → `null` (tools off, Day 1–16).
- Legacy single-server (mcpCommand/mcpUrl) свёрнут в single-element `mcp` на уровне `ConfigRepository`.

## Критерии готовности

- `./gradlew compileKotlin` green.
- Single-server конфиг → `/mcp` показывает 1 сервер, 11 tools работают.
- Multi-server (≥2) → `CompositeMcpToolExecutor`, status-line показывает "N server(s)".
