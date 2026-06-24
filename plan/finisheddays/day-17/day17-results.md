# День 17 — Итоги реализации

> Собственный MCP-сервер + agent tool-use loop. Функция-calling (LLM сама вызывает tool и
> использует результат). Local stdio transport (согласовано с фактической реализацией Day 16).
> API-таргет: **GitHub REST API (read-only, `get_repo`)** — выбран после сравнения с Telegram
> (GitHub даёт содержательный read-only tool «из коробки» без write-обвязки; Telegram
> `send_message` — write-action, отложен на Day 18 с confirmation-flow).

План: `~/.claude/plans/ancient-conjuring-nygaard.md` (промт-спецификация + декомпозиция T1–T8 +
5 вариантов API + будущая декомпозиция Day 18+).

---

## Что построено (полный LLM function-calling цикл)

| # | Задача | Артефакт |
|---|---|---|
| T1 | Gradle-субпроект `:mcp-server` (application) | `settings.gradle.kts` (`include(":mcp-server")`), `mcp-server/build.gradle.kts` |
| T2 | MCP-сервер над GitHub REST (read-only `get_repo`) | `mcp-server/src/main/kotlin/com/cliagent/mcp/server/GitHubMcpServer.kt` — `Server`+`StdioServerTransport`+`addTool`; Ktor `GET /repos/{owner}/{repo}` (заголовки `Authorization: Bearer`, `Accept`, `User-Agent`); allowlist-валидация owner/repo (`[A-Za-z0-9._-]+`, защита от path-injection); tool-error при отсутствии токена |
| T3 | `McpClient.callTool` + доменный `McpToolResult` | `src/main/kotlin/com/cliagent/mcp/McpToolResult.kt`, правки в `mcp/McpClient.kt` |
| T4 | LLM function-calling модели | `llm/model/ToolDefinition.kt` (`ToolDefinition`/`FunctionDef`/`ToolCall`/`ToolCallFunction`), `ChatRequest.tools`/`tool_choice`, `ChatMessage.toolCalls`/`toolCallId` + `content: String = ""`, `coerceInputValues=true` в `OpenAiCompatibleClient`, `McpTool.toToolDefinition()` |
| T5 | `ToolExecutor` seam + persistent `McpToolExecutor` | `agent/ToolExecutor.kt` (interface), `mcp/McpToolExecutor.kt` (impl, lazy-connect), wiring в `ChatCommand` + `toolExecutor?.close()` в `finally` REPL |
| T6 | Agent tool-use loop | `ContextAwareAgent.chat()` — loop с `MAX_TOOL_ROUNDS=4`, исполнение `tool_calls` in-memory (без persist промежуточных assistant/tool-сообщений), `finalizeAssistant` (persist только финального ответа) |
| T7 | Тесты | `AgentToolUseLoopTest` (3), `McpToolMappingTest` (3), `ToolCallSerializationTest` (3) — все зелёные |
| T8 | e2e-верификация | raw JSON-RPC сервера (initialize/tools-list/tools-call) + **integration-тест `McpClientServerIntegrationTest`** (connect/listTools/callTool/close — PASS, opt-in через env) |

---

## Ключевые находки и исправления в процессе

1. **SDK 0.13.0 `Server` не наследует `Protocol`** — нет метода `connect`. Используется
   `server.createSession(transport)` (регистрирует handlers tools/list + tools/call, коннектит
   session, запускает reader/writer/processor). `awaitCancellation()` держит процесс живым; клиент
   (`McpClient.close` → `stopProcess`) убивает его — тот же паттерн, что у reference npx-сервера
   дня 16.

2. **`kotlin-logging` портит stdout.** Init-сообщение «kotlin-logging: initializing... active logger
   factory» пишется в **stdout** через `println` → ломает JSON-RPC-канал MCP. Подавлено через
   `KotlinLoggingConfiguration.logStartupMessage = false`. Потребовалось добавить
   `io.github.oshai:kotlin-logging-jvm:8.0.03` **прямой зависимостью** — `implementation` не
   экспонирует транзитив на compile-classpath. Дополнительно: системные свойства
   `org.slf4j.simpleLogger.defaultLogLevel=warn` подавляют INFO-логи SDK в stderr (надёжнее
   classpath-файла `simplelog.properties`, который не подхватывался при `installDist`).

3. **`McpClient.close()` вис навсегда (latent-баг, касается и Day 16).** SDK
   `transport.closeResources()` делает `scope.coroutineContext[Job]?.join()`, ожидая EOF reader'а
   по stdin → а EOF наступает только когда subprocess умер. Но `stopProcess()` звался **после**
   `client?.close()` → deadlock (join ждёт смерти процесса, stopProcess — после close).
   `withTimeout` не лечил: join блокируется на non-cancellable read. **Исправлено: `stopProcess()`
   сначала** → reader получает EOF → transport scope завершается → `client?.close()` (bounded 5с)
   отрабатывает быстро. Подтверждено: smoke-прогон `connect→listTools→callTool→close→DONE` без hang.

4. **`content: null` в tool_call-ответе LLM** ломал бы парсинг (`ChatMessage.content` non-null без
   default). Добавлены `content: String = ""` (default) + `coerceInputValues = true` в
   `OpenAiCompatibleClient` — null coercion к "".

---

## Верификация

- `./gradlew build` — green (оба модуля: root + `:mcp-server`).
- `./gradlew test` — green; 9 новых тестов (3+3+3) + существующие.
- Integration-тест `McpClientServerIntegrationTest` (opt-in, `CLI_AGENT_MCP_INTEGRATION=1`):
  `tests=1 failures=0 errors=0 time=0.519s` — реальная связка McpClient → GitHubMcpServer
  (connect/listTools → `[get_repo]`/callTool → tool-error без токена → close без hang).
- Сервер проверен и через raw JSON-RPC: `initialize` → capabilities `tools:{}`, `tools/list` →
  `get_repo` (schema owner/repo), `tools/call` → `isError:true` (token not configured).
- stdout сервера — pure JSON-RPC (kotlin-logging init подавлен); INFO-логи SDK — в stderr, drained
  клиентом.

### Что НЕ проверено live (нужны креды пользователя)
- Реальный GitHub-вызов (PAT) — handler-логика простая, проверена через error-path.
- Возврат `tool_calls` от GLM-5.1 — loop покрыт юнит-тестами с mock'ом.

### Команды для ручного e2e
```bash
./gradlew :mcp-server:installDist
CLI_AGENT_MCP_COMMAND="$PWD/mcp-server/build/install/mcp-server/bin/mcp-server" \
CLI_AGENT_GITHUB_TOKEN=<pat> CLI_AGENT_API_KEY=<key> \
./gradlew run --args="chat"
# REPL: «Что знает агент о репозитории JetBrains/kotlin?»
#   → LLM зовёт get_repo → финальный ответ со stars/языком/описанием
```

**Риск GLM-5.1:** если модель не возвращает `tool_calls` — fallback в коде (`loadToolsOrNull` при
ошибке → `tools=null`, агент отвечает без tools). При необходимости — переключиться на `glm-4.6`
(поддерживает function-calling). Проверяется live-запуском выше.

---

## Изменённые/новые файлы

**Новые:**
- `mcp-server/build.gradle.kts`, `mcp-server/src/main/resources/simplelog.properties`
- `mcp-server/src/main/kotlin/com/cliagent/mcp/server/GitHubMcpServer.kt`
- `src/main/kotlin/com/cliagent/mcp/McpToolResult.kt`
- `src/main/kotlin/com/cliagent/mcp/McpToolExecutor.kt`
- `src/main/kotlin/com/cliagent/agent/ToolExecutor.kt`
- `src/main/kotlin/com/cliagent/llm/model/ToolDefinition.kt`
- `src/test/kotlin/com/cliagent/agent/AgentToolUseLoopTest.kt`
- `src/test/kotlin/com/cliagent/mcp/McpToolMappingTest.kt`
- `src/test/kotlin/com/cliagent/mcp/McpClientServerIntegrationTest.kt`
- `src/test/kotlin/com/cliagent/llm/model/ToolCallSerializationTest.kt`

**Изменённые:**
- `settings.gradle.kts` — `include(":mcp-server")`
- `src/main/kotlin/com/cliagent/mcp/McpClient.kt` — `callTool` + `close()` (stopProcess-сначала fix)
- `src/main/kotlin/com/cliagent/mcp/McpTool.kt` — `toToolDefinition()`
- `src/main/kotlin/com/cliagent/llm/model/ChatRequest.kt` — `tools`, `toolChoice`
- `src/main/kotlin/com/cliagent/llm/model/ChatMessage.kt` — `toolCalls`, `toolCallId`, `content=""`
- `src/main/kotlin/com/cliagent/llm/OpenAiCompatibleClient.kt` — `coerceInputValues=true`
- `src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt` — `toolExecutor` param + tool-use loop
- `src/main/kotlin/com/cliagent/cli/ChatCommand.kt` — `McpToolExecutor` wiring + `finally { close() }`
- `plan/finisheddays/day-16/global-plan.md` — Phase 5.2 → ✅, «Дни 1–17», env `CLI_AGENT_GITHUB_TOKEN`

---

## Будущая декомпозиция (Day 18+)

- **Day 18 — оркестрация нескольких tools:** 2+ read-only tools на GitHub-сервере (`search_code`,
  `list_issues`, `get_issue`, `get_file_content`); LLM выбирает/чейнит (search_code → get_file_content).
- **Day 18 — рендер inputSchema** в `/mcp list-tools` (deferred §4); **валидация аргументов** перед
  `callTool` (deferred §4); **shell-токенизатор** для `/mcp call`/override (deferred §2).
- **Day 18–19 — persistent-connection харденинг:** reconnect при падении сервера; Telegram
  `send_message` (write-action с confirmation/allowlist/audit) — кандидат на второй сервер.
- **Day 19 — MCP в реальной рабочей задаче:** обернуть внутренний API (таск-трекер/CI); read-only
  scope, audit.
- **Day 20 — сравнение MCP vs Skill+CLI:** тот же сценарий через MCP и через bash-skill (`gh` CLI);
  token-flow report (idle overhead, batching, schema weight); воспроизводимые offline-замеры.
- **Опц. — remote Streamable HTTP transport** (исходный фрейминг саммари для Day 16): альтернативный
  transport, DeepWiki; не требуется выданным заданием.

---

## 5 вариантов API (cheap → expensive) — из плана

| # | API | Auth | Что оборачиваем | Простота |
|---|---|---|---|---|
| 1 | Mock/deterministic | — | in-process данные | ★★★★★ |
| 2 | Open-Meteo (weather) | — | `get_forecast(lat,lon)` | ★★★★☆ |
| 3 | **GitHub REST (read-only)** 🏆 | PAT | `get_repo`, `search_code`, `list_issues` | ★★★☆☆ |
| 4 | Telegram Bot (`send_message`) ✨ | Bot token | messaging — write-action | ★★☆☆☆ |
| 5 | Яндекс.Трекер / Jira | OAuth/IAM | multi-tool: issues, comments | ★☆☆☆☆ |

- 🏆 **Лучший (реализация Day 17): #3 GitHub REST (read-only).** Содержательный read-only tool «из
  коробки», безопасный least-privilege без write-обвязки; PAT проще OAuth; ложится в coding-CLI.
- ✨ **Nice-to-have: #4 Telegram `send_message`.** Яркий demo «агент прислал в Telegram», маркетно.
  Но write-action → по саммари нужен confirmation/allowlist/audit (отдельный scope) → Day 18+.

---

Git не трогал (правила проекта).
