# CLI Agent — Kotlin LLM Agent

## Project Overview

Incremental CLI-agent для **AI Advent Challenge #8**. Kotlin, растёт с каждой неделей курса.

**Previous work (Android client):** [llm-chat-demo-app](https://github.com/sukhoikms27/llm-chat-demo-app) —
референс при миграции фич в CLI.

**Текущая фаза:** **Неделя 6 (Days 26-30)** — локальные LLM: запуск (Ollama), интеграция в CLI,
RAG поверх локальной модели, оптимизация (native client, model limits), приватный сервис на VPS
(Ollama + Caddy reverse-proxy + TLS). Смержено через PR #18 (`task/day-30`) + `7 week prepare`
(коммит-подготовка, **не** старт недели 7). Dev-дневник — до day-30 (`swarm-report/`).
> Note: `README.md` в корне частично устарел (там «Неделя 3»); реальный прогресс см. в этом файле и в git-логе.
> **Неделя 7 (Days 31-35)** — production AI-системы (dev-assistant, PR-review pipeline, support agent,
> file agent, day-35 капстоун «ship a real app») — к ней ещё **не приступали**.

## Plan & docs map

| Что | Где |
|---|---|
| Глобальная архитектура и фазы | `plan/finisheddays/day-16/global-plan.md` |
| Course assignments (day 12-35) | `plan/newdays/dayNN.md` |
| Completed days 1-10 | `plan/finisheddays/day-01.md` … `day-10.md` (плоские) |
| Completed days 11-25 | `plan/finisheddays/day-11/` … `day-25/` (папки с README + задачами) |
| Архитектурные issues / bugs / OOP | `plan/extensions/` (`arch-issues.md`, `bugs.md`, `oop-issues.md`, `critical-issues.md`, `file-operations-design.md`, `05-…`-`08-…`) |
| Dev-дневник (day-26..day-30, мотиватор, ollama, SSE) | `swarm-report/` |
| Видео-саммари курса (RU) | `plan/videossummary/` |
| Результаты прогона | `plan/results/console-result.md` |

> Старые пути `plan/days/`, корневой `global-plan.md`, `plan/changelog-android-diff.md` — **не существуют** (удалены/перемещены).

## Tech Stack

| Component | Choice |
|---|---|
| Language / JVM | Kotlin, JVM 21 |
| Build | Gradle + Kotlin DSL, **multi-module** (`settings.gradle.kts`: root + `:mcp-server` + `:web-app`) |
| CLI framework | clikt 4.4.0 |
| REPL engine | JLine3 3.29.0 |
| Terminal output | mordant 2.5.0 (ANSI, таблицы, спиннеры) |
| HTTP client | Ktor Client 3.4.3 (core + cio + content-negotiation + json) |
| Serialization | kotlinx.serialization 1.11.0 |
| Coroutines | kotlinx-coroutines 1.11.0 |
| MCP SDK | `io.modelcontextprotocol:kotlin-sdk-{client,server}:0.13.0` |
| Persistence | JSON files (one per chat), atomic write |
| LLM providers | z.ai (GLM-5.1, default) · Ollama (qwen2.5, local/VPS) · generic OpenAI-compatible |
| Streaming | SSE via Ktor (**выполнено**, не «Phase 2 placeholder») |
| Logging | slf4j-simple 2.0.18 (runtime) |
| Testing | JUnit 5.12.2 + MockK 1.13.16 + kotlinx-coroutines-test + ktor-client-mock |
| Distribution | Shadow JAR (fat-jar в `mcp-server`) |

## Build & test commands

Gradle wrapper (`./gradlew`), Kotlin DSL, **3 модуля** (`:cli-agent` root + `:mcp-server` + `:web-app`).

| Что | Команда |
|---|---|
| Run CLI-agent (REPL chat) | `./gradlew run --args="chat"` (stdin проброшен через `tasks.withType<JavaExec>`) |
| Compile all modules | `./gradlew build -x test` |
| Unit tests (root) | `./gradlew test` |
| Тест одного класса | `./gradlew test --tests "com.cliagent.rag.RagRetrieverTest"` |
| Тесты всех модулей | `./gradlew :test :mcp-server:test :web-app:test` |
| Run mcp-server | `./gradlew :mcp-server:run` |
| Run web-app (Ktor) | `./gradlew :web-app:run` |
| Fat-jar mcp-server | `./gradlew :mcp-server:shadowJar` → `mcp-server/build/libs/mcp-server-*-all.jar` |

**E2E-тесты gated** (`McpClientHttpE2ETest`, интеграционный stdio-тест MCP) — по умолчанию **skipped**,
не запускаются обычным `./gradlew test`. Чтобы включить, пробросить флаг в test-JVM:
`./gradlew test -Dcli-agent.e2e.http=true -Dcli-agent.mcp.jar=<path>` (или env `CLI_AGENT_MCP_INTEGRATION`).
Без флага — тихо skip, это не ошибка.

## Modules

- **root** (`src/main/kotlin/com/cliagent/`) — сам CLI-агент (entry: `com.cliagent.MainKt`).
- **`:mcp-server`** (`mcp-server/src/main/kotlin/com/cliagent/mcp/server/`) — standalone MCP-сервер с
  multi-tool: GitHub, Weather (scheduler + store), Wikipedia, Notes. Fat-jar via Shadow.
- **`:web-app`** (`web-app/src/main/kotlin/com/cliagent/web/`) — motivator: `MotivatorApp` +
  `SessionManager` + статический фронтенд (`web-app/src/main/resources/static/`).

## Architecture

```
CLI Layer (clikt + JLine3 REPL + mordant)
        ↓
Agent Layer (SimpleAgent / ContextAwareAgent / StatefulAgent / StageAgent / SwarmStageAgent)
        ↓
┌────────────┬─────────────┬──────────────┬─────────┬─────────┐
│ LLM Layer  │ Context     │ Memory+State │ RAG     │ MCP     │
│ (llm/)     │ (context/)  │ (memory/     │ (rag/)  │ (mcp/)  │
│ + Factory  │ + strategy  │  +state/)    │         │         │
└────────────┴─────────────┴──────────────┴─────────┴─────────┘
        ↓
Infrastructure (JSON files, Ktor, Config, XDG paths)
```

### Key Interfaces
- `LlmClient` → `LlmResult<ChatResponse>`. Реализации: `OpenAiCompatibleClient` (zai/openai/ollama-via-v1),
  `OllamaNativeClient` (`/api/chat`). Dispatch через `LlmClientFactory` + `LlmProvider` enum.
- `ContextStrategy` — SlidingWindow / StickyFacts / Summary / Branching (4 стратегии в `context/strategy/`).
- `MemoryStore` (JsonChatStore, JsonLongTermStore) + `MemoryLayer` (short/working/long-term).
- `Agent` — `chat()` / `getHistory()` / `reset()`. Stage-варианты: `ClarifyStageAgent`,
  `PlanningStageAgent`, `ExecutionStageAgent`, `ValidationStageAgent`, `DoneStageAgent`;
  swarm-вариант: `SwarmStageAgent` (lead + workers + integrate).
- `McpToolExecutor` / `CompositeMcpToolExecutor` — вызов внешних MCP-тулов как agent tools.

## Project Structure

```
src/main/kotlin/com/cliagent/
├── Main.kt                          # entry (clikt delegation)
├── cli/                             # CliAgentCommand, ChatCommand, ReplEngine, AgentSession,
│                                    #   AppTerminal, RagCommands, LocalRagCompare, LocalSmoke
├── agent/                           # Agent, SimpleAgent, ContextAwareAgent, StatefulAgent,
│   ├── stage/                       #   InvariantGuard, ProfileExtractor, PromptBuilder, ToolExecutor
│   │                                #   StageAgent + 5 stage-агентов + 5 классификаторов +
│   │                                #   PlanParser, StageAnnouncer, TaskOrchestrator, StepAgent
│   └── swarm/                       # SwarmMode, SwarmPrompts, SwarmStageAgent, SwarmStrategy
├── config/                          # AppConfig, AppPaths, ConfigRepository, OllamaTunables, SamplingTunables
├── context/
│   ├── strategy/                    # ContextStrategy + 4 impls
│   ├── ContextManager.kt            # strategy switcher
│   └── HistoryCompressor.kt         # incremental summarization
├── llm/
│   ├── LlmClient.kt, LlmClientFactory.kt, LlmProvider.kt, LlmResult.kt, LlmCallException.kt
│   ├── OpenAiCompatibleClient.kt, OllamaNativeClient.kt, OllamaBenchClient.kt, OllamaHealthChecker.kt
│   ├── BenchmarkRunner.kt, ModelDefaults.kt, ModelLimits.kt
│   ├── model/                       # ChatMessage, ChatRequest, ChatResponse, ModelInfo,
│   │                                #   SystemPrompts, PromptTemplates, ReasoningStrategy,
│   │                                #   StagePromptTemplates, StreamChunk, ToolDefinition, BenchmarkResult
│   ├── token/                       # TokenCounter, OutputBudget
│   └── pricing/                     # Pricing
├── mcp/                             # McpClient, McpServerConfig, McpTransportConfig, McpTool,
│                                    #   McpToolExecutor, CompositeMcpToolExecutor, McpToolResult, McpException
├── memory/                          # MemoryLayer, MemoryStore, ChatData, JsonChatStore, JsonLongTermStore
├── rag/
│   ├── RagModels, RagFactories, RagIndexer, RagRetriever, JsonRagStore,
│   │   DocumentLoader, VectorMath, CitationDetector, ChunkingComparison, CannedResponses
│   ├── chunk/                       # ChunkingStrategy, FixedSizeChunker, StructuralChunker
│   ├── embedding/                   # EmbeddingClient, OllamaEmbeddingClient (/api/embed)
│   ├── rerank/                      # Reranker, HeuristicReranker, LlmReranker, ThresholdReranker
│   └── rewrite/                     # QueryRewriter, HeuristicQueryRewriter, LlmQueryRewriter
├── state/
│   ├── TaskStage, TaskState, TaskStateMachine, TaskKind, TaskComplexity,
│   │   InteractionMode, TransitionGuard, TransitionOutcome
│   └── invariant/                   # Invariant, InvariantResult, InvariantChecker, LlmInvariantChecker
└── (tests mirror packages under src/test/kotlin/com/cliagent/)
```

`mcp-server/` и `web-app/` — отдельные деревья, см. раздел Modules.

## Implementation Phases (статус)

| Фаза | Дни | Статус |
|---|---|---|
| Phase 1: Foundation | Days 1-6 (LlmClient, модели, REPL, config) | ✅ |
| Phase 2: Context & Parameters | Days 3-5, 7-8 (temp/top_p/max_tokens, presets, JSON storage, TokenCounter, `/stats` `/cost`, **SSE streaming**) | ✅ |
| Phase 3: Context Management | Days 9-10 (HistoryCompressor, 4 стратегии, `/strategy`) | ✅ |
| Phase 4: Stateful Agent | Week 3 (Profile, TaskStateMachine, InvariantChecker, StatefulAgent, PromptBuilder) | ✅ |
| Week 4: Tools + MCP | Days 16-20 (agent tools, MCP client+server, multi-tool server) | ✅ |
| Week 5: RAG | Days 21-25 (chunking, embeddings, rerank, query rewrite, citations, `/rag`) | ✅ |
| **Week 6: Local LLM** | **Days 26-30 (Ollama provider, native client, model limits, SSE, RAG-over-local, VPS-сервис) ← CURRENT** | 🚧 |
| Week 7: Production agents | Days 31-35 (dev-assistant, PR-review pipeline, support agent, file agent, capstone «ship») | ⏳ не начата |

## LLM API Details (multi-provider)

| Provider | Endpoint | Auth | Когда |
|---|---|---|---|
| `zai` (default) | `https://api.z.ai/api/coding/paas/v4/chat/completions` | Bearer `CLI_AGENT_API_KEY` | Облако, GLM-5.1 |
| `ollama` | `http://localhost:11434/v1/chat/completions` (OpenAI-compat) **или** `/api/chat` (native) | пустой apiKey | Локально / VPS, qwen2.5 |
| `openai-compatible` | любой `$BASE_URL/chat/completions` | Bearer apiKey | Прочее |

**z.ai request/response** — стандартный OpenAI Chat Completions (`messages[]`, `model`, `temperature`, `max_tokens`),
response содержит `choices[].message` и `usage.{prompt,completion,total}_tokens`.

### Environment variables
- `CLI_AGENT_API_KEY` — облако (zai/openai-compatible): required. Ollama: не нужен.
- `CLI_AGENT_MODEL` — default `glm-5.1`. Для Ollama: `qwen2.5:32b-instruct-q5_K_M` (~20 ГБ RAM, 128K контекст) либо `qwen2.5:14b-instruct-q5_K_M` (fallback).
- `CLI_AGENT_BASE_URL` — default z.ai; для Ollama `http://localhost:11434/v1`.
- `CLI_AGENT_PROVIDER` — `zai` | `ollama` | `openai-compatible`. Пусто → auto-detect по `baseUrl`.
- `XDG_DATA_HOME` / `XDG_CONFIG_HOME` — override директорий (default `~/.local/share`, `~/.config`).

### Запуск с локальной моделью
```
CLI_AGENT_PROVIDER=ollama \
CLI_AGENT_BASE_URL=http://localhost:11434/v1 \
CLI_AGENT_MODEL=qwen2.5:32b-instruct-q5_K_M \
./gradlew run --args="chat"
```
Либо через REPL: `/config set provider ollama` + `set baseUrl …` + `set model …` (применяется после рестарта `chat`).

Per-model лимиты (`ModelLimitsRegistry`) кормят `OutputBudget`: локальные модели получают корректный `max_tokens` (qwen2.5: 128K context / 8K output), неизвестные → консервативный default.

## Подсистемы — кратко

- **MCP** (`mcp/` + `:mcp-server`) — клиент подключается к stdio/HTTP MCP-серверам и экспонирует их туулы как agent tools; отдельный Gradle-модуль — собственный multi-tool сервер (GitHub/Weather/Wikipedia/Notes).
- **RAG** (`rag/`) — индексация (.md/.kt → chunks → embeddings), retrieval с rerank + query rewrite, детекция цитат, `/rag` команды.
- **Swarm** (`agent/swarm/`) — multi-agent режим: lead + workers + integrate на каждой стадии FSM.
- **State** (`state/`) — FSM задач (clarify → planning → execution → validation → done) + invariant checker (programmatic + LLM).

## Deployment (day 30, week 6 — приватный LLM-сервис)

- `deploy/vps/` — `Caddyfile` (reverse-proxy + TLS), `Modelfile.qwen25-7b`, `deploy.sh`, `ollama.service.override.conf`, README.
- `scripts/deploy/` — `install-mcp-server.sh`, `uninstall-mcp-server.sh`, `update-jar.sh`, `setup-nginx-tls.sh`, README.

## Development Conventions

- Kotlin naming (camelCase / PascalCase); пакеты lowercase без подчёркиваний.
- Error handling: sealed `AgentResult<T>` / `LlmResult<T>`, без исключений для flow control.
- Exit codes: POSIX sysexits (`ExitCodes`).
- Coroutines: suspend для IO, `SupervisorJob` в REPL, **никогда не глотать** `CancellationException`.
- Serialization: `@Serializable` везде, единый `AppJson` (`ignoreUnknownKeys=true`).
- Persistence: JSON, atomic write (temp+rename), XDG-пути. Эволюция схемы: только add-with-default.
- Testing: JUnit 5 + MockK (unit), `cmd.parse()` (clikt), `runTest` (coroutines).
- CLI output: данные → stdout, ошибки → stderr, цвета через mordant.
- **Git: totally blocks git usage** — все VCS-операции только через пользователя.
- **Поиск по коду: use ast-index tool** каждый раз, когда нужно что-то найти в проекте.

## Before editing sensitive areas, read

| Меняешь… | Сначала прочитай |
|---|---|
| Архитектуру / слои / фазы | `plan/finisheddays/day-16/global-plan.md` |
| Context strategies, HistoryCompressor | `plan/extensions/arch-issues.md`, `oop-issues.md` |
| Любой bug / known issue | `plan/extensions/bugs.md`, `critical-issues.md` |
| File operations / tools | `plan/extensions/file-operations-design.md` |
| RAG retrieval (шум/коллизии/мультиязычность) | `plan/extensions/rag-noisy-corpus.md` |
| Multi-provider LLM (`LlmClientFactory`) | раздел LLM API Details выше + `llm/ModelLimits.kt` |
| Kotlin CLI-паттерны (JLine3/mordant/корутины/JSON/ошибки/clikt) | workspace-skill `.zcode/skills/kotlin-cli-patterns/` (+ `references/*.md`) |
| Хронологию решений по дню N | `plan/finisheddays/day-N/README.md`, `swarm-report/day-N-*.md` |

> **Kotlin CLI паттерны** (JLine3 REPL, mordant, корутины, сериализация, JSON-персистентность,
> error handling, clikt) — вынесены в workspace-skill **`kotlin-cli-patterns`**
> (`.zcode/skills/kotlin-cli-patterns/`). Туда — за развёрнутыми примерами кода.
