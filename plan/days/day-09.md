# День 9. Управление контекстом: сжатие истории

## Задание курса

Реализуйте механизм управления контекстом:
- храните последние N сообщений «как есть»
- остальное заменяйте summary (например каждые 10 сообщений)
- храните summary отдельно и подставляйте его в запрос вместо полной истории

Сравните:
- качество ответов без сжатия
- качество ответов со сжатием
- расход токенов до/после

**Результат:** Агент, который работает с компрессией истории и экономит токены

---

## Что уже есть (после дней 1–8)

```
✅ ContextAwareAgent с персистентной историей (SQLite)
✅ TokenCounter с оценкой и агрегацией
✅ /stats REPL-команда
✅ Предупреждение при приближении к лимиту контекста
✅ MemoryStore интерфейс + SqliteMemoryStore
```

## Что меняется в этот день

До сих пор история отправлялась в LLM **целиком**. Теперь добавляем
автоматическое сжатие: старые сообщения заменяются summary, Recent — остаются.
Это первый шаг к управлению контекстом (день 10 — стратегии).

---

## Что реализуем

### 1. HistoryCompressor

**Файл:** `src/.../context/HistoryCompressor.kt` (новый)

```kotlin
class HistoryCompressor(
    private val llmClient: LlmClient,
    private val model: String,
    private val keepRecentCount: Int = 10,         // сколько последних сообщений не сжимать
    private val compressThreshold: Int = 15         // при каком количестве сообщений сжимать
) {
    /**
     * Сжимает старые сообщения в summary.
     * Инкрементальный подход: если есть existingSummary — обновляет его,
     * иначе сжимает все старые сообщения (первый запуск).
     * Возвращает: summary + последние N сообщений.
     */
    suspend fun compress(
        history: List<ChatMessage>,
        existingSummary: String? = null
    ): CompressionResult {
        if (history.size <= keepRecentCount) {
            return CompressionResult(
                summary = existingSummary,
                recentMessages = history,
                wasCompressed = false,
                summarizedCount = 0,
                tokenEstimate = 0
            )
        }

        val oldMessages = history.dropLast(keepRecentCount)
        val recentMessages = history.takeLast(keepRecentCount)

        val summary = if (existingSummary != null) {
            generateIncrementalSummary(existingSummary, oldMessages)
        } else {
            generateSummary(oldMessages)
        }

        return CompressionResult(
            summary = summary,
            recentMessages = recentMessages,
            wasCompressed = true,
            summarizedCount = oldMessages.size,
            tokenEstimate = estimateTokens(summary)
        )
    }

    private suspend fun generateSummary(messages: List<ChatMessage>): String {
        val summaryPrompt = """
            ${messages.joinToString("\n") { "[${it.role}]: ${it.content}" }}
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = SUMMARIZATION_PROMPT),
                ChatMessage(role = "user", content = summaryPrompt)
            ),
            temperature = 0.0
        )

        return when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> result.data.choices.first().message.content
            is LlmResult.Error -> "[Summary generation failed: ${result.message}]"
        }
    }

    private suspend fun generateIncrementalSummary(
        existingSummary: String,
        newMessages: List<ChatMessage>
    ): String {
        val prompt = """
            Обнови существующее резюме диалога, добавив новую информацию.
            Сохрани все ключевые факты, решения и контекст.

            Существующее резюме:
            $existingSummary

            Новые сообщения:
            ${newMessages.joinToString("\n") { "[${it.role}]: ${it.content}" }}
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = SUMMARIZATION_PROMPT),
                ChatMessage(role = "user", content = prompt)
            ),
            temperature = 0.0
        )

        return when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> result.data.choices.first().message.content
            is LlmResult.Error -> existingSummary  // fallback на старый summary
        }
    }

    private fun estimateTokens(text: String): Int = (text.length / 4) + 4
}

data class CompressionResult(
    val summary: String?,           // Сжатая часть (null если не сжимали)
    val recentMessages: List<ChatMessage>,  // Последние N сообщений как есть
    val wasCompressed: Boolean,
    val summarizedCount: Int = 0,   // сколько сообщений сжато
    val tokenEstimate: Int = 0      // оценка токенов в summary
)
```

> **LLM для сжатия** — используем тот же LlmClient. Это стоит токены,
> но обеспечивает качественное сжатие. Альтернатива — простое обрезание
> (теряет контекст). Для курса — LLM-сжатие.

> **temperature = 0** — сжатие должно быть детерминированным.
> При одинаковой истории — одинаковый summary.

> **Инкрементальная суммаризация** — если есть существующий summary,
> обновляем его вместо пересжатия всей истории. Это экономит токены.

**Промпт суммаризации** (русскоязычный, структурированный):

```kotlin
private val SUMMARIZATION_PROMPT = """
    Ты — ассистент по сжатию контекста диалога.
    Извлеки ключевую информацию из диалога, структурируя по разделам:

    ## Цели и задачи
    - Какие цели ставил пользователь

    ## Решения и соглашения
    - Какие решения были приняты

    ## Факты и ограничения
    - Важные числа, имена, даты
    - Технические ограничения

    ## Предпочтения
    - Стиль общения, формат ответов

    Не добавляй новую информацию. Будь кратким, но точным.
""".trimIndent()
```

### 2. Хранение summary в SQLite

**Файл:** `src/.../memory/MemoryStore.kt` — расширить

```kotlin
interface MemoryStore {
    // ... существующие методы ...

    // Summary — сжатая часть истории
    suspend fun saveSummary(sessionId: String, summary: String)
    suspend fun loadSummary(sessionId: String): String?
    suspend fun clearSummary(sessionId: String)
}
```

**Файл:** `src/.../memory/SqliteMemoryStore.kt` — добавить таблицу

```sql
CREATE TABLE IF NOT EXISTS summaries (
    session_id TEXT PRIMARY KEY,
    summary TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
)
```

### 3. Интеграция в ContextAwareAgent

**Файл:** `src/.../agent/ContextAwareAgent.kt` — обновить

```kotlin
class ContextAwareAgent(
    private val llmClient: LlmClient,
    private val memoryStore: MemoryStore,
    private val model: String,
    private val chatId: String,
    private val systemPrompt: ChatMessage = SystemPrompts.default,
    private val reasoningStrategy: ReasoningStrategy? = null,
    private val tokenCounter: TokenCounter = TokenCounter(),
    private val contextLimit: Int = 128000,
    private val historyCompressor: HistoryCompressor? = null  // NEW: опционально
) : Agent {

    override suspend fun chat(userMessage: String): String {
        ensureLoaded()

        val userMsg = ChatMessage(role = "user", content = userMessage)
        history.add(userMsg)
        memoryStore.saveMessage(sessionId, userMsg)

        // Сжатие если нужно и compressor задан
        val messagesToSend = if (historyCompressor != null) {
            val existingSummary = memoryStore.loadSummary(chatId)
            val compressionResult = historyCompressor.compress(history, existingSummary)
            if (compressionResult.wasCompressed && compressionResult.summary != null) {
                // Сохранить summary
                memoryStore.saveSummary(sessionId, compressionResult.summary)

                // Собрать финальный список: summary как system message + recent
                val summaryMessage = ChatMessage(
                    role = "system",
                    content = "[Previous conversation summary]\n${compressionResult.summary}"
                )
                listOf(summaryMessage) + compressionResult.recentMessages
            } else {
                buildMessages()
            }
        } else {
            buildMessages()
        }

        val request = ChatRequest(model = model, messages = messagesToSend)
        val result = llmClient.chat(request)

        // ... обработка результата как раньше ...
    }
}
```

> **historyCompressor — nullable** — по умолчанию null (без сжатия).
> Это сохраняет обратную совместимость: если компрессор не задан,
> агент работает как раньше. Включается через CLI-флаг.

### 4. CLI-флаг для сжатия

**Файл:** `src/.../cli/ChatCommand.kt` — обновить

```kotlin
private val compress by option("-c", "--compress", help = "Enable history compression").flag()
private val keepRecent by option("-k", "--keep-recent", help = "Keep last N messages").int().default(10)

// В run():
val historyCompressor = if (compress) {
    HistoryCompressor(client, model, keepRecentCount = keepRecent)
} else {
    null
}
```

### 5. REPL-команды

Добавить:
```
/compress   — вручную запустить сжатие
/summary    — показать текущий summary сессии
```

### 6. Автокомпрессия

Добавить в `chat()` автоматическую компрессию при превышении порога:

```kotlin
// После добавления сообщения в историю
val shouldCompress = historyCompressor != null &&
    history.size > historyCompressor.compressThreshold &&
    history.size % historyCompressor.compressThreshold == 0  // каждые N сообщений

if (shouldCompress) {
    // Запустить компрессию в фоне или синхронно
    println("🔄 Compressing history...")
}
```

---

## Изменения в существующих файлах

| Файл | Изменение |
|---|---|
| `context/HistoryCompressor.kt` | Создать |
| `context/CompressionResult.kt` | Создать |
| `memory/MemoryStore.kt` | Добавить saveSummary/loadSummary/clearSummary |
| `memory/SqliteMemoryStore.kt` | Добавить таблицу summaries, реализовать методы |
| `agent/ContextAwareAgent.kt` | Добавить historyCompressor, логику сжатия в chat() |
| `cli/ChatCommand.kt` | Добавить --compress, --keep-recent, /compress, /summary |

## Новые файлы

| Файл | Описание |
|---|---|
| `context/HistoryCompressor.kt` | Сжатие истории через LLM-суммаризацию |
| `context/CompressionResult.kt` | Результат сжатия (summary + recent messages) |

---

## На что обратить внимание

1. **Стоимость сжатия** — каждый вызов `generateSummary()` стоит токены.
   При `compressThreshold = 15` сжатие происходит каждые 15 сообщений.
   Это умеренные затраты, но стоит показать в /stats.

2. **Summary как system message** — сжатая история подставляется как
   дополнительный system message с пометкой `[Previous conversation summary]`.
   Это не конфликтует с основным systemPrompt — оба отправляются.

3. **Потеря деталей** — сжатие неизбежно теряет часть информации.
   Для курса это ОК — нужно показать tradeoff между экономией токенов
   и качеством ответов.

4. **Не сжимать слишком часто** — компрессия каждые 10–15 сообщений
   достаточна. Чаще — дороже и не даёт преимуществ.

5. **Summary накапливается** — при каждом сжатии старый summary
   объединяется с новыми сообщениями и пересоздаётся. Старый summary
   не хранится отдельно — он уже «включён» в новый.

6. **Качество summary** — при плохом качестве сжатия ответы могут ухудшаться.
   Для курса важно показать сравнение: с/без сжатия.

---

## Критерии проверки

- [ ] Без --compress агент работает как раньше (обратная совместимость)
- [ ] С --compress история сжимается при превышении порога
- [ ] `/summary` показывает текущий сжатый контекст
- [ ] `/stats` показывает экономию токенов после сжатия
- [ ] После перезапуска summary загружается из SQLite
- [ ] Сравнение: качество ответов с/без сжатия на одном сценарии

---

## Состояние проекта после дня 9

```
✅ Всё из дней 1–8
✅ HistoryCompressor — сжатие через LLM-суммаризацию
✅ summaries таблица в SQLite
✅ Автокомпрессия при превышении порога
✅ /compress, /summary REPL-команды
✅ --compress CLI-флаг
❌ Стратегии Sliding Window / Sticky Facts / Branching (день 10)
❌ Переключатель стратегий (день 10)
```

---

## Что изменилось после сравнения с Android-реализацией

> Подробности: `plan/changelog-android-diff.md`

| Изменение | Тип | Описание |
|---|---|---|
| Инкрементальная суммаризация | CHANGED | Android суммаризирует «предыдущий summary + новые сообщения», а не все старые за раз. Экономит токены и даёт лучшее качество |
| Метаданные в CompressionResult | NEW | Android хранит `summarizedCount` и `tokenEstimate`. Полезно для /stats и отладки |
| Русскоязычные промпты суммаризации | NEW | Android использует русские структурированные промпты. Можно переиспользовать |
| SummaryStrategy как 4-я стратегия | NEW | Android сделал суммаризацию полноценной стратегией контекста, а не утилитой. Элегантнее: компрессия = способ управления контекстом |

### Инкрементальная суммаризация (изменить)

Заменить «сжать все старые за раз» на «инкрементально: предыдущий summary + новые сообщения»:

```kotlin
class HistoryCompressor(
    private val llmClient: LlmClient,
    private val model: String,
    private val keepRecentCount: Int = 10,
    private val compressThreshold: Int = 15
) {
    /**
     * [CHANGED] Инкрементальная суммаризация.
     * Если есть existingSummary — объединяем его с новыми сообщениями.
     * Если нет — сжимаем все старые сообщения (первый запуск).
     */
    suspend fun compress(
        history: List<ChatMessage>,
        existingSummary: String? = null  // [NEW] предыдущий summary
    ): CompressionResult {
        if (history.size <= keepRecentCount) {
            return CompressionResult(
                summary = existingSummary,
                recentMessages = history,
                wasCompressed = false,
                summarizedCount = 0,
                tokenEstimate = 0
            )
        }

        val oldMessages = history.dropLast(keepRecentCount)
        val recentMessages = history.takeLast(keepRecentCount)

        val summary = if (existingSummary != null) {
            // Инкрементально: старый summary + новые сообщения
            generateIncrementalSummary(existingSummary, oldMessages)
        } else {
            // Первый раз: сжать всё
            generateSummary(oldMessages)
        }

        return CompressionResult(
            summary = summary,
            recentMessages = recentMessages,
            wasCompressed = true,
            summarizedCount = oldMessages.size,
            tokenEstimate = estimateTokens(summary)
        )
    }

    private suspend fun generateIncrementalSummary(
        existingSummary: String,
        newMessages: List<ChatMessage>
    ): String {
        val prompt = """
            Обнови существующее резюме диалога, добавив новую информацию.
            Сохрани все ключевые факты, решения и контекст.

            Существующее резюме:
            $existingSummary

            Новые сообщения:
            ${newMessages.joinToString("\n") { "[${it.role}]: ${it.content}" }}
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = SUMMARIZATION_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = prompt)
            ),
            temperature = 0.0
        )

        return when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> result.data.choices.first().message.content
            is LlmResult.Error -> existingSummary  // fallback на старый summary
        }
    }

    private fun estimateTokens(text: String): Int = (text.length / 4) + 4
}
```

### Метаданные в CompressionResult (расширить)

```kotlin
data class CompressionResult(
    val summary: String?,
    val recentMessages: List<ChatMessage>,
    val wasCompressed: Boolean,
    val summarizedCount: Int = 0,      // [NEW] сколько сообщений сжато
    val tokenEstimate: Int = 0          // [NEW] оценка токенов в summary
)
```

### Русскоязычные промпты суммаризации (добавить)

```kotlin
// [NEW] Русскоязычный промпт суммаризации из Android-реализации
private val SUMMARIZATION_SYSTEM_PROMPT = """
    Ты — ассистент по сжатию контекста диалога.
    Извлеки ключевую информацию из диалога, структурируя по разделам:

    ## Цели и задачи
    - Какие цели ставил пользователь

    ## Решения и соглашения
    - Какие решения были приняты

    ## Факты и ограничения
    - Важные числа, имена, даты
    - Технические ограничения

    ## Предпочтения
    - Стиль общения, формат ответов

    Не добавляй новую информацию. Будь кратким, но точным.
""".trimIndent()
```

### SummaryStrategy как 4-я стратегия (добавить)

**Файл:** `src/.../context/strategy/SummaryStrategy.kt` (новый)

```kotlin
/**
 * Стратегия суммаризации — автоматически сжимает старые сообщения.
 * [ANDROID-DIFF] В Android это полноценная 4-я стратегия (SummaryStrategy),
 * а не просто утилита. Это элегантнее: компрессия = способ управления контекстом.
 */
class SummaryStrategy(
    private val historyCompressor: HistoryCompressor,
    private val memoryStore: MemoryStore,
    private val sessionId: String,
    private val windowSize: Int = 10
) : ContextStrategy {

    override fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage> {
        val existingSummary = memoryStore.loadSummary(sessionId)

        if (history.size <= windowSize) {
            return listOf(systemPrompt) + history + newMessage
        }

        val compressionResult = historyCompressor.compress(history, existingSummary)
        if (compressionResult.wasCompressed && compressionResult.summary != null) {
            memoryStore.saveSummary(sessionId, compressionResult.summary)
        }

        val summaryMessage = compressionResult.summary?.let {
            ChatMessage(role = "system", content = "[Previous conversation summary]\n$it")
        }

        val messages = if (summaryMessage != null) {
            listOf(systemPrompt, summaryMessage) + compressionResult.recentMessages + newMessage
        } else {
            listOf(systemPrompt) + history + newMessage
        }

        return messages
    }

    override fun getName(): String = "summary"

    override fun getDescription(): String =
        "Auto-summarizes old messages via LLM, keeps last N as-is"

    override fun needsCompression(): Boolean = true

    override fun reset() {
        memoryStore.clearSummary(sessionId)
    }
}
```

> SummaryStrategy будет доступна как 4-я опция в день 10:
> `/strategy sliding|facts|summary|branch`
