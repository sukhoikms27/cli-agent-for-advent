# День 10. Управление контекстом: разные стратегии

## Задание курса

Реализуйте в агенте 3 разных стратегии управления контекстом и переключатель:

**Стратегия 1: Sliding Window**
- храните только последние N сообщений
- всё остальное отбрасывайте

**Стратегия 2: Sticky Facts / Key-Value Memory**
- отдельный блок «facts» (ключ-значение) для важных данных из диалога
- обновляйте facts после каждого сообщения пользователя
- в запрос: facts + последние N сообщений

**Стратегия 3: Branching (ветки диалога)**
- сохраните checkpoint в диалоге
- создайте 2 ветки от одного места
- продолжите диалог в каждой ветке independently
- переключайтесь между ветками

Протестируйте на одном сценарии и сравните: качество, стабильность, токены, удобство.

**Результат:** Агент с 3 стратегиями управления контекстом + сравнение результатов

---

## Что уже есть (после дней 1–9)

```
✅ ContextAwareAgent с персистентной историей (SQLite)
✅ HistoryCompressor — сжатие через summary (день 9)
✅ SummaryStrategy — 4-я стратегия (создана в день 9)
✅ TokenCounter с оценкой и агрегацией
✅ /stats, /compress, /summary REPL-команды
✅ MemoryStore + SqliteMemoryStore
```

## Что меняется в этот день

**Третий крупный архитектурный шаг:** вводим `ContextStrategy` интерфейс и
`ContextManager` для переключения. До сих пор управление контекстом было
зашито в ContextAwareAgent. Теперь:
1. `ContextStrategy` — интерфейс стратегии (как строить messages для LLM)
2. **4 реализации**: SlidingWindow, StickyFacts, Summary, Branching
3. `ContextManager` — переключатель стратегий
4. ContextAwareAgent делегирует управление контекстом стратегии

---

## Что реализуем

### 1. ContextStrategy — интерфейс

**Файл:** `src/.../context/strategy/ContextStrategy.kt` (новый)

```kotlin
interface ContextStrategy {
    /**
     * Строит список сообщений для отправки в LLM.
     * Принимает полную историю, возвращает то, что реально пойдёт в запрос.
     */
    fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage>

    /** Имя стратегии для отображения */
    fun getName(): String

    /** Описание стратегии для CLI-справки */
    fun getDescription(): String    // [ANDROID-DIFF] Android имеет displayName + description

    /** Нужна ли компрессия при текущем состоянии */
    fun needsCompression(): Boolean = false  // [ANDROID-DIFF] Android: needsCompression()

    /** Обработать ответ модели (для стратегий, обновляющих состояние) */
    suspend fun onAssistantResponse(assistantMessage: ChatMessage) {}

    /** Сбросить внутреннее состояние стратегии */
    fun reset() {}
}
```

> **onAssistantResponse** — хук для StickyFacts: после ответа модели
> стратегия может обновить facts. По умолчанию — no-op.

> **needsCompression** — [ANDROID-DIFF] позволяет стратегии сигнализировать,
> что контекст скоро выйдет за лимит. Используется для автокомпрессии.

> **getDescription** — [ANDROID-DIFF] человекочитаемое описание для `/strategy` команды.

### 2. SlidingWindowStrategy

**Файл:** `src/.../context/strategy/SlidingWindowStrategy.kt` (новый)

```kotlin
class SlidingWindowStrategy(
    private val windowSize: Int = 10
) : ContextStrategy {

    override fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage> {
        val allMessages = history + newMessage
        val windowedMessages = allMessages.takeLast(windowSize)
        return listOf(systemPrompt) + windowedMessages
    }

    override fun getName(): String = "sliding_window"

    override fun getDescription(): String =
        "Keeps last $windowSize messages, discards older ones"

    override fun reset() {
        // Нет внутреннего состояния
    }
}
```

> **Простейшая стратегия** — никаких побочных эффектов, просто обрезка.
> Токены: O(windowSize), качество: теряет всё старее windowSize.

### 3. StickyFactsStrategy

**Файл:** `src/.../context/strategy/StickyFactsStrategy.kt` (новый)

```kotlin
class StickyFactsStrategy(
    private val llmClient: LlmClient,
    private val model: String,
    private val windowSize: Int = 10,
    private val memoryStore: MemoryStore? = null,
    private val sessionId: String? = null
) : ContextStrategy {

    private val facts = mutableMapOf<String, String>()

    override fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage> {
        val recentMessages = history.takeLast(windowSize)
        val factsMessage = buildFactsMessage()

        return listOf(systemPrompt, factsMessage) + recentMessages + newMessage
    }

    override fun getName(): String = "sticky_facts"

    override fun getDescription(): String =
        "Extracts key facts via LLM + keeps last $windowSize messages"

    override suspend fun onAssistantResponse(assistantMessage: ChatMessage) {
        updateFacts(assistantMessage)
    }

    private suspend fun updateFacts(recentMessage: ChatMessage) {
        // [ANDROID-DIFF] Android использует JSON-формат для фактов:
        // {"goal": "...", "constraint": "...", ...}
        val extractionPrompt = """
            Извлеки ключевые факты из диалога в формате JSON.
            Каждый факт — пара ключ:значение.
            Извлекай только НОВЫЕ или ОБНОВЛЁННЫЕ факты.
            Включи: цели, ограничения, предпочтения, решения, важные числа/имена.

            Текущие факты:
            ${facts.entries.joinToString("\n") { "${it.key}: ${it.value}" }}

            Последнее сообщение:
            [${recentMessage.role}]: ${recentMessage.content}

            Выведи только обновлённый список фактов в формате:
            key1: value1
            key2: value2
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = extractionPrompt)),
            temperature = 0.0
        )

        when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> {
                val factsText = result.data.choices.first().message.content
                parseFacts(factsText)
            }
            is LlmResult.Error -> { /* оставить facts как есть */ }
        }

        // Персистентность
        if (memoryStore != null && sessionId != null) {
            memoryStore.saveFacts(sessionId, facts.toMap())
        }
    }

    private fun buildFactsMessage(): ChatMessage {
        if (facts.isEmpty()) {
            return ChatMessage(role = "system", content = "[No facts recorded yet]")
        }
        val factsText = facts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        return ChatMessage(role = "system", content = "[Key facts from conversation]\n$factsText")
    }

    private fun parseFacts(factsText: String) {
        factsText.lineSequence().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().removePrefix("- ")
                val value = parts[1].trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    facts[key] = value
                }
            }
        }
    }

    fun getFacts(): Map<String, String> = facts.toMap()

    override fun reset() {
        facts.clear()
    }
}
```

> **Дополнительный LLM-вызов** — каждый `onAssistantResponse` вызывает LLM
> для извлечения фактов. Это стоит токены и время. Для курса — ОК.
> Оптимизация: извлекать факты раз в N сообщений, а не каждый раз.

**Файл:** `src/.../memory/Facts.kt` (новый)

```kotlin
@Serializable
data class Facts(
    val entries: Map<String, String>
)
```

**SqliteMemoryStore — добавить таблицу facts:**

```sql
CREATE TABLE IF NOT EXISTS facts (
    session_id TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (session_id, key),
    FOREIGN KEY (session_id) REFERENCES chats(id)
)
```

### 4. SummaryStrategy

Уже создана в день 9 (см. `day-09.md` — секция «SummaryStrategy как 4-я стратегия»).
Подключается в этот день через ContextManager.

### 5. BranchingStrategy — персистентная

**Файл:** `src/.../context/strategy/BranchingStrategy.kt` (новый)

```kotlin
/**
 * [ANDROID-DIFF] Ветвление — персистентное (хранится в SQLite),
 * а не in-memory как в исходном плане.
 * Android хранит ветки в таблице dialog_branches.
 */
class BranchingStrategy(
    private val memoryStore: MemoryStore,
    private val sessionId: String,
    private val windowSize: Int = 10
) : ContextStrategy {

    private var currentBranchId: String = "main"
    private val branches = mutableMapOf<String, Branch>()

    data class Branch(
        val id: String,
        val name: String,
        val parentId: String?,       // от какой ветки ответвились
        val leafMessageId: String?,  // [ANDROID-DIFF] ID последнего сообщения в ветке
        val fromIndex: Int           // индекс сообщения, от которого ветка
    )

    override fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage> {
        val branch = branches[currentBranchId]
        val messages = if (branch != null && branch.fromIndex < history.size) {
            // [ANDROID-DIFF] Используем parentId для обхода дерева
            // Берём сообщения от корня до точки ветвления
            history.subList(0, branch.fromIndex + 1) + newMessage
        } else {
            history + newMessage
        }
        // Скользящее окно поверх ветки
        val windowed = messages.takeLast(windowSize)
        return listOf(systemPrompt) + windowed
    }

    override fun getName(): String = "branching"

    override fun getDescription(): String =
        "Creates branches from checkpoints, switch between them"

    fun createBranch(name: String, fromMessageIndex: Int, leafMessageId: String? = null): String {
        val branchId = "branch-${java.util.UUID.randomUUID()}"
        val branch = Branch(
            id = branchId,
            name = name,
            parentId = currentBranchId,
            leafMessageId = leafMessageId,  // [ANDROID-DIFF]
            fromIndex = fromMessageIndex
        )
        branches[branchId] = branch

        // [ANDROID-DIFF] Персистентное сохранение
        memoryStore.createBranch(sessionId, name, leafMessageId)

        return branchId
    }

    fun switchBranch(branchId: String): Result<String> {
        if (branchId !in branches && branchId != "main") {
            return Result.failure(IllegalArgumentException("Branch $branchId not found"))
        }
        currentBranchId = branchId
        val branchName = branches[branchId]?.name ?: "main"
        return Result.success(branchName)
    }

    fun listBranches(): List<String> {
        return listOf("main${if (currentBranchId == "main") " (current)" else ""}") +
            branches.entries.map { (id, b) ->
                "${b.name}${if (id == currentBranchId) " (current)" else ""}"
            }
    }

    fun getCurrentBranchId(): String = currentBranchId

    override fun reset() {
        branches.clear()
        currentBranchId = "main"
    }

    /**
     * [ANDROID-DIFF] LLM-генерация имени ветки (опционально).
     * Android генерирует имена через LLM по последним 4 сообщениям.
     */
    suspend fun maybeGenerateBranchName(
        llmClient: LlmClient,
        model: String,
        branchId: String,
        recentMessages: List<ChatMessage>
    ) {
        val lastMessages = recentMessages.takeLast(4)
        val prompt = "Generate a short descriptive name (2-4 words, in English) for a conversation branch based on these messages. Output only the name."
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = prompt),
                ChatMessage(role = "user", content = lastMessages.joinToString("\n") { "[${it.role}]: ${it.content.take(100)}" })
            ),
            temperature = 0.3
        )
        when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> {
                val name = result.data.choices.first().message.content.trim().take(30)
                branches[branchId]?.let { branches[branchId] = it.copy(name = name) }
            }
            is LlmResult.Error -> { /* fallback: имя по умолчанию */ }
        }
    }
}
```

### 6. Стратегия как enum (для CLI-флага)

**Не отдельный файл** — простой enum для парсинга CLI-флага `--context-strategy`.
Без `@Serializable` — в CLI сериализация не нужна (в отличие от Android с Room).

```kotlin
// Внутри ContextStrategy.kt или отдельный enum
enum class ContextStrategyType {
    SLIDING_WINDOW,
    STICKY_FACTS,
    SUMMARY,
    BRANCHING
}
```

### 7. Персистентное хранение веток в SQLite

```sql
-- [ANDROID-DIFF] Персистентное хранение веток в SQLite
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

### 8. ContextManager — переключатель стратегий

**Файл:** `src/.../context/ContextManager.kt` (новый)

```kotlin
class ContextManager(
    private var strategy: ContextStrategy
) {
    fun getStrategy(): ContextStrategy = strategy

    fun switchStrategy(newStrategy: ContextStrategy): String {
        val oldName = strategy.getName()
        strategy.reset()
        strategy = newStrategy
        return "Switched from $oldName to ${newStrategy.getName()}"
    }

    fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage> {
        return strategy.buildMessages(history, newMessage, systemPrompt)
    }

    suspend fun onAssistantResponse(assistantMessage: ChatMessage) {
        strategy.onAssistantResponse(assistantMessage)
    }

    fun needsCompression(): Boolean = strategy.needsCompression()

    fun reset() {
        strategy.reset()
    }
}
```

### 9. Интеграция в ContextAwareAgent

**Файл:** `src/.../agent/ContextAwareAgent.kt` — обновить

```kotlin
class ContextAwareAgent(
    private val llmClient: LlmClient,
    private val memoryStore: MemoryStore,
    private val model: String,
    private val sessionId: String,
    private val contextManager: ContextManager,  // NEW: вместо systemPrompt + strategy
    private val tokenCounter: TokenCounter = TokenCounter(),
    private val contextLimit: Int = 128000
) : Agent {

    override suspend fun chat(userMessage: String): String {
        ensureLoaded()

        val userMsg = ChatMessage(role = "user", content = userMessage)
        history.add(userMsg)
        memoryStore.saveMessage(sessionId, userMsg)

        // Делегировать построение messages стратегии
        val systemPrompt = SystemPrompts.default  // или из конфига
        val messagesToSend = contextManager.buildMessages(history, userMsg, systemPrompt)

        val request = ChatRequest(model = model, messages = messagesToSend)
        val result = llmClient.chat(request)

        val responseText = when (result) {
            is LlmResult.Success -> {
                val usage = result.data.usage
                tokenCounter.recordUsage(sessionId, usage)

                val assistantMsg = result.data.choices.first().message
                history.add(assistantMsg)
                memoryStore.saveMessage(sessionId, assistantMsg)

                // Дать стратегии обработать ответ (например, обновить facts)
                contextManager.onAssistantResponse(assistantMsg)

                assistantMsg.content
            }
            is LlmResult.Error -> "Error: ${result.code} — ${result.message}"
        }

        return responseText
    }

    fun switchStrategy(newStrategy: ContextStrategy): String =
        contextManager.switchStrategy(newStrategy)

    fun getCurrentStrategyName(): String =
        contextManager.getStrategy().getName()
}
```

> **Рефакторинг** — `systemPrompt` и `reasoningStrategy` убираются из
> конструктора агента. Теперь ими управляет ContextManager.
> Это упрощает агента и даёт полный контроль над контекстом стратегии.

### 10. CLI-команды

**Файл:** `src/.../cli/ChatCommand.kt` — обновить

Новый флаг:
```kotlin
private val contextStrategy by option("--context-strategy",
    help = "Context strategy: sliding, facts, summary, branch").default("sliding")
```

Новые REPL-команды:

```
/strategy [sliding|facts|summary|branch]   — переключить стратегию
/facts                                     — показать текущие факты (для sticky-facts)
/branch create <name> [at <N>]             — создать ветку от сообщения N
/branch list                               — список веток
/branch switch <name>                      — переключиться на ветку
```

> [ANDROID-DIFF] Добавлена `summary` как 4-я опция в `/strategy`.

### 11. MemoryStore — расширить для Branching

**Файл:** `src/.../memory/MemoryStore.kt` — добавить

```kotlin
interface MemoryStore {
    // ... существующие методы ...

    // Персистентное хранение веток
    suspend fun createBranch(chatId: String, name: String, leafMessageId: String?): String
    suspend fun listBranches(chatId: String): List<BranchingStrategy.Branch>
    suspend fun deleteBranch(branchId: String)
}
```

---

## Изменения в существующих файлах

| Файл | Изменение |
|---|---|
| `context/strategy/ContextStrategy.kt` | Создать — интерфейс стратегии (+ needsCompression, getDescription, ContextStrategyType enum) |
| `context/strategy/SlidingWindowStrategy.kt` | Создать |
| `context/strategy/StickyFactsStrategy.kt` | Создать |
| `context/strategy/SummaryStrategy.kt` | Создана в день 9 — подключить |
| `context/strategy/BranchingStrategy.kt` | Создать — **персистентная** (не in-memory) |
| `context/ContextManager.kt` | Создать — переключатель стратегий |
| `memory/Facts.kt` | Создать — модель facts |
| `memory/MemoryStore.kt` | Добавить createBranch/listBranches/deleteBranch |
| `memory/SqliteMemoryStore.kt` | Добавить таблицы facts и dialog_branches |
| `agent/ContextAwareAgent.kt` | Рефакторинг: contextManager вместо systemPrompt/reasoningStrategy |
| `cli/ChatCommand.kt` | Добавить --context-strategy, /strategy, /facts, /branch команды |

## Новые файлы

| Файл | Описание |
|---|---|
| `context/strategy/ContextStrategy.kt` | Интерфейс стратегии + ContextStrategyType enum |
| `context/strategy/SlidingWindowStrategy.kt` | Скользящее окно |
| `context/strategy/StickyFactsStrategy.kt` | Ключевые факты + окно |
| `context/strategy/SummaryStrategy.kt` | Суммаризация + окно (из дня 9) |
| `context/strategy/BranchingStrategy.kt` | Ветки диалога (персистентные, с внутренним Branch data class) |
| `context/ContextManager.kt` | Переключатель стратегий |
| `memory/Facts.kt` | Модель key-value фактов |

---

## На что обратить внимание

1. **Рефакторинг ContextAwareAgent** — удаление `systemPrompt` и
   `reasoningStrategy` из конструктора — breaking change. Убедиться,
   что все вызовы обновлены. `ContextManager` теперь отвечает за всё.

2. **HistoryCompressor (день 9)** — не удалять! Это отдельная стратегия
   (сжатие через summary), которая может комбинироваться с любой из трёх.
   Например: SlidingWindow + Compression = «скользящее окно с сжатием
   отброшенных сообщений в summary на случай возврата».

3. **StickyFacts — стоимость** — каждый ответ модели вызывает дополнительный
   LLM-запрос для извлечения фактов. Это удваивает стоимость и время.
   Для демо — ОК, для продакшена — оптимизировать (раз в 5 сообщений,
   или только при определённых триггерах).

4. **Branching — персистентность** — [ANDROID-DIFF] ветки хранятся в SQLite,
   а не только в памяти. При перезапуске ветки не теряются.
   Это сложнее in-memory подхода, но даёт лучший UX.

5. **Обратная совместимость** — если не указана стратегия, по умолчанию
   используется SlidingWindow (windowSize = безлимитный, т.е. вся история).
   Это эквивалентно поведению до дня 10.

6. **4 стратегии, не 3** — [ANDROID-DIFF] добавлена SummaryStrategy
   (из дня 9). Курс требует 3, но Summary — естественное развитие
   HistoryCompressor. Не удаляет, а дополняет.

7. **Comparison** — для курса нужно прогнать один и тот же сценарий
   («собираем ТЗ 10–15 сообщений») на всех трёх стратегиях и сравнить.
   Можно автоматизировать: подготовить скрипт с последовательностью сообщений.

---

## Критерии проверки

- [ ] `/strategy sliding` — переключает на Sliding Window
- [ ] `/strategy facts` — переключает на Sticky Facts, извлекает факты из диалога
- [ ] `/strategy summary` — переключает на Summary (автосжатие)
- [ ] `/strategy branch` — переключает на Branching
- [ ] `/facts` — показывает текущие извлечённые факты
- [ ] `/branch create feature-a at 5` — создаёт ветку от 5-го сообщения
- [ ] `/branch switch feature-a` — переключается на ветку
- [ ] `/branch list` — показывает все ветки
- [ ] Переключение стратегий не теряет историю (только обрезает для LLM)
- [ ] Токены корректно считаются для каждой стратегии
- [ ] Ветки сохраняются в SQLite (персистентность)

---

## Состояние проекта после дня 10

```
✅ Всё из дней 1–9
✅ ContextStrategy интерфейс + 4 реализации
✅ ContextManager — переключатель стратегий
✅ SlidingWindowStrategy — простое окно
✅ StickyFactsStrategy — факты + окно (с LLM-извлечением)
✅ SummaryStrategy — автосуммаризация + окно
✅ BranchingStrategy — персистентные ветки диалога
✅ /strategy, /facts, /branch REPL-команды
✅ Facts persistence в SQLite
✅ Branch persistence в SQLite (dialog_branches)
✅ Рефакторинг ContextAwareAgent через ContextManager
✅ ContextStrategyType — enum (без @Serializable, внутри ContextStrategy.kt)
✅ Branch data class — внутри BranchingStrategy (без отдельного DialogBranch)
```

## Итоговая архитектура (после дня 10)

```
CLI (clikt REPL)
  → Agent (ContextAwareAgent)
    → ContextManager (стратегия)
      → ContextStrategy (sliding / facts / summary / branch)
    → LlmClient (OpenAiCompatibleClient → Ktor → z.ai)
    → MemoryStore (SqliteMemoryStore → SQLite)
    → TokenCounter (подсчёт и оценка токенов)
    → HistoryCompressor (сжатие через summary)
```

Все 10 дней реализованы. Далее — неделя 3 (стейтфул-агент, Profile, StateMachine,
InvariantChecker) — см. global-plan.md Фаза 4.

---

## Что изменилось после сравнения с Android-реализацией

> Подробности: `plan/changelog-android-diff.md`

| Изменение | Тип | Описание |
|---|---|---|
| 4 стратегии вместо 3 | CHANGED | ✅ Добавлена SummaryStrategy (из дня 9) |
| `needsCompression()` в интерфейсе | NEW | ✅ Полезен для автокомпрессии в REPL |
| `getDescription()` в интерфейсе | NEW | ✅ Полезен для `/help` и `/strategy` в REPL |
| Branching — персистентная | CHANGED | ✅ Ветки в SQLite, не в памяти |
| ~~`ContextStrategyType` @Serializable~~ | REJECTED | В CLI стратегия из флага `--strategy`, сериализация не нужна. Plain enum внутри ContextStrategy.kt достаточен |
| ~~`DialogBranch` отдельный файл~~ | REJECTED | С raw JDBC нет Clean Architecture слоёв. Branch data class внутри BranchingStrategy достаточен |
| LLM-генерация имён веток | NEW | Опционально — `maybeGenerateBranchName()` в BranchingStrategy |
| `leafMessageId` в Branch | NEW | ✅ ID последнего сообщения в ветке для навигации по дереву |
