# День 2. Формат ответа

## Задание курса

Отправьте один и тот же запрос, но:
- добавьте явное описание формата ответа
- добавьте ограничение на длину ответа
- добавьте условие завершения ответа (stop sequence или явную инструкцию)

Сравните ответы:
- без ограничений
- с ограничениями

**Результат:** Один и тот же запрос с разным уровнем контроля ответа через API

---

## Что уже есть (после дня 1)

```
✅ Gradle-проект с Ktor + kotlinx.serialization
✅ LlmClient интерфейс + OpenAiCompatibleClient
✅ ChatMessage, ChatRequest, ChatResponse модели
✅ LlmResult sealed class для ошибок
✅ AppConfig + ConfigRepository
✅ Main.kt — один запрос, вывод в консоль
```

## Что меняется в этот день

В день 1 `ChatRequest` содержал только `model` и `messages`. Теперь добавляем
параметры контроля ответа. Это **расширение существующих моделей**, а не новый код с нуля.

---

## Что реализуем

### 1. Расширение ChatRequest

**Файл:** `src/.../llm/model/ChatRequest.kt`

```kotlin
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int? = null,
    val stop: List<String>? = null
)
```

> **Важно:** `maxTokens` и `stop` — nullable. Если `null`, поле не попадёт в JSON
> (нужна аннотация `@SerialName` + настройка `encodeDefaults = false` в Json config).
> Это позволяет отправлять запросы как с ограничениями, так и без.

> `stop` — массив stop-sequences. В OpenAI-совместимом API это `stop: ["\n\n", "===END==="]`.
> z.ai поддерживает этот параметр.

### 2. Системный промпт с контролем формата

Это не отдельный файл — это паттерн использования `ChatMessage(role = "system")`.
Но стоит вынести шаблоны в отдельный объект для реиспользования.

**Файл:** `src/.../llm/model/SystemPrompts.kt` (новый)

```kotlin
object SystemPrompts {
    /** Без ограничений — базовый промпт */
    val default = ChatMessage(
        role = "system",
        content = "You are a helpful AI assistant."
    )

    /** С ограничением формата — JSON */
    val jsonFormat = ChatMessage(
        role = "system",
        content = """
            You are a helpful AI assistant.
            You MUST respond in JSON format with the following structure:
            {"answer": "your answer here", "confidence": 0.0-1.0}
            Do not include any text outside the JSON object.
        """.trimIndent()
    )

    /** С ограничением длины */
    fun withMaxLength(maxWords: Int) = ChatMessage(
        role = "system",
        content = """
            You are a helpful AI assistant.
            Your response must be no longer than $maxWords words.
            Be concise and direct.
        """.trimIndent()
    )

    /** С stop sequence */
    val withStopSequence = ChatMessage(
        role = "system",
        content = """
            You are a helpful AI assistant.
            End your response with ===END=== when you are done.
        """.trimIndent()
    )
}
```

### 3. Демонстрационный скрипт

**Файл:** `src/.../Main.kt` — обновить

Заменить одиночный запрос на демонстрацию трёх режимов:

```kotlin
fun main() = runBlocking {
    val config = ConfigRepository().load()
    val client = OpenAiCompatibleClient(config.baseUrl, config.apiKey)
    val question = "Объясни, что такое рекурсия"

    // 1. Без ограничений
    val requestFree = ChatRequest(model = config.model, messages = listOf(
        SystemPrompts.default,
        ChatMessage(role = "user", content = question)
    ))

    // 2. С ограничением длины + формат
    val requestConstrained = ChatRequest(model = config.model, messages = listOf(
        SystemPrompts.jsonFormat,
        ChatMessage(role = "user", content = question)
    ), maxTokens = 200)

    // 3. С stop sequence
    val requestWithStop = ChatRequest(model = config.model, messages = listOf(
        SystemPrompts.withStopSequence,
        ChatMessage(role = "user", content = question)
    ), stop = listOf("===END==="))

    // Выполнить все три и вывести сравнение
    // ...
}
```

---

## Изменения в существующих файлах

| Файл | Изменение |
|---|---|
| `llm/model/ChatRequest.kt` | Добавить `maxTokens: Int? = null`, `stop: List<String>? = null` |
| `Main.kt` | Заменить одиночный запрос на демонстрацию трёх режимов |

## Новые файлы

| Файл | Описание |
|---|---|
| `llm/model/SystemPrompts.kt` | Шаблоны системных промптов с разными уровнями контроля |

---

## На что обратить внимание

1. **Nullable поля в JSON** — при `maxTokens = null` и `stop = null` эти поля
   НЕ должны попадать в сериализованный JSON. Проверить настройку:
   ```kotlin
   Json { encodeDefaults = false }
   ```
   Это глобальная настройка Ktor ContentNegotiation — установить один раз.

2. **Stop sequences** — z.ai может обрабатывать stop sequences иначе, чем OpenAI.
   Если параметр `stop` не поддерживается, fallback — использовать инструкцию
   в system prompt ("End your response with ===END===").

3. **maxTokens vs max_tokens** — в JSON API поле называется `max_tokens`.
   Использовать `@SerialName("max_tokens")` в ChatRequest.

4. **Не ломать день 1** — после расширения ChatRequest базовый запрос
   (без maxTokens и stop) должен работать как раньше. Проверить обратную совместимость.

---

## Критерии проверки

- [ ] Запрос без ограничений работает как в день 1
- [ ] Запрос с `maxTokens = 200` возвращает короткий ответ
- [ ] Запрос с `stop = listOf("===END===")` обрезается на стоп-последовательности
- [ ] System prompt с форматом JSON заставляет модель отвечать в JSON
- [ ] Nullable поля не сериализуются когда `null`

---

## Состояние проекта после дня 2

```
✅ Всё из дня 1
✅ ChatRequest расширен: maxTokens, stop
✅ SystemPrompts — шаблоны с контролем формата
✅ Демонстрация трёх режимов: без ограничений / с ограничениями / stop sequence
❌ Параметры temperature/top_p (день 4)
❌ REPL-режим (день 6)
```

---

## Что изменилось после сравнения с Android-реализацией

> Подробности: `plan/changelog-android-diff.md`

| Изменение | Тип | Описание |
|---|---|---|
| `GenerationPresets` | NEW | Объединённые пресеты (формат + стратегия). Android использует 4 пресета: Standard, Concise, Prompted, Experts. Добавить как удобную обёртку над SystemPrompts + PromptTemplates |

### GenerationPresets (добавить)

**Файл:** `src/.../llm/model/GenerationPresets.kt` (новый)

```kotlin
/**
 * Пресеты генерации — объединяют формат ответа (SystemPrompts) и
 * стратегию рассуждения (PromptTemplates) в один выбор.
 * [ANDROID-DIFF] Android использует похожий подход в GenerationPresets.kt
 */
enum class GenerationPreset(val label: String) {
    STANDARD("standard"),     // SystemPrompts.default + DIRECT
    CONCISE("concise"),       // SystemPrompts.withMaxLength(100) + DIRECT + maxTokens=200
    PROMPTED("prompted"),     // META_PROMPT стратегия
    EXPERTS("experts");       // EXPERT_GROUP стратегия

    fun toSystemMessage(): ChatMessage = when (this) {
        STANDARD -> SystemPrompts.default
        CONCISE  -> SystemPrompts.withMaxLength(100)
        PROMPTED -> PromptTemplates.buildSystemMessage(ReasoningStrategy.META_PROMPT)
        EXPERTS  -> PromptTemplates.buildSystemMessage(ReasoningStrategy.EXPERT_GROUP)
    }

    fun toReasoningStrategy(): ReasoningStrategy? = when (this) {
        STANDARD, CONCISE -> ReasoningStrategy.DIRECT
        PROMPTED          -> ReasoningStrategy.META_PROMPT
        EXPERTS           -> ReasoningStrategy.EXPERT_GROUP
    }
}
```

> Пресеты появятся полностью в днях 3-4 (когда будут PromptTemplates и temperature).
> В день 2 достаточно объявить enum и связать с SystemPrompts.
> **Внимание:** `toSystemMessage()` для PROMPTED и EXPERTS ссылается на
> `PromptTemplates` из дня 3 — этот метод будет реализован в день 3,
> а в день 2 оставить заглушку или `TODO()`.
