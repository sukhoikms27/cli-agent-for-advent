# Как запустить MCP-сервер и подключить его к CLI-агенту

Локальная инструкция Day 17: максимально подробно, по факту кода. Две части — **сервер**
(отдельный процесс) и **подключение** (как агент его поднимает и использует).

---

## Часть 1. Что это вообще такое и как устроено

В проекте два модуля Gradle:

| Модуль | Роль | Артефакт |
|---|---|---|
| `:cli-agent` (root) | CLI-агент + MCP-**клиент** | `./gradlew run --args="chat"` |
| `:mcp-server` | MCP-**сервер** (subprocess) | `mcp-server/build/install/mcp-server/bin/mcp-server` |

Связь между ними — **stdio-transport** по протоколу MCP (JSON-RPC 2.0):

```
CLI-агент (родитель)
  │
  │  ProcessBuilder("mcp-server")
  │  ── stdin  → сервер (JSON-RPC запросы: initialize, tools/list, tools/call)
  │  ── stdout ← сервер (JSON-RPC ответы + tool-результаты)
  │  ── stderr ← сервер (логи SDK, не протокольные)
  ▼
McpClient ──(kotlin-sdk-client)──▶ GitHubMcpServer ──(kotlin-sdk-server)──▶ GitHub REST API
```

Агент сам **порождает** сервер как дочерний процесс через `ProcessBuilder`. Руками сервер запускать
для работы агента **не нужно** — нужно только **указать путь к его бинарнику** в env-переменной.
Руками запуск нужен только для отладки (см. §5).

---

## Часть 2. Сборка MCP-сервера

Сервер — это `application`-плагин Gradle в субпроекте `:mcp-server` (`mcp-server/build.gradle.kts`):
- `mainClass = com.cliagent.mcp.server.GitHubMcpServerKt`
- зависимости: `kotlin-sdk-server:0.13.0`, `kotlin-logging-jvm`, `kotlinx-coroutines`,
  `ktor-client-*`, `kotlinx-serialization-json`, `kotlinx-io-core`, `slf4j-simple` (runtime)
- JVM 17

**Команда сборки + установки launch-скрипта:**
```bash
./gradlew :mcp-server:installDist
```
После этого появляется готовый runnable-скрипт:
```
mcp-server/build/install/mcp-server/bin/mcp-server     # unix (#!/bin/sh)
mcp-server/build/install/mcp-server/bin/mcp-server.bat # windows
```
Внутри — стандартный Gradle `application`-дистрибутив: `bin/mcp-server` + `lib/*.jar` (classpath).
Этот путь и есть то, что подсунем агенту.

> `installDist` достаточно сделать один раз; пересобирать нужно только если код сервера
> (`GitHubMcpServer.kt`) изменился.

---

## Часть 3. Переменные окружения

Три env-переменные. Первые две обязательны, третья — указывает на сервер.

| Переменная | Назначение | Кому нужна |
|---|---|---|
| `CLI_AGENT_API_KEY` | z.ai API-ключ (для LLM-вызовов) | агенту |
| `CLI_AGENT_GITHUB_TOKEN` | GitHub PAT (read-only репозиториев) | **серверу** (но задаётся в shell агента → наследуется subprocess'ом) |
| `CLI_AGENT_MCP_COMMAND` | Команда запуска сервера (путь к бинарнику) | агенту |

**Важный механизм — наследование env.** `McpClient` запускает сервер через
`ProcessBuilder(command)` **без** очистки окружения (`environment()` не переопределяется). Поэтому
всё, что задано в shell агента, попадает в сервер автоматически — в т.ч. `CLI_AGENT_GITHUB_TOKEN`.
Сервер читает его сам (`System.getenv("CLI_AGENT_GITHUB_TOKEN")` в `GitHubMcpServer.kt:70`), токен
агенту передавать не надо и **он не логируется**.

**GitHub PAT:** подойдёт classic PAT со scope `public_repo` (или fine-grained с read на публичные
репозитории). Выпуск: GitHub → Settings → Developer settings → Personal access tokens.

---

## Часть 4. Как подключить сервер к агенту

### 4.1. Точка конфигурации

`CLI_AGENT_MCP_COMMAND` читается в конфиге (`AppConfig.mcpCommand`). Агент получает его в
`ChatCommand.kt:127-132`:

```kotlin
val toolExecutor: ToolExecutor? = config.mcpCommand
    ?.takeIf { it.isNotBlank() }
    ?.trim()
    ?.split("\\s+".toRegex())      // токенизация строки → List<String>
    ?.takeIf { it.isNotEmpty() }
    ?.let { McpToolExecutor(it) }  // null если mcpCommand не задан → tools отключены
```

То есть значение `CLI_AGENT_MCP_COMMAND` — это **строка команды**, которая сплитится по пробелам в
`argv`. Если переменная пустая/отсутствует → `toolExecutor = null` → агент работает как в днях 1–16,
без tools (обратная совместимость сохранена).

### 4.2. Запуск агента с подключённым сервером

```bash
./gradlew :mcp-server:installDist   # 1) собрать сервер (один раз)

CLI_AGENT_MCP_COMMAND="$PWD/mcp-server/build/install/mcp-server/bin/mcp-server" \
CLI_AGENT_GITHUB_TOKEN="<ваш-github-pat>" \
CLI_AGENT_API_KEY="<ваш-z.ai-key>" \
./gradlew run --args="chat"
```

После старта REPL:
```
cli-agent> Что знает агент о репозитории JetBrains/kotlin?
```
→ LLM сама решает вызвать `get_repo` → агент выполняет tool-call → финальный ответ со
stars/языком/описанием.

`$PWD` (не `~`) важен: `McpClient` сплитит команду по пробелам и запускает как есть, `~` не
раскроется. Путь должен быть абсолютным.

### 4.3. Как соединение поднимается под капотом (lazy connect)

`McpToolExecutor` — **persistent lazy**. Subprocess НЕ стартует при запуске REPL. Цепочка:

1. В REPL свободный текст → `dispatchFreeText` → `statefulAgent.chat(msg)` →
   `ContextAwareAgent.chat()`.
2. В `chat()` первым делом `loadToolsOrNull()` → `toolExecutor?.definitions()`.
3. `definitions()` → `McpToolExecutor.ensureConnected()` (первый вызов) → `McpClient.connect()`.
4. `McpClient.connect()` (`McpClient.kt:49-77`):
   - `ProcessBuilder(command).redirectErrorStream(false).start()` — породили subprocess.
   - `StdioClientTransport(input=proc.stdin, output=proc.stdout, error=proc.stderr)` — три pipe'а.
     **`error` передаётся обязательно** — иначе stderr-буфер сервера (~64 КБ) переполнится и зависнет
     (особенности npx; у нас stdio-сервер, но pipe-логика та же).
   - `Client(...)` + `withTimeout(30s) { c.connect(transport) }` — MCP-handshake
     (`initialize` + `initialized`). 30-секундный таймаут спасает от зависшего сервера.
5. После handshake `listTools()` → сервер отдаёт `get_repo` → маппится в `ToolDefinition` (через
   `McpTool.toToolDefinition()`).
6. `tools` + `tool_choice="auto"` кладутся в `ChatRequest` → LLM.
7. Если LLM вернула `tool_calls` — agent tool-use loop (до `MAX_TOOL_ROUNDS=4`):
   - `toolExecutor.call(name, args)` → `McpClient.callTool` → сервер `tools/call` → `handleGetRepo`
     → GitHub REST → `TextContent` со сводкой.
   - Результат дописывается в in-memory scratch как `ChatMessage(role="tool", toolCallId=...)`,
     LLM вызывается снова.
   - Промежуточные tool-сообщения **не персистятся** в `ChatData.messages` — только финальный ответ.

### 4.4. Закрытие соединения (выход из REPL)

В `ChatCommand.kt:219-222`:
```kotlin
} finally {
    toolExecutor?.close()
}
```
`McpToolExecutor.close()` → `McpClient.close()`. **Критичный порядок** (`McpClient.kt:103-121`):
1. `stopProcess()` — **сначала** убивает дерево subprocess (`descendants().destroyForcibly()` +
   `destroy` + `waitFor(2s)` + `destroyForcibly`). Это даёт серверу EOF на stdin.
2. `withTimeout(5s) { client?.close() }` — graceful JSON-RPC shutdown; отрабатывает быстро, т.к.
   процесс уже мёртв → reader получил EOF → transport-scope завершён.

Если бы `close()` звался **до** `stopProcess()` — дедлок: SDK ждёт EOF reader'а, а EOF наступает
только после смерти процесса, которая стоит «после» close. Этот баг был латентным со Day 16 и
исправлен в Day 17 (см. `day17-results.md` §3).

---

## Часть 5. Ручная отладка сервера (без агента)

Сервер можно дёрнуть напрямую по JSON-RPC, чтобы убедиться, что он отвечает. **stdin — запросы,
stdout — чистый JSON-RPC, stderr — логи.**

**Простейшая проверка (initialize + tools/list):**
```bash
CLI_AGENT_GITHUB_TOKEN="<pat>" \
mcp-server/build/install/mcp-server/bin/mcp-server <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0.0.1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
EOF
```
Ожидаемо в stdout: `initialize` с `capabilities:{tools:{}}` и `tools/list` → `get_repo` со schema
`owner`/`repo`.

**Проверка tool-вызова (нужен реальный PAT):**
```bash
CLI_AGENT_GITHUB_TOKEN="<pat>" \
mcp-server/build/install/mcp-server/bin/mcp-server <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0.0.1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_repo","arguments":{"owner":"JetBrains","repo":"kotlin"}}}
EOF
```
→ `TextContent` со сводкой: `Repository: JetBrains/kotlin | Stars: N | Language: Kotlin | ...`.

**Без токена** — `tools/call` вернёт `isError:true` («GitHub token ... is not configured»), а
`initialize`/`tools/list` отработают нормально (токен нужен только в handler'е).

> Замечание: в stdout сервера не должно быть **ничего**, кроме JSON-RPC. Поэтому `kotlin-logging`
> startup-сообщение явно подавлено (`KotlinLoggingConfiguration.logStartupMessage = false`), а
> slf4j-логи заведены в stderr через системные свойства. Любой лишний `println` в stdout сломает
> протокол.

---

## Часть 6. Полный чек-лист «от нуля до рабочего tool-call»

1. ✅ `./gradlew :mcp-server:installDist` — собран бинарник
   `mcp-server/build/install/mcp-server/bin/mcp-server`.
2. ✅ Получен GitHub PAT (scope `public_repo`).
3. ✅ Есть `CLI_AGENT_API_KEY` (z.ai).
4. ✅ Ручная JSON-RPC-проверка (§5) отвечает `get_repo` и возвращает сводку.
5. ✅ Запуск агента с тремя env (§4.2).
6. ✅ В REPL вопрос про репозиторий → агент вызывает `get_repo` → ответ со stars/описанием.
7. ✅ После `/exit`/Ctrl+D — `ps aux | grep mcp-server` не находит процессов (корректный cleanup).
8. ✅ Обычный вопрос без tool (например «привет») — отвечает как раньше, без tool-call.

---

## Краткая шпаргалка

```bash
# Сборка
./gradlew :mcp-server:installDist

# Запуск агента с подключённым сервером
CLI_AGENT_MCP_COMMAND="$PWD/mcp-server/build/install/mcp-server/bin/mcp-server" \
CLI_AGENT_GITHUB_TOKEN="<pat>" \
CLI_AGENT_API_KEY="<key>" \
./gradlew run --args="chat"
```

**Суть:** сервер отдельно собирается (`installDist`) → агент получает путь к нему через
`CLI_AGENT_MCP_COMMAND` → при первом tool-вопросе лениво поднимает subprocess по stdio → LLM через
function-calling вызывает `get_repo` → результат идёт в контекст → финальный ответ. Токен GitHub
наследуется subprocess'ом из env агента, не логируется. Соединение закрывается в `finally` при выходе
из REPL (`stopProcess` → `close`, порядок критичен).

---

Git не трогал (правила проекта).
