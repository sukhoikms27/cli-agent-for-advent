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
| **HTTP-клиент** | Ktor Client |
| **Сериализация** | kotlinx.serialization |
| **База данных** | SQLite (через JDBC) |
| **LLM-провайдер** | z.ai (Zhipu AI) — GLM-5.1 |
| **API-формат** | OpenAI-совместимый (`/v4/chat/completions`) |
| **Streaming** | Фаза 2 (сначала без стриминга) |
| **Стиль CLI** | REPL по умолчанию + CLI-аргументы для подкоманд |
| **Выполненные задания курса** | Дни 1–10 (API-вызов → формат → рассуждения → температура → модели → агент → контекст → токены → сжатие → 4 стратегии) |

---

## 2. Слои архитектуры

```
┌──────────────────────────────────────────────────────────┐
│                       CLI Layer                           │
│                   (clikt: REPL + команды)                 │
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
│  Ktor HTTP │  Стратегии   │  SQLite, Profile, Facts,      │
│  client    │  управления  │  StateMachine, Invariants      │
│            │  контекстом  │                               │
├────────────┴──────────────┴───────────────────────────────┤
│                    Infrastructure                          │
│               (SQLite, Ktor, Config)                      │
│                  config/ package                           │
└──────────────────────────────────────────────────────────┘
```

**Принцип разделения:** Каждый слой общается только с соседним снизу.
CLI не знает про LLM — он вызывает Agent. Agent не знает про SQLite —
он вызывает MemoryStore. Это позволяет менять реализации без каскадных правок.

---

## 3. Структура пакетов

```
src/main/kotlin/com/cliagent/
│
├── Main.kt                          # Точка входа (main function)
│
├── cli/                              # ── CLI Layer ──
│   ├── CliAgentCommand.kt            #   Корневая команда (clikt)
│   ├── ChatCommand.kt                #   REPL-режим чата
│   ├── ConfigCommand.kt              #   Управление конфигурацией
│   ├── ContextCommand.kt             #   Управление контекстом (list/switch/clear)
│   └── MemoryCommand.kt              #   Управление памятью (save/load/search)
│
├── agent/                            # ── Agent Layer ──
│   ├── Agent.kt                      #   Интерфейс агента
│   ├── SimpleAgent.kt                #   Базовый агент (день 6)
│   ├── ContextAwareAgent.kt          #   Агент с контекстом (день 7)
│   └── StatefulAgent.kt              #   Стейтфул-агент (неделя 3)
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
│   ├── MemoryStore.kt                #   Интерфейс хранилища (+ branches, summaries, facts)
│   ├── SqliteMemoryStore.kt          #   SQLite-реализация (с миграциями)
│   ├── DatabaseMigrations.kt         #   Система миграций БД
│   ├── Profile.kt                    #   Модель профиля пользователя (неделя 3)
│   └── Facts.kt                      #   Key-value факты из диалога (день 10)
│
├── state/                            # ── State Layer ──
│   ├── TaskState.kt                  #   Enum состояний задачи
│   ├── StateMachine.kt               #   Стейт-машина с переходами
│   └── InvariantChecker.kt           #   Программная проверка инвариантов
│
└── config/                           # ── Infrastructure ──
    ├── AppConfig.kt                  #   Data class конфигурации
    └── ConfigRepository.kt           #   Загрузка/сохранение конфига
```

### Описание пакетов

| Пакет | Ответственность | Зависит от |
|---|---|---|
| `cli/` | Парсинг аргументов, REPL-цикл, вывод пользователю | `agent/`, `config/` |
| `agent/` | Оркестрация пайплайна: perception → memory → reasoning → action | `llm/`, `context/`, `memory/`, `state/` |
| `llm/` | HTTP-взаимодействие с LLM API, модели запросов/ответов, pricing | `config/` (baseUrl, apiKey) |
| `context/` | Управление контекстным окном: 4 стратегии, инкрементальное сжатие | `llm/model/` (ChatMessage), `memory/` |
| `memory/` | Персистентность: история, профиль, факты, ветки, миграции БД | — |
| `state/` | Стейт-машина задач, инварианты | — |
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
    suspend fun createBranch(chatId: String, name: String, leafMessageId: String?): String
    suspend fun listBranches(chatId: String): List<BranchingStrategy.Branch>
    suspend fun deleteBranch(branchId: String)

    // Профиль пользователя
    suspend fun saveProfile(profile: Profile)
    suspend fun loadProfile(): Profile?
}

data class Chat(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String
)
```

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
| 2.3 | SQLite-хранилище истории (`SqliteMemoryStore`) | День 7 | `memory/SqliteMemoryStore.kt` |
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

### Фаза 4: Стейтфул-агент (Неделя 3)

| Шаг | Что делаем | Неделя курса | Артефакт |
|---|---|---|---|
| 4.1 | `Profile`: модель персонализации (стиль, ограничения, контекст) | Неделя 3 | `memory/Profile.kt` |
| 4.2 | `TaskStateMachine`: этапы (clarify → plan → execute → validate → done) | Неделя 3 | `state/StateMachine.kt`, `state/TaskState.kt` |
| 4.3 | `InvariantChecker`: программная проверка ограничений | Неделя 3 | `state/InvariantChecker.kt` |
| 4.4 | `StatefulAgent`: полная сборка (профиль + стейт + инварианты) | Неделя 3 | `agent/StatefulAgent.kt` |
| 4.5 | `PromptBuilder`: сборка контекстных слоёв в финальный промпт | Неделя 3 | Интеграция в `StatefulAgent` |

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
| `CLI_AGENT_DB_PATH` | Путь к SQLite базе | `~/.cli-agent/data.db` |

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

---

## 9. Отличия от Android-реализации

> Подробное сравнение: `plan/changelog-android-diff.md`

При проектировании CLI-агента использовался референс Android-проекта
[llm-chat-demo-app](https://github.com/sukhoikms27/llm-chat-demo-app).
Ключевые отличия, учтённые в плане:

| Компонент | Android | CLI (этот проект) |
|---|---|---|
| HTTP-клиент | Retrofit + OkHttp | Ktor CIO |
| БД | Room (ORM) | JDBC (сырой SQL) |
| DI | Hilt | Без DI (ручное создание) |
| Сериализация БД | Room annotations | JSON-строки в TEXT-полях |
| Обработка ошибок | try/catch | `LlmResult<T>` sealed class |
| UI | Jetpack Compose | clikt REPL |
| Модели | Конкретные IDs (glm-5.1, glm-5...) | То же + тир-система (WEAK/MEDIUM/STRONG) |
| Бенчмарк | Нет | `BenchmarkRunner` |
| Стриминг | SSE через OkHttp | Phase 2 (интерфейс заложен) |
| Количество стратегий | 4 (SlidingWindow, StickyFacts, Summary, Branching) | 4 (то же) |
| Ветвление | Персистентное (Room) | Персистентное (SQLite) |
| Миграции БД | 7 Room-миграций | `DatabaseMigrations` (JDBC) |

**Что позаимствовано из Android (принято):**
- `GenerationPresets` — объединённые пресеты (без GenerationConfig)
- `parentId` в ChatMessage — дерево сообщений для ветвления
- `Pricing` объект — расчёт стоимости по моделям
- Инкрементальная суммаризация — предыдущий summary + новые сообщения
- `SummaryStrategy` — суммаризация как полноценная стратегия
- `needsCompression()` + `getDescription()` — в интерфейсе ContextStrategy
- Персистентное ветвление — таблица dialog_branches
- `cachedTokens` — учёт кешированных токенов
- Миграции БД — инкрементальные миграции SQLite
- Конкретные z.ai model IDs и тир-система

**Что отвергнуто (не подходит CLI):**
- ~~`GenerationConfig`~~ — в CLI флаги → ChatRequest напрямую, отдельный слой без потребителя
- ~~`AgentFactory`~~ — без DI фабрика = обёртка над конструктором
- ~~`MessageUsage` встроенный в ChatMessage~~ — ломает API-сериализацию, CLI не отображает per-message токены
- ~~`ContextStrategyType` @Serializable~~ — CLI получает стратегию из флага, сериализация не нужна
- ~~`DialogBranch` отдельная доменная модель~~ — с raw JDBC Branch внутри BranchingStrategy достаточен

**Что CLI делает лучше:**
- `LlmResult<T>` — строгая обработка ошибок
- Тир-система моделей (WEAK/MEDIUM/STRONG)
- `BenchmarkRunner` — утилита сравнения моделей
- Больше LLM-параметров (seed, frequencyPenalty, presencePenalty)
- Поэтапное наращивание (каждый день — минимальная дельта)
