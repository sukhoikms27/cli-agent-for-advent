# CLI Agent — Глобальный план архитектуры

> Документ фиксирует верхнеуровневую архитектуру CLI-агента на Kotlin,
> спроектированную для курса AI Advent Challenge #8.
> Агент развивается инкрементально — каждая фаза соответствует заданиям курса.

---

## 1. Сводка принятых решений

| Параметр | Решение |
|---|---|
| **Цель** | Инкрементальный агент (растёт вместе с курсом) |
| **Язык** | Kotlin |
| **Билд-система** | Gradle + Kotlin DSL (`build.gradle.kts`) |
| **CLI-фреймворк** | clikt |
| **REPL engine** | JLine3 (LineReader: история, tab-completion, редактирование, Ctrl+C/D) |
| **Terminal output** | mordant (ANSI-цвета, таблицы, спиннеры; auto-detect + `--no-color`) |
| **HTTP-клиент** | Ktor Client |
| **Сериализация** | kotlinx.serialization |
| **Память/персистентность** | JSON-файлы (один на чат + глобальный long-term), атомарная запись |
| **LLM-провайдер** | z.ai (Zhipu AI) — GLM-5.1 |
| **API-формат** | OpenAI-совместимый (`/v4/chat/completions`) |
| **Streaming** | Фаза 2 (SSE через Ktor, инкрементальный рендер через mordant live-line) |
| **Стиль CLI** | REPL по умолчанию + CLI-аргументы для подкоманд |
| **TUI-стратегия** | JLine3 + mordant (внедрено); kotter (декларативный live-TUI) — отложен до потребности в live-панелях todo/tool-use |
| **Выполненные задания курса** | Дни 1–17 (API-вызов → формат → рассуждения → температура → модели → агент → контекст → токены → сжатие → 4 стратегии → модель памяти: short/working/long-term layers → Day 16: MCP-соединение + tools/list по stdio → Day 17: свой MCP-сервер (GitHub `get_repo`) + agent tool-use loop через LLM function-calling) |
| **MCP-интеграция** | Model Context Protocol Kotlin SDK 0.13.0; local stdio transport (subprocess через npx); REPL-команда `/mcp`; remote Streamable HTTP — отложен |

---

## 2. Слои архитектуры

```
┌──────────────────────────────────────────────────────────┐
│                       CLI Layer                           │
│            (clikt + JLine3 REPL + mordant output)         │
│                    cli/ package                           │
├──────────────────────────────────────────────────────────┤
│                      Agent Layer                          │
│             (оркестрация: запрос → ответ)                 │
│              Инкапсулирует логику агента                  │
│                   agent/ package                          │
├────────────┬──────────────┬───────────────────────────────┤
│  LLM Layer │    Context   │      Memory + State           │
│   (llm/)   │  (context/)  │  (memory/ + state/)           │
│            │              │                               │
│  Ktor HTTP │  Стратегии   │  JSON-файлы, MemoryLayers,    │
│  client    │  управления  │  Profile, Facts, StateMachine,│
│            │  контекстом  │  Invariants                   │
├────────────┴──────────────┴───────────────────────────────┤
│                    Infrastructure                          │
│               (JSON files, Ktor, Config)                  │
│                  config/ package                           │
└──────────────────────────────────────────────────────────┘
```

**Принцип разделения:** Каждый слой общается только с соседним снизу.
CLI не знает про LLM — он вызывает Agent. Agent не знает про JSON-хранилище —
он вызывает MemoryStore. Это позволяет менять реализации без каскадных правок.

> **MCP-интеграция (`mcp/`)** — внешний слой: Agent/CLI вызывает `McpClient` (stdio transport →
> subprocess MCP-сервер). MCP-сервер запускается по команде из `AppConfig.mcpCommand`
> (env/local.properties). В Week 04 подключение per-invocation; persistent-соединение и `callTool` —
> Day 17+.

---

## 3. Структура пакетов

```
src/main/kotlin/com/cliagent/
│
├── Main.kt                          # Точка входа (main function)
│
├── cli/                              # ── CLI Layer ──
│   ├── CliAgentCommand.kt            #   Корневая команда (clikt)
│   ├── ChatCommand.kt                #   REPL-режим чата (slash-команды, диспетч)
│   ├── ReplEngine.kt                 #   JLine3 REPL-цикл + completion + история (TUI)
│   └── AppTerminal.kt                #   mordant Terminal-обёртка (цвета/таблицы/спиннеры)
│
├── agent/                            # ── Agent Layer ──
│   ├── Agent.kt                      #   Интерфейс агента
│   ├── SimpleAgent.kt                #   Базовый агент (день 6, in-memory)
│   ├── ContextAwareAgent.kt          #   Агент с контекстом + memory layers (день 7, расширен в день 11)
│   ├── PromptBuilder.kt              #   Сборка слоёного system prompt (день 11)
│   └── ProfileExtractor.kt           #   LLM авто-извлечение UserProfile из диалога (день 12)
│
├── llm/                              # ── LLM Layer ──
│   ├── LlmClient.kt                  #   Интерфейс LLM-клиента (chat + chatStream placeholder)
│   ├── OpenAiCompatibleClient.kt     #   Реализация для z.ai / OpenAI-совместимых
│   ├── model/                        #   Модели данных LLM
│   │   ├── ChatMessage.kt            #     Сообщение (role + content + id + parentId)
│   │   ├── ChatRequest.kt            #     Тело запроса к API (плоское, все параметры inline)
│   │   ├── ChatResponse.kt           #     Тело ответа от API (с cachedTokens в Usage)
│   │   ├── GenerationPresets.kt      #     Объединённые пресеты (формат + стратегия)
│   │   ├── ReasoningStrategy.kt      #     Enum стратегий рассуждения
│   │   ├── PromptTemplates.kt        #     Шаблоны промптов для стратегий
│   │   ├── SystemPrompts.kt          #     Шаблоны контроля формата
│   │   ├── ModelInfo.kt              #     Метаданные моделей (tier, context window, pricing)
│   │   └── BenchmarkResult.kt        #     Результат бенчмарка
│   ├── token/                        #   Подсчёт токенов
│   │   └── TokenCounter.kt           #     Подсчёт для запроса/истории/ответа (день 8)
│   └── pricing/                      #   Расчёт стоимости
│       └── Pricing.kt                #     calculateCost() по моделям
│
├── context/                          # ── Context Layer ──
│   ├── ContextManager.kt             #   Менеджер контекста (переключатель стратегий)
│   ├── strategy/                     #   Стратегии управления контекстом
│   │   ├── ContextStrategy.kt        #     Интерфейс стратегии (+ needsCompression, getDescription, ContextStrategyType enum)
│   │   ├── SlidingWindowStrategy.kt  #     Скользящее окно (день 10)
│   │   ├── StickyFactsStrategy.kt    #     Ключевые факты + окно (день 10)
│   │   ├── SummaryStrategy.kt        #     Автосуммаризация + окно (день 9-10)
│   │   └── BranchingStrategy.kt      #     Персистентные ветки диалога (день 10, с внутренним Branch)
│   └── HistoryCompressor.kt          #   Сжатие истории в summary (день 9, инкрементальное)
│
├── memory/                           # ── Memory Layer ──
│   ├── MemoryStore.kt                #   Интерфейс хранилища (+ branches, summaries, facts, working/long-term)
│   ├── JsonChatStore.kt              #   JSON file-per-chat реализация (атомарная запись)
│   ├── JsonLongTermStore.kt          #   Глобальное long-term хранилище (день 11, кросс-чат)
│   ├── ChatData.kt                   #   Агрегат чата (messages + summary + facts + branches + workingMemory)
│   ├── MemoryLayer.kt                #   Модель слоёв памяти: MemoryLayer enum + WorkingMemory + LongTermMemory + UserProfile (день 11, about — день 12)
│   ├── Profile.kt                    #   (UserProfile определён в MemoryLayer.kt; день 12 — about + экстрактор)
│   └── Facts.kt                      #   Key-value факты из диалога (день 10, inline Map в ChatData)
│
├── state/                            # ── State Layer ──
│   ├── TaskState.kt                  #   Enum состояний задачи
│   ├── StateMachine.kt               #   Стейт-машина с переходами
│   └── InvariantChecker.kt           #   Программная проверка инвариантов
│
├── mcp/                              # ── MCP Integration Layer (день 16) ──
│   ├── McpClient.kt                 #   Обёртка над MCP SDK: stdio transport, connect/listTools/close
│   ├── McpTool.kt                   #   Доменная модель инструмента (name, description, inputSchema)
│   └── McpException.kt              #   Ошибка MCP-соединения/handshake
│
└── config/                           # ── Infrastructure ──
    ├── AppConfig.kt                  #   Data class конфигурации
    └── ConfigRepository.kt           #   Загрузка/сохранение конфига
```

### Описание пакетов

| Пакет | Ответственность | Зависит от |
|---|---|---|
| `cli/` | Парсинг аргументов, JLine3 REPL-цикл, mordant-вывод пользователю | `agent/`, `config/` |
| `agent/` | Оркестрация пайплайна: perception → memory → reasoning → action | `llm/`, `context/`, `memory/`, `state/` |
| `llm/` | HTTP-взаимодействие с LLM API, модели запросов/ответов, pricing | `config/` (baseUrl, apiKey) |
| `context/` | Управление контекстным окном: 4 стратегии, инкрементальное сжатие | `llm/model/` (ChatMessage), `memory/` |
| `memory/` | Персистентность: история, профиль, факты, ветки, memory layers (JSON-файлы) | — |
| `state/` | Стейт-машина задач, инварианты | — |
| `mcp/` | MCP-интеграция: подключение к MCP-серверу (stdio), discovery tools через `tools/list` | `config/` (mcpCommand) |
| `config/` | Загрузка конфигурации из env/файлов | — |

---

## 4. Ключевые интерфейсы

### LlmClient

```kotlin
interface LlmClient {
    suspend fun chat(request: ChatRequest): LlmResult<ChatResponse>
    // Phase 2: fun chatStream(request: ChatRequest): Flow<StreamChunk>
}
```

**Реализация:** `OpenAiCompatibleClient` — отправляет POST-запрос через Ktor
на `https://api.z.ai/api/coding/paas/v4/chat/completions`.

### GenerationPresets

```kotlin
// Объединённые пресеты (формат + стратегия) — удобная обёртка для CLI
enum class GenerationPreset(val label: String) {
    STANDARD("standard"),     // SystemPrompts.default + DIRECT
    CONCISE("concise"),       // SystemPrompts.withMaxLength(100) + DIRECT + maxTokens=200
    PROMPTED("prompted"),     // META_PROMPT стратегия
    EXPERTS("experts");       // EXPERT_GROUP стратегия

    fun toSystemMessage(): ChatMessage
    fun toReasoningStrategy(): ReasoningStrategy?
}
```

> **Без GenerationConfig** — в CLI флаги собираются прямо в ChatRequest.
> Отдельный доменный слой параметров нужен только при UI для настроек (Android).

### ContextStrategy

```kotlin
interface ContextStrategy {
    fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage>

    fun getName(): String
    fun getDescription(): String              // [ANDROID-DIFF] для CLI-справки
    fun needsCompression(): Boolean = false   // [ANDROID-DIFF] автокомпрессия
    suspend fun onAssistantResponse(assistantMessage: ChatMessage) {}
    fun reset() {}
}
```

**Реализации:** `SlidingWindowStrategy`, `StickyFactsStrategy`, `SummaryStrategy`, `BranchingStrategy`.

### MemoryStore

```kotlin
interface MemoryStore {
    // История диалога
    suspend fun saveMessage(chatId: String, message: ChatMessage)
    suspend fun loadHistory(chatId: String): List<ChatMessage>
    suspend fun clearHistory(chatId: String)

    // Управление чатами
    suspend fun listChats(): List<Chat>
    suspend fun createChat(): Chat
    suspend fun deleteChat(chatId: String)

    // Факты (Sticky Facts strategy)
    suspend fun saveFacts(chatId: String, facts: Map<String, String>)
    suspend fun loadFacts(chatId: String): Map<String, String>

    // Summary (History compression)
    suspend fun saveSummary(chatId: String, summary: String)
    suspend fun loadSummary(chatId: String): String?
    suspend fun clearSummary(chatId: String)

    // Ветки диалога (Branching strategy)
    suspend fun createBranch(chatId: String, name: String, leafMessageId: String?, fromIndex: Int): BranchData
    suspend fun listBranches(chatId: String): List<BranchData>
    suspend fun deleteBranch(chatId: String, branchId: String)

    // Working memory — per-chat (данные текущей задачи, день 11)
    suspend fun saveWorkingMemory(chatId: String, memory: WorkingMemory)
    suspend fun loadWorkingMemory(chatId: String): WorkingMemory?
    suspend fun clearWorkingMemory(chatId: String)

    // Long-term memory — global (profile, decisions, knowledge; кросс-чат, день 11)
    suspend fun loadLongTermMemory(): LongTermMemory   // non-null: пустой объект если файла нет
    suspend fun saveLongTermMemory(memory: LongTermMemory)
    suspend fun clearLongTermMemory()
}

data class Chat(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String
)
```

> **Реализация:** `JsonChatStore` — один JSON-файл на чат в `AppPaths.chatsDir`,
> атомарная запись (temp + rename). Long-term — отдельный глобальный файл
> `AppPaths.longTermFile` через `JsonLongTermStore` (форвардится из `JsonChatStore`).
> Профиль пользователя живёт внутри `LongTermMemory.profile` (день 12), а не отдельной таблицей.

### Memory layers (день 11)

```kotlin
enum class MemoryLayer { SHORT_TERM, WORKING, LONG_TERM }

@Serializable
data class WorkingMemory(             // per-chat, данные текущей задачи
    val currentTask: String? = null,
    val plan: String? = null,
    val scratchNotes: String? = null,
    val taskDecisions: List<String> = emptyList()
    // день 13: val taskState: TaskState? = null
)

@Serializable
data class LongTermMemory(            // global, кросс-чат/кросс-сессия
    val knowledge: Map<String, String> = emptyMap(),
    val decisions: Map<String, String> = emptyMap(),
    val profile: UserProfile? = null   // день 12: UserProfile(style, format, about, constraints)
)
```

| Слой | Что хранит | Хранение | Scope |
|---|---|---|---|
| `SHORT_TERM` | текущий диалог | `ChatData.messages` + context-стратегии | per-chat |
| `WORKING` | данные текущей задачи (task, plan, notes, decisions) | `ChatData.workingMemory` | per-chat, reset при `/reset` |
| `LONG_TERM` | knowledge, decisions, profile | `AppPaths.longTermFile` (global JSON) | global |

Слои подмешиваются в system prompt через `PromptBuilder` — пустые слои элизируются,
поэтому при отсутствии памяти поведение дней 1–10 не меняется.

### PromptBuilder (день 11)

```kotlin
class PromptBuilder(
    private val baseSystem: ChatMessage,     // SystemPrompts.default или PromptTemplates.buildSystemMessage(strategy)
    private val longTerm: LongTermMemory?,
    private val working: WorkingMemory?
) {
    fun build(): ChatMessage   // base + [long-term block] + [working block]; пустые слои пропускаются
}
```

### UserProfile + ProfileExtractor (день 12)

```kotlin
@Serializable
data class UserProfile(
    val style: String? = null,        // стиль ответов (кратко/подробно, тон)
    val format: String? = null,       // формат (с примерами кода / без)
    val about: String? = null,        // контекст: кто пользователь, цель
    val constraints: List<String> = emptyList()  // ограничения/стек/запреты
)

class ProfileExtractor(llmClient: LlmClient, model: String) {
    suspend fun extract(history: List<ChatMessage>, current: UserProfile?): UserProfile
    fun mergeProfile(current: UserProfile?, inferred: UserProfile): UserProfile  // аккумулирует, не затирает
}
```

Профиль живёт в global `LongTermMemory.profile`, рендерится в system prompt через
`PromptBuilder` (каждый запрос). Наполнение: ручная `/profile` + LLM авто-извлечение
(`ProfileExtractor`, opt-in `--auto-profile` каждые N ходов / on-demand `/profile extract`).

### Agent

```kotlin
interface Agent {
    suspend fun chat(userMessage: String): String
    fun getHistory(): List<ChatMessage>
    fun reset()
}
```

> **Без AgentFactory** — в CLI агент создаётся напрямую в ChatCommand.run().
> Фабрика нужна только при DI-контейнере (Android + Hilt).

### StateMachine

```kotlin
enum class TaskState {
    CLARIFY,    // Уточнение задачи
    PLANNING,   // Планирование
    EXECUTING,  // Выполнение
    VALIDATING, // Проверка
    DONE        // Завершено
}

class StateMachine {
    fun currentState(): TaskState
    fun transitionTo(next: TaskState): Result<TaskState>
    fun allowedTransitions(): Set<TaskState>
}
```

### InvariantChecker

```kotlin
interface InvariantChecker {
    fun check(response: String): InvariantResult
}

sealed class InvariantResult {
    data class Valid(val response: String) : InvariantResult()
    data class Violated(val reason: String, val suggestion: String) : InvariantResult()
}
```

### McpClient (день 16)

```kotlin
class McpClient(command: List<String>) {
    suspend fun connect()                    // запуск subprocess + handshake (withTimeout 30s)
    suspend fun listTools(): List<McpTool>   // tools/list → доменная McpTool
    suspend fun close()                      // graceful JSON-RPC close + stopProcess
}
```

> **Реализация:** stdio transport (`StdioClientTransport`): subprocess запускается через
> `ProcessBuilder(command)`, JSON-RPC идёт по stdin/stdout, stderr drained отдельно (иначе
> pipe-deadlock). **Не `AutoCloseable`** — `close()` suspend, поэтому вызывается явно в `finally`
> (без `use{}`). `inputSchema` хранится как JSON-строка (`McpTool.inputSchema`), рендер схемы
> отложен (Day 17). `stopProcess` = `destroy → waitFor(2s) → destroyForcibly` (паттерн SDK).
> Команда сервера — из `AppConfig.mcpCommand` (env `CLI_AGENT_MCP_COMMAND` / `local.properties`
> `mcp.command`) либо inline override `/mcp list-tools <cmd…>`.

---

## 5. План реализации по фазам

### Фаза 1: Фундамент (Дни 1–6) — MVP

| Шаг | Что делаем | День курса | Артефакт |
|---|---|---|---|
| 1.1 | Инициализация Gradle-проекта, зависимости, структура пакетов | — | `build.gradle.kts`, `settings.gradle.kts` |
| 1.2 | `LlmClient` интерфейс + `OpenAiCompatibleClient` (Ktor → z.ai) | День 1 | `llm/LlmClient.kt`, `llm/OpenAiCompatibleClient.kt` |
| 1.3 | Модели данных: `ChatMessage`, `ChatRequest`, `ChatResponse`, `LlmParameters` | День 1–2 | `llm/model/*.kt` |
| 1.4 | REPL-режим через clikt: ввод → LLM → вывод | День 6 | `cli/CliAgentCommand.kt`, `cli/ChatCommand.kt` |
| 1.5 | `AppConfig`: загрузка API-ключа, model, baseUrl из env/config | День 1 | `config/AppConfig.kt`, `config/ConfigRepository.kt` |
| 1.6 | Управление форматом ответа (system prompt с ограничениями) | День 2 | Интеграция в `SimpleAgent` |

**Результат:** Работающий CLI-агент, который принимает запрос и возвращает ответ от GLM-5.1

```
$ ./cli-agent
> Привет, кто ты?
Ответ: Я — AI-ассистент на базе GLM-5.1...
> /exit
```

---

### Фаза 2: Контекст и параметры (Дни 3–5, 7–8)

| Шаг | Что делаем | День курса | Артефакт |
|---|---|---|---|
| 2.1 | `LlmParameters`: temperature, top_p, max_tokens — с CLI-флагами | День 4 | Расширение `ChatRequest`, CLI-опции |
| 2.2 | Разные стратегии промптинга (step-by-step, expert group) | День 3 | Промпт-шаблоны в `agent/` |
| 2.3 | JSON-хранилище истории (`JsonChatStore`, файл на чат) | День 7 | `memory/JsonChatStore.kt` |
| 2.4 | Загрузка/восстановление контекста при перезапуске | День 7 | Интеграция в `ContextAwareAgent` |
| 2.5 | `TokenCounter`: подсчёт токенов для запроса, истории, ответа | День 8 | `llm/token/TokenCounter.kt` |
| 2.6 | CLI-команды: `/context list`, `/context clear`, `/stats` | — | `cli/ContextCommand.kt` |
| 2.7 | Streaming (SSE через Ktor) | День 1+ | Расширение `OpenAiCompatibleClient` |

**Результат:** Агент с памятью, подсчётом токенов, параметрами LLM и персистентностью

```
$ ./cli-agent
> /stats
Tokens: prompt=152, completion=89, total=241
History: 8 messages, session: abc123
> /context list
[1] user: Привет!
[2] assistant: Здравствуйте!
...
> /exit
$ ./cli-agent           # Перезапуск — агент помнит историю
> Что я спрашивал в прошлый раз?
Ответ: Вы спрашивали о...
```

---

### Фаза 3: Управление контекстом (Дни 9–10)

| Шаг | Что делаем | День курса | Артефакт |
|---|---|---|---|
| 3.1 | `HistoryCompressor`: инкрементальное сжатие старых сообщений в summary | День 9 | `context/HistoryCompressor.kt` |
| 3.2 | `SlidingWindowStrategy`: хранение последних N сообщений | День 10 | `context/strategy/SlidingWindowStrategy.kt` |
| 3.3 | `StickyFactsStrategy`: key-value факты + последние N сообщений | День 10 | `context/strategy/StickyFactsStrategy.kt` |
| 3.4 | `SummaryStrategy`: автосуммаризация + последние N сообщений | День 9-10 | `context/strategy/SummaryStrategy.kt` |
| 3.5 | `BranchingStrategy`: персистентные ветки диалога от checkpoint | День 10 | `context/strategy/BranchingStrategy.kt` |
| 3.6 | Переключатель стратегий: `/strategy sliding/facts/summary/branch` | День 10 | `context/ContextManager.kt`, CLI |

**Результат:** Агент с четырьмя стратегиями управления контекстом

```
$ ./cli-agent
> /strategy sliding
Switched to Sliding Window (last 10 messages)
> /strategy facts
Switched to Sticky Facts + Window
> /strategy summary
Switched to Summary + Window (auto-compression)
> /strategy branch
Switched to Branching
> /branch create feature-a
Created branch from message #5
> /branch switch feature-a
Switched to feature-a
```

---

### Фаза 4: Стейтфул-агент (Неделя 3 = Дни 11–13)

| Шаг | Что делаем | День курса | Артефакт |
|---|---|---|---|
| 4.1 | **Memory layers**: 3 типа памяти (short/working/long-term), хранятся отдельно, явный выбор что куда | День 11 | `memory/MemoryLayer.kt`, `memory/JsonLongTermStore.kt`, `agent/PromptBuilder.kt`, расширение `ContextAwareAgent` + `MemoryStore` + `ChatData` |
| 4.2 | **Персонализация**: `UserProfile(style, format, about, constraints)` в `LongTermMemory.profile`, рендерится в system prompt через `PromptBuilder` (каждый запрос), наполнение через `/profile` + LLM авто-извлечение `ProfileExtractor` (opt-in `--auto-profile`) | День 12 | `memory/MemoryLayer.kt` (UserProfile+about), `agent/ProfileExtractor.kt`, `/profile` в `ChatCommand`, аксессоры в `ContextAwareAgent` |
| 4.3 | **Task state machine**: этапы (clarify → plan → execute → validate → done), пауза/возобновление без повторных объяснений | День 13 | `state/TaskState.kt`, `state/StateMachine.kt`, `WorkingMemory.taskState`, `agent/StatefulAgent.kt` |
| 4.4 | **InvariantChecker**: программная проверка ограничений | День 13+ | `state/InvariantChecker.kt` |
| 4.5 | **StatefulAgent**: полная сборка (профиль + стейт + инварианты) | День 13 | `agent/StatefulAgent.kt` |

**Результат дня 11:** Агент с явной моделью памяти — короткая/рабочая/долговременная
хранятся раздельно, `/memory` явно сохраняет данные в нужный слой, `PromptBuilder`
подмешивает слои в system prompt.

```
$ ./cli-agent chat -c new
> /memory save long knowledge stack Kotlin
> /memory save working task "auth service"
> На каком языке писать и какой следующий шаг?
Ответ: (учитывает long-term knowledge=Kotlin + working task=auth service)
> /memory show working
[Working memory] currentTask: auth service ...
```

**Результат дня 12:** Персонализированный агент — `UserProfile` (style/format/about/constraints)
живёт в global `LongTermMemory.profile`, рендерится в system prompt каждого запроса
через `PromptBuilder`. Наполнение: ручная `/profile` + LLM авто-извлечение (`ProfileExtractor`,
opt-in `--auto-profile` / on-demand `/profile extract`). Профиль переживает рестарт и применяется
во всех чатах.

```
$ ./cli-agent chat
> /profile set style concise
> /profile set about "backend dev, Ktor"
> /profile add constraint "no RxJava"
> /profile show
[User profile] Style: concise | About: backend dev, Ktor | Constraints: no RxJava
> Напиши сервис авторизации
Ответ: (краткий, на Ktor, без RxJava — профиль учтён автоматически)
```

**Результат:** Полный стейтфул-агент с персонализацией, стейт-машиной и инвариантами

```
$ ./cli-agent
> Помоги написать REST API
[CLARIFY] Какой стек? Какие эндпоинты нужны?
> Kotlin + Ktor, CRUD для пользователей
[PLANNING] План: 1) Модели 2) Рoutes 3) Repository 4) Тесты
[EXECUTING] Создаю модели данных...
[VALIDATING] Проверяю соответствие плану и ограничениям...
[DONE] REST API готов.
```

---

### Фаза 5: MCP (Неделя 4 = Дни 16–20)

| Шаг | Что делаем | День курса | Артефакт |
|---|---|---|---|
| 5.1 | MCP-соединение + `tools/list` (local stdio, subprocess) | День 16 ✅ | `mcp/McpClient.kt`, `mcp/McpTool.kt`, `mcp/McpException.kt`, `/mcp` в `ChatCommand`, `mcpCommand` в `AppConfig` |
| 5.2 | Собственный MCP-сервер вокруг API + `callTool` из агента | День 17 ✅ | `:mcp-server` субпроект (`GitHubMcpServer`, `get_repo`), `McpClient.callTool`+`McpToolResult`, `ToolExecutor`/`McpToolExecutor`, function-calling (`ToolDefinition`/`ToolCall`, `ChatRequest.tools`), agent tool-use loop в `ContextAwareAgent` |
| 5.3 | Оркестрация нескольких tools в агенте | День 18 | (планируется) |
| 5.4 | MCP в реальной рабочей задаче | День 19 | (планируется) |
| 5.5 | Сравнение MCP vs Skill+CLI (token flow) | День 20 | (планируется) |

**Результат дня 16:** `/mcp list-tools` подключается к subprocess MCP-серверу (reference
`@modelcontextprotocol/server-filesystem` через `npx`), выводит mordant-таблицу инструментов;
`/mcp` показывает статус конфигурации. Без вызова tools, без LLM API.

```
$ ./gradlew run --args="chat"
cli-agent> /mcp
MCP: no command configured (set CLI_AGENT_MCP_COMMAND or mcp.command)
cli-agent> /mcp list-tools npx -y @modelcontextprotocol/server-filesystem /tmp
Connecting to MCP server…
┌─ 🔌 MCP Tools (8) ──────────────────────┐
│ # │ Name              │ Description      │
│ 1 │ read_file         │ …                │
│ … │ list_directory    │ …                │
└──────────────────────────────────────────┘
```

---

## 6. LLM API Details

### Провайдер

| Параметр | Значение |
|---|---|
| **Платформа** | z.ai (Zhipu AI / 智谱AI) |
| **Endpoint** | `https://api.z.ai/api/coding/paas/v4/chat/completions` |
| **Coding endpoint** | `https://api.z.ai/api/coding/paas/v4` (для Coding Plan) |
| **Модель** | `glm-5.1` |
| **Формат** | OpenAI-совместимый |
| **Аутентификация** | API key в заголовке `Authorization: Bearer token` |

### Формат запроса

```json
{
  "model": "glm-5.1",
  "messages": [
    {"role": "system", "content": "You are a helpful AI assistant."},
    {"role": "user", "content": "Hello, please introduce yourself."}
  ],
  "temperature": 0.7,
  "max_tokens": 1024,
  "top_p": 0.9
}
```

### Формат ответа

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "model": "glm-5.1",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! I am an AI assistant..."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 20,
    "total_tokens": 30
  }
}
```

### Доступные модели (на момент проектирования)

| Модель | Tier | Описание |
|---|---|---|
| `glm-5.1` | STRONG | Флагманская модель, целевая для проекта |
| `glm-5` | STRONG | Предыдущая флагманская |
| `glm-5-turbo` | MEDIUM | Быстрая, сбалансированная |
| `glm-4.7` | MEDIUM | Средняя по возможностям |
| `glm-4.5-air` | WEAK | Быстрая, дешёвая, ограниченная |
| `glm-5v-turbo` | — | Мультимодальная (визуальная) |
| `glm-image` | — | Генерация изображений |
| `cogvideox-3` | — | Генерация видео |

---

## 7. Переменные окружения

| Переменная | Описание | Значение по умолчанию |
|---|---|---|
| `CLI_AGENT_API_KEY` | z.ai API ключ | — (обязательная) |
| `CLI_AGENT_MODEL` | Имя модели | `glm-5.1` |
| `CLI_AGENT_BASE_URL` | API base URL | `https://api.z.ai/api/coding/paas/v4` |
| `CLI_AGENT_MCP_COMMAND` | Команда запуска MCP-сервера (subprocess, stdio) | — (опциональная; иначе `/mcp list-tools <cmd…>` inline) |
| `CLI_AGENT_GITHUB_TOKEN` | GitHub PAT для `:mcp-server` (`get_repo`); наследуется subprocess'ом | — (опциональная; без неё `get_repo` возвращает tool-error) |
| `XDG_DATA_HOME` | Каталог данных (чаты + long-term память) | `~/.local/share` (→ `cli-agent/chats/*.json`, `cli-agent/longterm/memory.json`) |
| `XDG_CONFIG_HOME` | Каталог конфигурации | `~/.config` |

---

## 8. Конвенции разработки

| Аспект | Правило |
|---|---|
| **Именование** | Kotlin conventions: camelCase для функций/переменных, PascalCase для классов |
| **Пакеты** | lowercase, без подчёркиваний |
| **Обработка ошибок** | sealed class `Result` паттерн, без исключений для flow control |
| **Корутины** | `suspend` для всех IO-операций |
| **Сериализация** | `@Serializable` на всех data classes для JSON |
| **Тестирование** | JUnit 5 + MockK для unit-тестов |
| **Логирование** | kotlin.io или kotlinx-logging при необходимости |
| **Git** | Conventional commits: `feat:`, `fix:`, `refactor:`, `chore:` |
