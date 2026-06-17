# Changelog: Отличия CLI-планов от Android-реализации

> Сравнение планов `plan/days/day-01.md` — `day-10.md` с реальной реализацией
> в Android-проекте [llm-chat-demo-app](https://github.com/sukhoikms27/llm-chat-demo-app).
> Пометки: `[NEW]` — добавить, `[CHANGED]` — изменить, `[REMOVED]` — убрать, `[REJECTED]` — рассмотрено и отвергнуто (не подходит CLI).

---

## День 1 — Первый вызов LLM API

### `[NEW]` chatStream() в интерфейсе LlmClient

Android реализовал стриминг (SSE) через Retrofit `@Streaming`. CLI откладывает стриминг на Phase 2, но интерфейс нужно заложить заранее:

```kotlin
interface LlmClient {
    suspend fun chat(request: ChatRequest): LlmResult<ChatResponse>
    // Phase 2 placeholder:
    // fun chatStream(request: ChatRequest): Flow<StreamChunk>
}
```

**Референс Android:** `LlmApi.kt` — `chatCompletionsStream(@Streaming)`

### `[CHANGED]` Usage — добавить cachedTokens

Android учитывает `cachedTokens` в ответе API. Добавить поле:

```kotlin
@Serializable
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cachedTokens: Int? = null  // [NEW]
)
```

**Референс Android:** `ChatResponse.kt` → `PromptTokensDetails(cachedTokens)`

---

## День 2 — Управление форматом ответа

### `[NEW]` GenerationPresets — объединённые пресеты

Android использует `GenerationPresets` — 4 пресета, объединяющих формат + стратегию:
- Standard (default)
- Concise (short + maxTokens)
- Prompted (meta-prompt)
- Experts (expert group)

Добавить пресеты как удобную обёртку над `SystemPrompts` + `PromptTemplates`:

```kotlin
// src/.../llm/model/GenerationPresets.kt
enum class GenerationPreset {
    STANDARD,   // SystemPrompts.default + DIRECT
    CONCISE,    // SystemPrompts.withMaxLength(100) + DIRECT + maxTokens=200
    PROMPTED,   // META_PROMPT стратегия
    EXPERTS     // EXPERT_GROUP стратегия
}
```

**Референс Android:** `domain/model/GenerationPresets.kt`

---

## День 3 — Стратегии рассуждения

### `[NEW]` Упомянуть GenerationPresets

Стратегии из PromptTemplates комбинируются с форматом из SystemPrompts.
GenerationPresets (из дня 2) — удобный способ задать обе оси разом.

**Без изменений в коде дня 3**, но добавить секцию «Связь с пресетами».

---

## День 4 — Температура и параметры

### `[REJECTED]` GenerationConfig — отдельный класс

Android группирует все параметры генерации в `GenerationConfig` (для Settings UI + Room).
В CLI флаги напрямую собираются в `ChatRequest`. Отдельный доменный слой — оверхед:
- Нет UI для редактирования настроек
- Создаёт циклическую зависимость с `ContextStrategyType` (день 10)
- Конверсия GenerationConfig → ChatRequest = лишний код

**Решение:** Оставить плоские поля в `ChatRequest`. Пресеты (`GenerationPresets`) задают
дефолтные значения для ChatRequest-полей напрямую.

---

## День 5 — Версии моделей

### `[NEW]` Конкретные z.ai model IDs

Android использует конкретные модели z.ai. Обновить ModelInfo:

```kotlin
val AVAILABLE_MODELS = listOf(
    ModelInfo("glm-5.1", ModelTier.STRONG, contextWindow = 128000),
    ModelInfo("glm-5", ModelTier.STRONG, contextWindow = 128000),
    ModelInfo("glm-5-turbo", ModelTier.MEDIUM, contextWindow = 128000),
    ModelInfo("glm-4.7", ModelTier.MEDIUM, contextWindow = 128000),
    ModelInfo("glm-4.5-air", ModelTier.WEAK, contextWindow = 8192)
)
```

**Референс Android:** `LlmRepositoryImpl.getAvailableModels()`

### `[NEW]` Pricing-объект

Android имеет отдельный объект `Pricing` с ценами по моделям и методом `calculateCost()`:

```kotlin
object Pricing {
    private val prices = mapOf(
        "glm-5.1" to Price(input = 20.0, output = 20.0),       // за 1M токенов
        "glm-5-turbo" to Price(input = 5.0, output = 5.0),
        "glm-4.5-air" to Price(input = 1.0, output = 1.0)
    )

    fun calculateCost(modelId: String, usage: Usage): Double? { ... }
}
```

**Референс Android:** `domain/pricing/Pricing.kt`

---

## День 6 — Первый агент

### `[REJECTED]` AgentFactory — фабрика агентов

Android использует фабрику (`LlmAgentFactory`) с Hilt DI. В CLI агент создаётся
один раз в `ChatCommand.run()` напрямую. Фабрика без DI = обёртка над конструктором.
К тому же зависела бы от отвергнутого `GenerationConfig`.

**Референс Android:** `domain/agent/LlmAgentFactory.kt`

---

## День 7 — Персистентность контекста

### `[NEW]` parentId в модели сообщения

Android использует `parentId` в модели сообщения для формирования дерева.
Это **критично** для BranchingStrategy (день 10). Без parentId ветвление будет хрупким.

```kotlin
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val parentId: String? = null  // [NEW] для дерева сообщений
)
```

SQL-схема:
```sql
CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,           -- UUID
    session_id TEXT NOT NULL,
    parent_id TEXT,                -- [NEW] ссылка на родительское сообщение
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
)
```

**Референс Android:** `ChatMessageEntity.kt` → `parentId`

### `[NEW]` Система миграций БД

Android прошёл через 7 миграций (Room MIGRATION_1_2 .. MIGRATION_6_7).
CLI тоже нужен механизм миграций, т.к. схема будет расширяться каждый день:

```kotlin
object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration {
        override fun migrate(db: Connection) {
            db.createStatement().execute("ALTER TABLE messages ADD COLUMN parent_id TEXT")
        }
    }
    // ...
}
```

**Референс Android:** `data/local/Migrations.kt`

### `[CHANGED]` Расширенная модель чата

Android хранит в чате: id, title, createdAt, updatedAt. CLI-план имеет только sessionId.

```kotlin
// sessions → chats
data class Chat(
    val id: String,           // UUID
    val title: String,        // тема чата (можно генерировать из первого сообщения)
    val createdAt: String,    // ISO 8601
    val updatedAt: String     // ISO 8601
)
```

SQL:
```sql
CREATE TABLE IF NOT EXISTS chats (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL DEFAULT 'New Chat',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
)
```

### `[CHANGED]` Сообщения используют UUID вместо AUTOINCREMENT

Android использует текстовые UUID для ID сообщений. Это нужно для parentId-ссылок:

```sql
id TEXT PRIMARY KEY  -- вместо INTEGER PRIMARY KEY AUTOINCREMENT
```

---

## День 8 — Подсчёт токенов

### `[CHANGED]` Коэффициент оценки токенов: ~4 chars/token

Android использует ~4 символа на токен. CLI-план использовал ~3. Обновить:

```kotlin
fun estimateMessageTokens(message: ChatMessage): Int {
    return (message.content.length / 4) + 4  // [CHANGED] 3 → 4
}
```

**Референс Android:** `ContextManager.estimateTokens()`

### `[NEW]` cachedTokens в Usage-модели

(Уже добавлено в день 1 changelog.) Учитывать кешированные токены при расчёте стоимости.

### `[REJECTED]` Usage встраивается в модель сообщения

Android хранит usage прямо в ChatMessage для per-message UI. В CLI это не нужно:
- ChatMessage — модель для API-запросов, поле `usage` ломает сериализацию
- CLI не отображает per-message токены — достаточно TokenCounter + `/stats` + `token_usage` таблица
- Дублирование: MessageUsage + token_usage = два источника истины

---

## День 9 — Сжатие контекста (Summary)

### `[CHANGED]` Инкрементальная суммаризация

CLI-план сжимает все «старые» сообщения за один раз. Android использует **инкрементальный** подход: предыдущий summary + новые сообщения → новый summary. Это экономит токены и даёт более качественное сжатие.

```kotlin
suspend fun compress(
    history: List<ChatMessage>,
    existingSummary: String?  // [NEW] предыдущий summary
): CompressionResult {
    val oldMessages = history.dropLast(keepRecentCount)
    val recentMessages = history.takeLast(keepRecentCount)

    val summary = if (existingSummary != null) {
        // Инкрементально: старый summary + новые сообщения
        generateIncrementalSummary(existingSummary, oldMessages)
    } else {
        // Первый раз: сжать всё
        generateSummary(oldMessages)
    }
    // ...
}
```

**Референс Android:** `ContextManager.kt` → инкрементальная суммаризация

### `[NEW]` Метаданные в CompressionResult

Android хранит доп. метаданные о компрессии. Добавить:

```kotlin
data class CompressionResult(
    val summary: String?,
    val recentMessages: List<ChatMessage>,
    val wasCompressed: Boolean,
    val summarizedCount: Int = 0,      // [NEW] сколько сообщений сжато
    val tokenEstimate: Int = 0          // [NEW] оценка токенов в summary
)
```

**Референс Android:** `ContextSummary.kt` → `summarizedCount`, `tokenEstimate`

### `[NEW]` Русскоязычные промпты суммаризации

Android использует русскоязычные структурированные промпты для суммаризации.
Можно переиспользовать:

```kotlin
private val SUMMARIZATION_SYSTEM_PROMPT = """
    Ты — ассистент по сжатию контекста диалога.
    Извлеки ключевую информацию из диалога:
    - Цели и задачи пользователя
    - Принятые решения и соглашения
    - Важные факты и ограничения
    - Предпочтения пользователя
    Структурируй ответ по разделам.
""".trimIndent()
```

**Референс Android:** `ContextManager.kt` → суммаризационные промпты

### `[NEW]` SummaryStrategy как 4-я стратегия

Android сделал суммаризацию полноценной стратегией контекста (SummaryStrategy),
а не просто утилитой. Это элегантнее: компрессия — это тоже способ управления контекстом.

Добавить `SummaryStrategy` в день 9 (а не 10):

```kotlin
class SummaryStrategy(
    private val historyCompressor: HistoryCompressor,
    private val memoryStore: MemoryStore,
    private val sessionId: String
) : ContextStrategy {
    override fun buildMessages(...): List<ChatMessage> {
        val summary = memoryStore.loadSummary(sessionId)
        val compressionResult = historyCompressor.compress(history, existingSummary = summary)
        // ...
    }

    override fun needsCompression(): Boolean { ... }
}
```

**Референс Android:** `domain/agent/SummaryStrategy.kt`

---

## День 10 — Стратегии управления контекстом

### `[CHANGED]` 4 стратегии вместо 3

Добавить SummaryStrategy (из дня 9) как полноценную 4-ю стратегию:

```
sliding → SlidingWindowStrategy
facts   → StickyFactsStrategy
summary → SummaryStrategy      // [NEW]
branch  → BranchingStrategy
```

**Референс Android:** 4 стратегии — SlidingWindow, StickyFacts, Summary, Branching

### `[NEW]` needsCompression() в интерфейсе ContextStrategy

Android добавляет `needsCompression()` — метод, определяющий, нужна ли компрессия:
```kotlin
interface ContextStrategy {
    fun buildMessages(...): List<ChatMessage>
    fun getName(): String
    fun getDescription(): String          // [NEW] для CLI-справки
    suspend fun onAssistantResponse(assistantMessage: ChatMessage) {}
    fun needsCompression(): Boolean = false  // [NEW]
    fun reset() {}
}
```

**Референс Android:** `ContextStrategy.kt` → `needsCompression()`, `displayName`, `description`

### `[CHANGED]` Branching — персистентная (не in-memory)

CLI-план хранит ветки в памяти — при перезапуске теряются.
Android хранит ветки в Room-таблице `dialog_branches`.
Для CLI нужно хранить в SQLite:

```sql
CREATE TABLE IF NOT EXISTS dialog_branches (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    name TEXT NOT NULL,
    leaf_message_id TEXT,           -- ID последнего сообщения в ветке
    parent_leaf_message_id TEXT,    -- ID leaf-сообщения родительской ветки
    parent_branch_id TEXT,          -- ID родительской ветки
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES chats(id)
)
```

```kotlin
// MemoryStore — добавить методы
suspend fun createBranch(sessionId: String, name: String, leafMessageId: String?): String
suspend fun listBranches(sessionId: String): List<DialogBranch>
suspend fun switchBranch(sessionId: String, branchId: String)
```

**Референс Android:** `DialogBranchEntity.kt`, `DialogBranchDao.kt`

### `[NEW]` description в ContextStrategy

Каждая стратегия должна иметь описание для CLI-справки:
```kotlin
SlidingWindowStrategy: "Keeps last N messages, discards older ones"
StickyFactsStrategy: "Extracts key facts + keeps last N messages"
SummaryStrategy: "Auto-summarizes old messages via LLM"
BranchingStrategy: "Creates branches from checkpoints, switch between them"
```

**Референс Android:** `ContextStrategy.kt` → `description`

### `[NEW]` LLM-генерация имён веток (опционально)

Android генерирует имена веток через LLM (по последним 4 сообщениям).
Можно добавить опционально:

```kotlin
private suspend fun maybeGenerateBranchName(branchId: String, history: List<ChatMessage>) {
    val recentMessages = history.takeLast(4)
    val prompt = "Generate a short descriptive name (2-4 words) for a conversation branch based on these messages: ..."
    // ...
}
```

**Референс Android:** `LlmAgentImpl.maybeGenerateBranchName()`

---

## Общие изменения

### `[REJECTED]` ContextStrategyType — сериализуемый enum

В CLI стратегия приходит из флага `--strategy`. Сериализация (`@Serializable`) нужна
только Android (Room SettingsEntity). Plain enum без аннотации достаточен.

### `[REJECTED]` DialogBranch — отдельная доменная модель

С raw JDBC CLI читает прямо из SQL. `Branch` data class внутри `BranchingStrategy`
уже имеет все нужные поля. Отдельный файл-модель — слой абстракции без потребителя
(нет Clean Architecture слоёв data/domain/presentation как в Android).

---

## Сводка всех изменений по дням

| День | NEW (принято) | CHANGED (принято) | REJECTED | Итого |
|---|---|---|---|---|
| 1 | chatStream placeholder, cachedTokens | — | — | 2 |
| 2 | GenerationPresets | — | — | 1 |
| 3 | — (упомянуть пресеты) | — | — | 0+ |
| 4 | — | Параметры плоские в ChatRequest | ~~GenerationConfig~~ | 1 |
| 5 | Конкретные model IDs, Pricing объект | — | — | 2 |
| 6 | — | — | ~~AgentFactory~~ | 0 |
| 7 | parentId, миграции БД, UUID id, Chat модель | sessions→chats | — | 5 |
| 8 | cachedTokens в TokenCounter | ~3→~4 chars/token | ~~MessageUsage в ChatMessage~~ | 2 |
| 9 | Инкрементальная суммаризация, метаданные, русские промпты, SummaryStrategy | — | — | 4 |
| 10 | needsCompression(), description, LLM-имена веток | 4 стратегии, Branching персистентная | ~~ContextStrategyType @Serializable~~, ~~DialogBranch файл~~ | 4 |
| **Итого** | **20** | **7** | **27** |
