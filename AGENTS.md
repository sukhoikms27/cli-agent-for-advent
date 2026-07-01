# CLI Agent — Kotlin LLM Agent

## Project Overview

Incremental CLI-agent for the AI Advent Challenge #8 course. Built with Kotlin, grows with each week's assignments.

**Previous work (Android client):** [llm-chat-demo-app](https://github.com/sukhoikms27/llm-chat-demo-app) — репозиторий с предыдущими наработками курса на Android-клиенте. Используется как референс при миграции фич в CLI.

**Current phase:** Phase 1 (Foundation — Days 1-6 MVP)

**Detailed day-by-day plans:** `plan/days/day-01.md` — `plan/days/day-10.md` — инструкции для реализации каждого дня курса с учётом нарастающих изменений.
**Global architecture:** `global-plan.md` — верхнеуровневый план архитектуры и фаз.
**Android comparison:** `plan/changelog-android-diff.md` — что принято и отвергнуто из Android-реализации.

## Kotlin CLI Development Meta-Prompt

Правила и паттерны для разработки CLI-тулов на Kotlin. Применимы к этому проекту и любому будущему CLI.

### REPL: JLine3, не readLine()

Использовать [JLine3](https://github.com/jline/jline3) для REPL-цикла. Даёт из коробки:
- История команд (персистентная между сессиями)
- Tab-completion для slash-команд и аргументов
- Корректная обработка Ctrl+C (`UserInterruptException`) и Ctrl+D (`EndOfFileException`)
- Редактирование строки (стрелки, Ctrl+W, Ctrl+U и пр.)

```kotlin
val terminal = TerminalBuilder.builder().system(true).build()
val reader = LineReaderBuilder.builder()
    .terminal(terminal)
    .completer(AggregateCompleter(/* command completers */))
    .variable(LineReader.HISTORY_FILE, dataDir.resolve("repl-history").toString())
    .build()

while (true) {
    try {
        val line = reader.readLine("cli-agent> ")
        // handle input
    } catch (e: UserInterruptException) { /* Ctrl+C — cancel input, not REPL */ }
      catch (e: EndOfFileException) { break } // Ctrl+D — exit
}
```

### Цветной вывод: mordant

[mordant](https://github.com/ajalt/mordant) — от того же автора, что clikt. Даёт:
- ANSI-цвета с auto-detect и `--no-color` fallback
- Таблицы для `/stats`
- Спиннеры для загрузки

```kotlin
val t = Terminal()
t.println("${red("Error:")} API key not found")
t.println("${green("✓")} Configuration saved")
```

### Корутины в CLI

| Паттерн | Правило |
|---|---|
| Точка входа | `runBlocking` только в `main()` / clikt `run()` |
| REPL-цикл | `SupervisorJob` — одна ошибка не крашит сессию |
| IO-операции | `withContext(Dispatchers.IO)` — Ktor, файлы |
| CPU-операции | `withContext(Dispatchers.Default)` — подсчёт токенов, компрессия |
| CancellationException | **Никогда не глотать** — всегда re-throw |
| Долгие запросы | `withTimeoutOrNull(30_000L)` — таймаут 30с на LLM-запрос |
| Отмена по Ctrl+C | Отменять `currentJob?.cancel()`, не убивать процесс |

### Сериализация: единый Json-инстанс

Один `Json` объект на всё приложение с правильными настройками:

```kotlin
val AppJson = Json {
    ignoreUnknownKeys = true    // forward compat: новые поля не ломают старый код
    encodeDefaults = true       // все поля всегда в JSON — явнее
    explicitNulls = false       // null-поля не пишутся — компактнее
    coerceInputValues = true    // неизвестные enum → default
    prettyPrint = false         // компактный JSON для storage; prettyPrint для /export
}
```

**Эволюция схемы** — добавлять поля только с дефолтами, никогда не удалять:
```kotlin
@Serializable
data class ChatData(
    val id: String,
    val title: String = "New Chat",              // v1: default для новых чатов
    val messages: List<ChatMessage> = emptyList(),
    val summary: String? = null,                   // v2: nullable — старые чаты загружаются
    val facts: Map<String, String> = emptyMap(),   // v3: default — бесшовная миграция
)
```

### Файловая персистентность: JSON, не SQLite

Для CLI-агента (1-5 чатов, 10-100 сообщений) JSON быстрее и проще SQLite:
- Нет JDBC overhead (коннект, SQL-парсинг, ResultSet mapping)
- Нет миграций — меняешь data class, всё работает через defaults
- Нет зависимости sqlite-jdbc (~8MB)
- Один файл на чат, человекочитаемый

**Структура каталогов (XDG):**
```
$XDG_DATA_HOME/cli-agent/     (~/.local/share/cli-agent/ на Linux, ~/Library/Application Support/cli-agent/ на macOS)
├── config.json                # AppConfig + настройки
├── repl-history               # JLine3 история
└── chats/
    ├── {uuid}.json            # ChatData: messages, summary, facts, branches
    └── {uuid}.json
```

**Атомарная запись** — всегда write-to-temp + rename:
```kotlin
fun atomicWrite(target: Path, content: String) {
    val tmp = target.resolveSibling(".${target.fileName}.tmp")
    tmp.writeText(content)
    Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
}
```

**XDG-пути:**
```kotlin
object AppPaths {
    val dataDir: Path = System.getenv("XDG_DATA_HOME")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".local", "share", "cli-agent")
    val configDir: Path = System.getenv("XDG_CONFIG_HOME")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".config", "cli-agent")
    val chatsDir: Path get() = dataDir.resolve("chats")
}
```

### Обработка ошибок: sealed class + exit codes

```kotlin
sealed class AgentResult<out T> {
    data class Success<out T>(val value: T) : AgentResult<T>()
    data class LlmError(val code: Int?, val message: String, val retryable: Boolean) : AgentResult<Nothing>()
    data class ConfigError(val message: String, val suggestion: String? = null) : AgentResult<Nothing>()
    data class IoError(val path: String, val message: String) : AgentResult<Nothing>()
}

// Exit codes (POSIX sysexits)
object ExitCodes {
    const val SUCCESS = 0
    const val USAGE = 2         // плохие аргументы
    const val DATA_ERR = 65     // невалидные данные
    const val NO_INPUT = 66     // файл не найден
    const val UNAVAILABLE = 69  // сервис недоступен (LLM down, rate limit)
    const val CONFIG = 78       // ошибка конфигурации (нет API key)
}
```

**stderr vs stdout:** данные → stdout (можно пайпать), ошибки → stderr.

### clikt: продвинутые паттерны

- **Shell completions:** `.subcommands(CompletionCommand())` — автодополнение для bash/zsh/fish
- **Валидация:** `.validate { require(it in 0f..2f) }` на опциях
- **Кросс-опционная валидация:** в `run()` после парсинга всех опций
- **Custom types:** `.convert { Url(it) }` для нетривиальных типов
- **Env vars:** `option("--api-key", envvar = "CLI_AGENT_API_KEY")` — clikt автоматически читает из env

### Тестирование CLI

| Слой | Подход |
|---|---|
| clikt-команды | `cmd.parse(listOf("--model", "glm-5.1"))` + assert на свойствах |
| Валидация | `assertFailsWith<UsageError> { cmd.parse(...) }` |
| Агент (unit) | MockK + `runTest` + `coEvery`/`coVerify` |
| REPL-команды | Тестировать `handleCommand()` изолированно |
| Интеграция | `ProcessBuilder` — запуск JAR как subprocess |
| Персистентность | Temp-файл + `@BeforeEach`/`@AfterEach` |

### Дистрибуция

1. **Shadow JAR** — fat JAR, работает везде где есть Java: `java -jar cli-agent.jar chat`
2. **GraalVM native-image** — нативный бинарник без JVM, быстрый старт (Phase 2)
3. **Homebrew** — формула в tap-репозитории

---

## Tech Stack

| Component | Choice |
|---|---|
| Language | Kotlin |
| Build | Gradle + Kotlin DSL (`build.gradle.kts`) |
| CLI framework | clikt |
| REPL engine | JLine3 |
| Terminal output | mordant (ANSI colors, tables, spinners) |
| HTTP client | Ktor Client |
| Serialization | kotlinx.serialization |
| Persistence | JSON files (one per chat) |
| LLM provider | z.ai (GLM-5.1) — OpenAI-compatible API |
| API format | OpenAI Chat Completions (`/v4/chat/completions`) |
| Streaming | Phase 2 (SSE via Ktor) |
| Testing | JUnit 5 + MockK + kotlinx-coroutines-test |

## Architecture

```
CLI Layer (clikt + JLine3 REPL + mordant output)
        ↓
Agent Layer (orchestration: request → response)
        ↓
┌──────────┬───────────┬───────────────────┐
│ LLM Layer│ Context   │ Memory + State    │
│ (llm/)   │ (context/)│ (memory/ + state/)│
└──────────┴───────────┴───────────────────┘
        ↓
Infrastructure (JSON files, Ktor, Config)
```

### Key Interfaces

- `LlmClient` — abstract LLM communication (current impl: OpenAiCompatibleClient via Ktor, returns `LlmResult<ChatResponse>`)
- `ContextStrategy` — pluggable context management (SlidingWindow, StickyFacts, Summary, Branching — 4 стратегии)
- `MemoryStore` — persistence abstraction (current impl: JsonChatStore — one JSON file per chat)
- `Agent` — agent entity with chat(), getHistory(), reset()

## Project Structure

```
src/main/kotlin/com/cliagent/
├── Main.kt                     # entry point (clikt delegation)
├── cli/                        # CLI layer (clikt commands)
│   ├── CliAgentCommand.kt      # root command
│   ├── ChatCommand.kt          # REPL chat mode (JLine3)
│   ├── ConfigCommand.kt        # config management
│   └── ReplEngine.kt           # JLine3 REPL loop + completion
├── agent/                      # Agent core
│   ├── Agent.kt                # agent interface
│   ├── SimpleAgent.kt          # basic agent (day 6)
│   ├── ContextAwareAgent.kt    # agent with context persistence (day 7)
│   └── StatefulAgent.kt        # stateful agent (week 3)
├── llm/                        # LLM integration
│   ├── LlmClient.kt            # LLM client interface (chat + chatStream placeholder)
│   ├── OpenAiCompatibleClient.kt
│   ├── model/                  # ChatMessage, ChatRequest, ChatResponse, GenerationPresets
│   ├── token/                  # TokenCounter (~4 chars/token)
│   └── pricing/                # Pricing (calculateCost per model)
├── context/                    # Context management
│   ├── ContextManager.kt       # strategy switcher
│   ├── strategy/               # ContextStrategy + 4 impls + ContextStrategyType enum
│   └── HistoryCompressor.kt    # incremental summarization (previous summary + new)
├── memory/                     # Memory & persistence
│   ├── MemoryStore.kt          # storage interface
│   ├── JsonChatStore.kt        # JSON file implementation (one file per chat)
│   ├── ChatData.kt             # aggregate model (messages + summary + facts + branches)
│   ├── Profile.kt              # user profile model
│   └── Facts.kt                # key-value facts from dialog
├── state/                      # Task state machine (week 3)
│   ├── TaskState.kt            # task states enum
│   ├── StateMachine.kt         # state machine with transitions
│   └── InvariantChecker.kt     # invariant validation
├── rag/                        # RAG indexing pipeline (day 21)
│   ├── RagModels.kt            # RagDocument, RagChunk, RagIndex, ScoredChunk, RagConfig
│   ├── DocumentLoader.kt       # corpus scan (.md/.kt → RagDocument)
│   ├── chunk/                  # ChunkingStrategy + FixedSizeChunker + StructuralChunker
│   ├── embedding/              # EmbeddingClient + OllamaEmbeddingClient (/api/embed)
│   ├── VectorMath.kt           # cosine similarity + topK
│   ├── JsonRagStore.kt         # JSON index persistence (atomicWrite)
│   ├── RagIndexer.kt           # chunk → embed → save orchestrator
│   └── ChunkingComparison.kt   # 2-strategy stats + probe retrieval
└── config/                     # Configuration
    ├── AppConfig.kt            # config data class (+rag: RagConfig)
    ├── ConfigRepository.kt     # config load/save (env + JSON)
    └── AppPaths.kt             # XDG-compliant paths (+ragDir)
```

## Implementation Phases

### Phase 1: Foundation (Days 1-6) — MVP ← CURRENT
- Gradle project setup, dependencies, package structure
- LlmClient interface + OpenAiCompatibleClient (Ktor, z.ai endpoint)
- Data models: ChatMessage, ChatRequest, ChatResponse, LlmParameters
- REPL mode via clikt
- AppConfig: API key, model, baseUrl from env/config
- Response format control (system prompt with constraints)

### Phase 2: Context & Parameters (Days 3-5, 7-8)
- ChatRequest: temperature, top_p, max_tokens with CLI flags (flat fields, no GenerationConfig)
- Prompting strategies (step-by-step, expert group) + GenerationPresets
- JSON file storage + context restoration on restart
- TokenCounter: token counting for request, history, response (~4 chars/token estimate)
- CLI commands: /context list, /context clear, /stats, /cost
- Streaming (SSE via Ktor)

### Phase 3: Context Management (Days 9-10)
- HistoryCompressor: incremental summarization (previous summary + new messages)
- SlidingWindowStrategy: keep last N messages
- StickyFactsStrategy: key-value facts + last N messages
- SummaryStrategy: auto-summarization + last N messages (4th strategy)
- BranchingStrategy: dialog branches from checkpoints (persistent in JSON)
- Strategy switcher via CLI: /strategy sliding/facts/summary/branch

### Phase 4: Stateful Agent (Week 3)
- Profile: personalization model (style, constraints, context)
- TaskStateMachine: stages (clarify → plan → execute → validate → done)
- InvariantChecker: programmatic constraint validation
- StatefulAgent: full assembly (profile + state + invariants)
- PromptBuilder: assemble context layers into final prompt

## LLM API Details

**Provider:** z.ai (Zhipu AI)
**Endpoint:** `https://api.z.ai/api/coding/paas/v4/chat/completions`
**Format:** OpenAI-compatible
**Model:** `glm-5.1`
**Auth:** Bearer token (API key in Authorization header)

### Request format
```json
{
  "model": "glm-5.1",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "..."}
  ],
  "temperature": 0.7,
  "max_tokens": 1024
}
```

### Response format
```json
{
  "id": "chatcmpl-...",
  "choices": [{"index": 0, "message": {"role": "assistant", "content": "..."}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}
}
```

## Course Context

This project fulfills the AI Advent Challenge #8 assignments. Each phase maps to course days/weeks:
- Days 1-5: Basic LLM calls, response format, reasoning strategies, temperature, model comparison
- Day 6: First agent (encapsulated entity)
- Day 7: Context persistence (history in JSON, restore on restart)
- Day 8: Token counting
- Day 9: History compression (incremental summary)
- Day 10: Context strategies (Sliding Window, Sticky Facts, Summary, Branching)
- Week 3: Full stateful agent (profile, task state machine, invariants)

## Development Conventions

- **Naming:** Kotlin conventions (camelCase for functions/variables, PascalCase for classes)
- **Packages:** lowercase, no underscores
- **Error handling:** sealed class Result patterns (`AgentResult<T>`, `LlmResult<T>`), no exceptions for flow control
- **Exit codes:** POSIX sysexits — `AgentResult.toExitCode()` for mapping
- **Coroutines:** suspend for all IO, `SupervisorJob` for REPL fault isolation, never swallow `CancellationException`
- **Serialization:** @Serializable on all data classes, single `AppJson` instance with `ignoreUnknownKeys = true`
- **Persistence:** JSON files, atomic writes (temp + rename), XDG-compliant paths
- **Schema evolution:** add fields with defaults, never remove — old JSON always loads
- **Testing:** JUnit 5 + MockK for unit, `cmd.parse()` for clikt, `runTest` for coroutines
- **Logging:** use kotlin.io or add kotlinx-logging if needed
- **Git:** totally blocks git usage
- **CLI output:** data → stdout, errors → stderr, colors via mordant

## Environment Variables

- `CLI_AGENT_API_KEY` — z.ai API key (required)
- `CLI_AGENT_MODEL` — model name (default: glm-5.1)
- `CLI_AGENT_BASE_URL` — API base URL (default: https://api.z.ai/api/coding/paas/v4)
- `XDG_DATA_HOME` — data directory override (default: ~/.local/share)
- `XDG_CONFIG_HOME` — config directory override (default: ~/.config)

### Agent info
- use ast-index tool every time, when you need to find out anything in project
