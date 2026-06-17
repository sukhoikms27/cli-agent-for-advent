# День 5. Версии моделей

## Задание курса

Выполните один и тот же запрос:
- на слабой модели
- на средней модели
- на сильной модели

Замерьте: время ответа, количество токенов, стоимость (если модель платная).

Сравните: качество ответов, скорость, ресурсоёмкость.

**Результат:** Короткий вывод о различиях между моделями + ссылки

---

## Что уже есть (после дней 1–4)

```
✅ LlmClient + OpenAiCompatibleClient
✅ ChatRequest с параметрами (temperature, maxTokens, stop, seed...)
✅ ChatResponse с Usage (promptTokens, completionTokens, totalTokens)
✅ ReasoningStrategy + PromptTemplates
✅ LlmResult sealed class
```

## Что меняется в этот день

Архитектура не меняется — `LlmClient` уже параметризован через `ChatRequest.model`.
Добавляем только бенчмарк-утилиту и возможность замерять время ответа.

---

## Что реализуем

### 1. Модель-метаданные

**Файл:** `src/.../llm/model/ModelInfo.kt` (новый)

```kotlin
data class ModelInfo(
    val id: String,           // "glm-5.1", "glm-4-flash", etc.
    val tier: ModelTier,      // WEAK, MEDIUM, STRONG
    val contextWindow: Int,   // размер контекстного окна в токенах
    val costPerMillionInput: Double?,   // $ за 1M input токенов
    val costPerMillionOutput: Double?   // $ за 1M output токенов
)

enum class ModelTier {
    WEAK,    // Быстрая, дешёвая, ограниченная
    MEDIUM,  // Баланс скорости и качества
    STRONG   // Максимальное качество, дороже
}
```

> Конкретные модели z.ai для каждого tier нужно определить
> из документации: https://docs.z.ai
> Например: STRONG=glm-5.1, MEDIUM=glm-4-flash, WEAK=glm-4-air

### 2. BenchmarkResult

**Файл:** `src/.../llm/model/BenchmarkResult.kt` (новый)

```kotlin
data class BenchmarkResult(
    val modelId: String,
    val tier: ModelTier,
    val responseText: String,
    val responseTimeMs: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val estimatedCost: Double?
)
```

### 3. Утилита бенчмарка

**Файл:** `src/.../llm/BenchmarkRunner.kt` (новый)

```kotlin
class BenchmarkRunner(private val client: LlmClient) {

    suspend fun runBenchmark(
        models: List<ModelInfo>,
        prompt: String,
        temperature: Double = 0.0
    ): List<BenchmarkResult> {
        return models.map { modelInfo ->
            val request = ChatRequest(
                model = modelInfo.id,
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                temperature = temperature
            )

            val startTime = System.currentTimeMillis()
            val result = client.chat(request)
            val elapsed = System.currentTimeMillis() - startTime

            when (result) {
                is LlmResult.Success -> {
                    val usage = result.data.usage
                    val cost = calculateCost(modelInfo, usage)
                    BenchmarkResult(
                        modelId = modelInfo.id,
                        tier = modelInfo.tier,
                        responseText = result.data.choices.first().message.content,
                        responseTimeMs = elapsed,
                        promptTokens = usage?.promptTokens ?: 0,
                        completionTokens = usage?.completionTokens ?: 0,
                        totalTokens = usage?.totalTokens ?: 0,
                        estimatedCost = cost
                    )
                }
                is LlmResult.Error -> BenchmarkResult(
                    modelId = modelInfo.id,
                    tier = modelInfo.tier,
                    responseText = "ERROR: ${result.message}",
                    responseTimeMs = elapsed,
                    promptTokens = 0, completionTokens = 0, totalTokens = 0,
                    estimatedCost = null
                )
            }
        }
    }

    private fun calculateCost(modelInfo: ModelInfo, usage: Usage?): Double? {
        if (usage == null || modelInfo.costPerMillionInput == null) return null
        val inputCost = (usage.promptTokens / 1_000_000.0) * modelInfo.costPerMillionInput
        val outputCost = (usage.completionTokens / 1_000_000.0) * (modelInfo.costPerMillionOutput ?: 0.0)
        return inputCost + outputCost
    }
}
```

### 4. Демонстрация

**Файл:** `src/.../Main.kt` — обновить

```kotlin
fun main() = runBlocking {
    val config = ConfigRepository().load()
    val client = OpenAiCompatibleClient(config.baseUrl, config.apiKey)

    val models = listOf(
        ModelInfo("glm-4-air", ModelTier.WEAK, contextWindow = 8192,
                  costPerMillionInput = 1.0, costPerMillionOutput = 1.0),
        ModelInfo("glm-4-flash", ModelTier.MEDIUM, contextWindow = 128000,
                  costPerMillionInput = 5.0, costPerMillionOutput = 5.0),
        ModelInfo("glm-5.1", ModelTier.STRONG, contextWindow = 128000,
                  costPerMillionInput = 20.0, costPerMillionOutput = 20.0)
    )

    val benchmark = BenchmarkRunner(client)
    val results = benchmark.runBenchmark(
        models = models,
        prompt = "Объясни разницу между контекстным окном и контекстной длиной в LLM"
    )

    // Таблица сравнения
    println(String.format("%-15s %-8s %8s %8s %10s %8s", "Model", "Tier", "Time", "Tokens", "Cost($)", "Quality"))
    results.forEach { r ->
        println(String.format("%-15s %-8s %6dms %6d %10.6f",
            r.modelId, r.tier.name, r.responseTimeMs, r.totalTokens, r.estimatedCost ?: 0.0))
    }
}
```

> **Важно:** конкретные ID моделей z.ai нужно уточнить в документации.
> Цены — примерные, заменить на реальные из https://docs.z.ai

---

## Изменения в существующих файлах

| Файл | Изменение |
|---|---|
| `Main.kt` | Заменить на демонстрацию бенчмарка моделей |

## Новые файлы

| Файл | Описание |
|---|---|
| `llm/model/ModelInfo.kt` | Метаданные моделей (tier, context window, pricing) |
| `llm/model/BenchmarkResult.kt` | Результат бенчмарка одной модели |
| `llm/BenchmarkRunner.kt` | Утилита запуска бенчмарка по списку моделей |

---

## На что обратить внимание

1. **ID моделей z.ai** — проверить актуальные ID в документации.
   Модели могут называться иначе, чем в примере.

2. **System.currentTimeMillis()** — в корутинном контексте это допустимо.
   В будущем (при стейтфул-агенте) можно заменить на абстракцию `Clock`.

3. **Rate limiting** — z.ai может иметь rate limit на количество запросов.
   При бенчмарке 3+ моделей подряд — добавить задержку между запросами
   (`delay(1000)`) если получаем 429.

4. **Стоимость** — если `Usage` в ответе null (некоторые провайдеры не возвращают),
   `estimatedCost` будет null. Это нормально — отображать как "N/A".

5. **Не ломать предыдущие дни** — BenchmarkRunner — отдельная утилита,
   не затрагивает LlmClient или ChatRequest.

---

## Критерии проверки

- [ ] Три модели вызываются с одним и тем же промптом
- [ ] Для каждой модели замеряется время ответа
- [ ] Выводится количество токенов (prompt, completion, total)
- [ ] (Опционально) Оценивается стоимость запроса
- [ ] Результаты выводятся в виде сравнительной таблицы
- [ ] При недоступности модели — graceful error, не краш

---

## Состояние проекта после дня 5

```
✅ Всё из дней 1–4
✅ ModelInfo — метаданные моделей (tier, pricing, context window)
✅ BenchmarkRunner — утилита сравнения моделей
✅ BenchmarkResult — модель результата бенчмарка
❌ REPL-режим (день 6)
❌ Агент как отдельная сущность (день 6)
```

---

## Что изменилось после сравнения с Android-реализацией

> Подробности: `plan/changelog-android-diff.md`

| Изменение | Тип | Описание |
|---|---|---|
| Конкретные z.ai model IDs | NEW | Android использует конкретные модели: glm-5.1, glm-5, glm-5-turbo, glm-4.7, glm-4.5-air. Заменить абстрактные имена на реальные |
| Pricing объект | NEW | Android имеет отдельный `Pricing` объект с `calculateCost()` по моделям. Вынести расчёт стоимости из BenchmarkRunner |

### Конкретные модели z.ai (обновить)

Заменить абстрактные model IDs на реальные из Android-реализации:

```kotlin
val AVAILABLE_MODELS = listOf(
    ModelInfo("glm-5.1", ModelTier.STRONG, contextWindow = 128000,
              costPerMillionInput = 20.0, costPerMillionOutput = 20.0),
    ModelInfo("glm-5", ModelTier.STRONG, contextWindow = 128000,
              costPerMillionInput = 20.0, costPerMillionOutput = 20.0),
    ModelInfo("glm-5-turbo", ModelTier.MEDIUM, contextWindow = 128000,
              costPerMillionInput = 5.0, costPerMillionOutput = 5.0),
    ModelInfo("glm-4.7", ModelTier.MEDIUM, contextWindow = 128000,
              costPerMillionInput = 5.0, costPerMillionOutput = 5.0),
    ModelInfo("glm-4.5-air", ModelTier.WEAK, contextWindow = 8192,
              costPerMillionInput = 1.0, costPerMillionOutput = 1.0)
)
```

> Цены — примерные. Уточнить в https://docs.z.ai

### Pricing объект (добавить)

**Файл:** `src/.../llm/pricing/Pricing.kt` (новый)

```kotlin
/**
 * Расчёт стоимости по моделям.
 * [ANDROID-DIFF] Аналог Pricing.kt в Android.
 */
object Pricing {
    data class Price(val input: Double, val output: Double)  // $ за 1M токенов

    private val prices = mapOf(
        "glm-5.1"     to Price(input = 20.0, output = 20.0),
        "glm-5"       to Price(input = 20.0, output = 20.0),
        "glm-5-turbo" to Price(input = 5.0, output = 5.0),
        "glm-4.7"     to Price(input = 5.0, output = 5.0),
        "glm-4.5-air" to Price(input = 1.0, output = 1.0)
    )

    fun calculateCost(modelId: String, usage: Usage?): Double? {
        if (usage == null) return null
        val price = prices[modelId] ?: return null
        val inputCost = (usage.promptTokens / 1_000_000.0) * price.input
        val outputCost = (usage.completionTokens / 1_000_000.0) * price.output
        return inputCost + outputCost
    }

    fun getPrice(modelId: String): Price? = prices[modelId]
}
```
