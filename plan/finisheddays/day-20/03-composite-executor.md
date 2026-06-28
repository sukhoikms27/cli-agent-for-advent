# 03 — CompositeMcpToolExecutor: ядро оркестрации

## Что сделано

`mcp/CompositeMcpToolExecutor.kt` — implements `ToolExecutor`, агрегирует N серверов.
**Agent-слой (`ContextAwareAgent`, `runToolLoop`) НЕ меняется** — он уже работает с одним
`ToolExecutor`; composite прозрачно маршрутизирует.

### Routing-таблица + prefix-on-collision (Р2)

```kotlin
// definitions(): для каждого enabled сервера → listTools → routing-таблица
// routing: Map<exposedName, Pair<serverName, rawName>>
private fun buildRoutingTable(perServer: Map<String, List<String>>): Map<...> {
    // имя, объявленное на ≥2 серверах → все экземпляры переименовываются в "server__tool"
    // уникальные имена остаются raw → 0 регрессий single-server
}
```

### call() — маршрутизация

```kotlin
override suspend fun call(name: String, args: Map<String, Any?>): String {
    val (serverName, rawName) = routing!![name]
        ?: return "Tool '$name' not found across ${servers.size} MCP server(s)..."  // LLM самокоррекция
    return executors[serverName]!!.call(rawName, args)
}
```

### Graceful degradation

Упавший/недоступный сервер → warning + skip его tools; остальные работают; агент **не падает**.
```kotlin
} catch (e: Throwable) {
    logger("⚠️ MCP server '${server.name}' unavailable: ${e.message}; skipping its tools.")
    continue   // не break — остальные серверы живы
}
```

### Lifecycle + DI-seam

- `executorFactory: (McpTransportConfig) -> McpToolExecutor = ::McpToolExecutor` — для тестов.
- Lazy-connect каждого сервера; `close()` закрывает все (CancellationException re-throw).
- `definitions()` кэширует routing-таблицу (single discovery за сессию).

## Критерии готовности

- `./gradlew compileKotlin` green.
- `CompositeMcpToolExecutorTest` green (11 тестов: routing, collision, degradation, disabled,
  unknown-tool, cache, close, serverSummary, empty, toTransport).
