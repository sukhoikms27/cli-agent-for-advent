# День 4. Температура

## Задание курса

Выполните один и тот же запрос с параметрами:
- temperature = 0
- temperature = 0.7
- temperature = 1.2

Сравните ответы по: точности, креативности, разнообразию.

Сформулируйте: для каких задач лучше подходит каждая настройка.

**Результат:** Примеры ответов с разной температурой и выводы по их использованию

---

## Что уже есть (после дней 1–3)

```
✅ LlmClient + OpenAiCompatibleClient
✅ ChatRequest (model, messages, maxTokens?, stop?)
✅ ChatResponse с Usage (promptTokens, completionTokens, totalTokens)
✅ SystemPrompts (контроль формата)
✅ ReasoningStrategy + PromptTemplates (стратегии рассуждения)
✅ LlmResult sealed class
```

## Что меняется в этот день

До сих пор `ChatRequest` не содержал параметров LLM (temperature, top_p, top_k).
Теперь добавляем `LlmParameters` — отдельную модель, которая группирует все
параметры генерации. Это архитектурное решение, а не просто добавление поля.

---

## Что реализуем

### 1. Расширение ChatRequest — плоские поля

Поля `maxTokens` и `stop` уже добавлены в день 2. Теперь добавляем
остальные параметры LLM. **Оставляем плоскую структуру** — это проще
для сериализации и совместимости с OpenAI-совместимым API.

> **Решение:** НЕ создавать отдельный `GenerationConfig` или `LlmParameters`.
> В CLI параметры передаются через флаги и собираются прямо в ChatRequest.
> Отдельный доменный слой (как `GenerationConfig` в Android) нужен только
> при наличии UI для редактирования настроек. В CLI это оверхед.

```kotlin
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val seed: Long? = null
)

> **@SerialName маппинг:**
> - `topP` → `@SerialName("top_p")`
> - `maxTokens` → `@SerialName("max_tokens")`
> - `frequencyPenalty` → `@SerialName("frequency_penalty")`
> - `presencePenalty` → `@SerialName("presence_penalty")`

### 2. Обновление OpenAiCompatibleClient

`OpenAiCompatibleClient` уже сериализует `ChatRequest` в JSON. После добавления
новых полей сериализация будет работать автоматически (при `encodeDefaults = false`).

Никаких изменений в клиенте не требуется — новые nullable-поля просто попадут
в JSON если заданы, и будут пропущены если `null`.

### 3. Демонстрация: температура и воспроизводимость

**Файл:** `src/.../Main.kt` — обновить

```kotlin
fun main() = runBlocking {
    val config = ConfigRepository().load()
    val client = OpenAiCompatibleClient(config.baseUrl, config.apiKey)
    val question = "Напиши короткую историю о коте, который научился программировать"

    val temperatures = listOf(0.0, 0.7, 1.2)

    temperatures.forEach { temp ->
        println("=== temperature = $temp ===")
        repeat(3) { iteration ->
            val request = ChatRequest(
                model = config.model,
                messages = listOf(
                    ChatMessage(role = "user", content = question)
                ),
                temperature = temp
            )
            when (val result = client.chat(request)) {
                is LlmResult.Success -> {
                    val tokens = result.data.usage
                    println("[#${iteration + 1}] tokens=${tokens?.totalTokens}")
                    println(result.data.choices.first().message.content)
                    println()
                }
                is LlmResult.Error -> println("ERROR: ${result.message}")
            }
        }
    }
}
```

> Три итерации для каждой температуры — чтобы увидеть разнообразие.
> При temp=0 ответы должны быть практически идентичны.
> При temp=1.2 — заметно отличаться.

### 4. Демонстрация: seed для воспроизводимости

```kotlin
// С seed ответы должны быть идентичны при одинаковых параметрах
val requestWithSeed = ChatRequest(
    model = config.model,
    messages = listOf(ChatMessage(role = "user", content = question)),
    temperature = 0.7,
    seed = 42L
)
```

> **Важно:** z.ai может не поддерживать `seed`. Если API возвращает ошибку
> на неизвестный параметр — обработать gracefully, без краша.
> В OpenAiCompatibleClient: если ответ 400 и причина в неизвестном поле —
> логировать предупреждение и продолжить.

---

## Изменения в существующих файлах

| Файл | Изменение |
|---|---|
| `llm/model/ChatRequest.kt` | Добавить temperature, topP, frequencyPenalty, presencePenalty, seed (все nullable) |
| `Main.kt` | Заменить на демонстрацию температуры |

---

## На что обратить внимание

1. **Обратная совместимость** — после добавления новых полей в ChatRequest,
   все вызовы из дней 1–3 должны работать без изменений (новые поля = null).

2. **Диапазоны значений** — z.ai может иметь другие ограничения на temperature
   (не 0–2, а 0–1). Проверить документацию. Если при temperature=1.2 получаем
   ошибку — ограничить до поддерживаемого диапазона.

3. **seed — не гарантирован** — даже при seed=42 ответы могут немного отличаться
   из-за особенностей инфраструктуры провайдера. Это нормально для демонстрации.

4. **Cost** — при 3 итерациях × 3 температуры = 9 API-вызовов.
   С короткими вопросами это дёшево, но иметь в виду.

5. **Не создавать LlmParameters или GenerationConfig** — плоская структура
   в ChatRequest проще и совместимее. В CLI нет UI для редактирования
   настроек, поэтому отдельный доменный слой параметров не нужен.

---

## Критерии проверки

- [ ] `temperature = 0` — три ответа практически идентичны
- [ ] `temperature = 0.7` — ответы отличаются, но в рамках темы
- [ ] `temperature = 1.2` — ответы заметно отличаются, возможен уход от темы
- [ ] Все предыдущие вызовы (дни 1–3) работают без изменений
- [ ] Nullable-поля не сериализуются когда null

---

## Состояние проекта после дня 4

```
✅ Всё из дней 1–3
✅ ChatRequest расширен: temperature, topP, seed, penalties
✅ Демонстрация влияния температуры на ответы
❌ Сравнение моделей (день 5)
❌ REPL-режим (день 6)
```

---

## Что изменилось после сравнения с Android-реализацией

> Подробности: `plan/changelog-android-diff.md`

| Изменение | Тип | Описание |
|---|---|---|
| ~~`GenerationConfig`~~ | REJECTED | Android группирует параметры в GenerationConfig, но в CLI флаги → ChatRequest напрямую. Отдельный слой — оверхед без UI для настроек. К тому же создал бы циклическую зависимость с ContextStrategyType (день 10) |
| Пресеты с предустановленными параметрами | KEEP | Каждый `GenerationPreset` может задавать дефолтные значения для ChatRequest-полей: Standard (temp=0.7), Concise (temp=0.3, maxTokens=200), Prompted (temp=0.7, meta-prompt), Experts (temp=0.7, expert-group) |
