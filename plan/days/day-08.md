# День 8. Работа с токенами

## Задание курса

Добавьте в код агента подсчёт токенов:
- для текущего запроса
- для всей истории диалога
- для ответа модели

Сравните:
- короткий диалог
- длинный диалог
- диалог, который превышает лимит модели

Покажите:
- как растёт стоимость/токены по мере диалога
- что ломается при переполнении

**Результат:** Код, который считает токены и показывает, как они влияют на поведение агента

---

## Что уже есть (после дней 1–7)

```
✅ ContextAwareAgent с персистентной историей (SQLite)
✅ ChatResponse с Usage (promptTokens, completionTokens, totalTokens)
✅ MemoryStore + SqliteMemoryStore
✅ REPL: /help, /history, /reset, /exit, /sessions
```

## Что меняется в этот день

ChatResponse уже содержит `Usage` с данными о токенах от API.
Но эта информация **не сохраняется** и **не отображается** пользователю.
Теперь:
1. TokenCounter — подсчёт и агрегация токенов
2. Сохранение usage в SQLite при каждом запросе
3. REPL-команды для просмотра статистики
4. Обнаружение переполнения контекстного окна

---

## Что реализуем

### 1. TokenCounter

**Файл:** `src/.../llm/token/TokenCounter.kt` (новый)

```kotlin
class TokenCounter {

    private val sessionStats = mutableMapOf<String, SessionTokens>()

    data class SessionTokens(
        var totalPromptTokens: Long = 0,
        var totalCompletionTokens: Long = 0,
        var totalTokens: Long = 0,
        var requestCount: Int = 0,
        var lastRequestTokens: Usage? = null
    )

    fun recordUsage(sessionId: String, usage: Usage?) {
        if (usage == null) return
        val stats = sessionStats.getOrPut(sessionId) { SessionTokens() }
        stats.totalPromptTokens += usage.promptTokens
        stats.totalCompletionTokens += usage.completionTokens
        stats.totalTokens += usage.totalTokens
        stats.requestCount++
        stats.lastRequestTokens = usage
    }

    fun getSessionStats(sessionId: String): SessionTokens? =
        sessionStats[sessionId]

    fun estimateMessageTokens(message: ChatMessage): Int {
        // Грубая оценка: ~4 символа = 1 токен (для смешанных рус/англ текстов)
        return (message.content.length / 4) + 4  // +4 на role/metadata
    }

    fun estimateHistoryTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateMessageTokens(it) }

    fun reset(sessionId: String) {
        sessionStats.remove(sessionId)
    }
}
```

> **Оценка токенов vs реальное количество** — API возвращает точное количество
> в `Usage`. `estimateMessageTokens` — грубая аппроксимация для случаев,
> когда нужно оценить размер запроса ДО отправки (например, для обрезки контекста).
> В идеале — использовать tiktoken или аналог, но для Kotlin это сложно.
> Аппроксимация достаточна для курса.

### 2. Расширение ContextAwareAgent

**Файл:** `src/.../agent/ContextAwareAgent.kt` — обновить

Добавить TokenCounter в агент:

```kotlin
class ContextAwareAgent(
    private val llmClient: LlmClient,
    private val memoryStore: MemoryStore,
    private val model: String,
    private val sessionId: String,
    private val systemPrompt: ChatMessage = SystemPrompts.default,
    private val reasoningStrategy: ReasoningStrategy? = null,
    private val tokenCounter: TokenCounter = TokenCounter(),
    private val contextLimit: Int = 128000  // лимит контекстного окна модели
) : Agent {

    // ... существующий код ...

    override suspend fun chat(userMessage: String): String {
        ensureLoaded()

        val userMsg = ChatMessage(role = "user", content = userMessage)
        history.add(userMsg)
        memoryStore.saveMessage(sessionId, userMsg)

        // Проверка: не превышает ли история контекстное окно?
        val estimatedTokens = tokenCounter.estimateHistoryTokens(buildMessages())
        if (estimatedTokens > contextLimit) {
            // Предупредить пользователя, что контекст переполнен
            // В день 9 это решится через сжатие/обрезку
            println("⚠️ Warning: estimated $estimatedTokens tokens, context limit is $contextLimit")
        }

        val messages = buildMessages()
        val request = ChatRequest(model = model, messages = messages)
        val result = llmClient.chat(request)

        val responseText = when (result) {
            is LlmResult.Success -> {
                val usage = result.data.usage
                tokenCounter.recordUsage(sessionId, usage)

                val assistantMsg = result.data.choices.first().message
                history.add(assistantMsg)
                memoryStore.saveMessage(sessionId, assistantMsg)
                assistantMsg.content
            }
            is LlmResult.Error -> "Error: ${result.code} — ${result.message}"
        }

        return responseText
    }

    fun getTokenStats(): TokenCounter.SessionTokens? =
        tokenCounter.getSessionStats(sessionId)

    fun getEstimatedHistoryTokens(): Int =
        tokenCounter.estimateHistoryTokens(history)
}
```

### 3. Сохранение usage в SQLite

**Файл:** `src/.../memory/SqliteMemoryStore.kt` — обновить

Добавить таблицу для записи usage:

```sql
CREATE TABLE IF NOT EXISTS token_usage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    total_tokens INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
)
```

Добавить метод в `MemoryStore`:

```kotlin
interface MemoryStore {
    // ... существующие методы ...

    suspend fun saveUsage(sessionId: String, usage: Usage)
    suspend fun getTotalUsage(sessionId: String): UsageSummary?
}

data class UsageSummary(
    val totalPromptTokens: Long,
    val totalCompletionTokens: Long,
    val totalTokens: Long,
    val requestCount: Int
)
```

### 4. REPL-команды

**Файл:** `src/.../cli/ChatCommand.kt` — обновить

Добавить команды:

```
/stats     — статистика токенов текущей сессии
/cost      — оценка стоимости сессии
```

```kotlin
// В REPL-цикл:
"/stats" -> {
    val stats = agent.getTokenStats()
    if (stats != null) {
        println("""
            |📊 Token Statistics
            |  Requests:    ${stats.requestCount}
            |  Prompt:      ${stats.totalPromptTokens} tokens
            |  Completion:  ${stats.totalCompletionTokens} tokens
            |  Total:       ${stats.totalTokens} tokens
            |  Last request: ${stats.lastRequestTokens}
            |  History est: ${agent.getEstimatedHistoryTokens()} tokens
        """.trimMargin())
    } else {
        println("No token data yet.")
    }
}
```

### 5. ModelInfo — связать contextLimit с моделью

**Файл:** `src/.../llm/model/ModelInfo.kt` — использовать

При создании агента определять `contextLimit` из ModelInfo:

```kotlin
val contextLimit = ModelInfoResolver.getContextLimit(model)
// glm-5.1 → 128000, glm-4-flash → 128000, glm-4-air → 8192
```

---

## Изменения в существующих файлах

| Файл | Изменение |
|---|---|
| `agent/ContextAwareAgent.kt` | Добавить TokenCounter, проверку лимита, getTokenStats() |
| `memory/MemoryStore.kt` | Добавить saveUsage(), getTotalUsage() |
| `memory/SqliteMemoryStore.kt` | Добавить таблицу token_usage, реализовать методы |
| `cli/ChatCommand.kt` | Добавить /stats, /cost команды |

## Новые файлы

| Файл | Описание |
|---|---|
| `llm/token/TokenCounter.kt` | Подсчёт и агрегация токенов |
| `llm/model/UsageSummary.kt` | Модель суммарного использования |

---

## На что обратить внимание

1. **Usage может быть null** — не все провайдеры возвращают usage.
   TokenCounter должен быть устойчив к null: `if (usage == null) return`.

2. **Оценка токенов до отправки** — `estimateMessageTokens()` — грубая.
   Реальное количество может отличаться в 2×. Для обнаружения переполнения
   это ОК — лучше перестраховаться и обрезать раньше, чем словить ошибку API.

3. **Переполнение контекста** — при `estimatedTokens > contextLimit`:
   - В день 8: только предупреждение
   - В день 9: автоматическое сжатие (HistoryCompressor)
   - В день 10: стратегия управления контекстом

4. **Стоимость** — для расчёта стоимости нужны цены из ModelInfo.
   Пока можно выводить только токены, без $ — цены могут устареть.

5. **TokenCounter — in-memory** — статистика токенов за сессию хранится
   в памяти. При перезапуске агрегированные данные теряются, но
   поэтажные записи в SQLite (`token_usage`) сохраняются.

---

## Критерии проверки

- [ ] `/stats` показывает количество токенов за сессию
- [ ] Статистика обновляется после каждого запроса
- [ ] При длинном диалоге появляется предупреждение о приближении к лимиту
- [ ] Оценка токенов истории коррелирует с реальным usage от API
- [ ] (Опционально) `/cost` показывает оценочную стоимость

---

## Состояние проекта после дня 8

```
✅ Всё из дней 1–7
✅ TokenCounter — подсчёт и агрегация токенов
✅ token_usage таблица в SQLite
✅ /stats REPL-команда
✅ Предупреждение при приближении к лимиту контекста
✅ Оценка токенов до отправки (аппроксимация)
❌ Автоматическое сжатие истории (день 9)
❌ Стратегии управления контекстом (день 10)
```

---

## Что изменилось после сравнения с Android-реализацией

> Подробности: `plan/changelog-android-diff.md`

| Изменение | Тип | Описание |
|---|---|---|
| Коэффициент оценки: ~4 chars/token | CHANGED | ✅ Применено в основном теле. Точнее для смешанных рус/англ текстов |
| `cachedTokens` в Usage | NEW | ✅ Уже добавлено в день 1. Учитывать при расчёте стоимости — кешированные токены дешевле |
| ~~Usage встроенный в ChatMessage~~ | REJECTED | Android встраивает `usage` в ChatMessage для per-message UI. В CLI нет per-message отображения токенов. ChatMessage — модель для API, поле `usage` ломает сериализацию запроса. Достаточно TokenCounter + `/stats` + `token_usage` таблица |

### Коэффициент оценки токенов (уже применено в основном теле)

Основной код `TokenCounter.estimateMessageTokens()` теперь использует `/ 4`
вместо `/ 3`. Изменение внесено прямо в основной план.

### cachedTokens — учёт в TokenCounter

Добавить подсчёт кешированных токенов в `SessionTokens`:

```kotlin
data class SessionTokens(
    var totalPromptTokens: Long = 0,
    var totalCompletionTokens: Long = 0,
    var totalTokens: Long = 0,
    var requestCount: Int = 0,
    var lastRequestTokens: Usage? = null,
    var totalCachedTokens: Long = 0  // кешированные = экономия
)

fun recordUsage(sessionId: String, usage: Usage?) {
    if (usage == null) return
    val stats = sessionStats.getOrPut(sessionId) { SessionTokens() }
    stats.totalPromptTokens += usage.promptTokens
    stats.totalCompletionTokens += usage.completionTokens
    stats.totalTokens += usage.totalTokens
    stats.totalCachedTokens += (usage.cachedTokens ?: 0)
    stats.requestCount++
    stats.lastRequestTokens = usage
}
```

> `/cost` может показывать экономию от кешированных токенов.
