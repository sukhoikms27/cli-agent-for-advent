# T2 — Зависимость MCP SDK

## Цель
Добавить зависимость официального MCP Kotlin SDK, убедиться в разрешении зависимостей и сверить
актуальные сигнатуры API (до написания `McpClient` в T5).

## Изменения

### `build.gradle.kts`
Добавить (после блока coroutines):
```kotlin
// MCP — Model Context Protocol client (официальный Kotlin SDK, день 16)
implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")
```

## Verify
1. `./gradlew build` — зелёный.
2. `./gradlew dependencies --configuration runtimeClasspath` — без красных конфликтов
   (dashed `->` line: разрешение версий serialization/coroutines/kotlinx-io выровнено бампом из T1).
3. **Сверить сигнатуры SDK в IDE** (важно для T5) — открыть классы из JAR и зафиксировать:
   - Пакеты: `io.modelcontextprotocol.kotlin.sdk.client.Client`,
     `…client.StdioClientTransport`, `…types.{Tool,Implementation,ToolSchema}`.
   - `StdioClientTransport` конструктор: `(input: Source, output: Sink, error: Source? = null, …)`.
   - `Client(clientInfo = Implementation(name, version))` — точная форма конструктора/фабрики.
   - `suspend fun connect(transport)`, `suspend fun listTools(): ListToolsResult` (`.tools: List<Tool>`),
     `suspend fun close()` (унаследован из `Protocol`; `Client` НЕ `AutoCloseable`).
   - `Tool`: поля `name: String`, `description: String?`, `inputSchema: ToolSchema`.
   - `ToolSchema.serializer()` — существует для `encodeToString`.

   Записать фактические сигнатуры в комментарий `McpClient.kt` в T5 при расхождении со скелетом.

## Коммит
`feat: add MCP Kotlin SDK 0.13.0 dependency (day16 T2)`. MCP-кода, использующего SDK, пока нет.
