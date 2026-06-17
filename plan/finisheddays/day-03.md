# День 3. Разные способы рассуждения

## Задание курса

Возьмите одну задачу (логическую, алгоритмическую или аналитическую).
Решите её через API четырьмя способами:
- получите прямой ответ без дополнительных инструкций
- добавьте в промпт инструкцию: «решай пошагово»
- попросите модель сначала составить промпт для решения задачи, а затем используйте его
- создайте в промпте группу экспертов (аналитик, инженер, критик) и получите решение от каждого

Сравните: отличаются ли ответы, какой способ дал наиболее точный результат.

**Результат:** Несколько решений одной задачи и их сравнение

---

## Что уже есть (после дней 1–2)

```
✅ LlmClient + OpenAiCompatibleClient (Ktor)
✅ ChatMessage, ChatRequest (с maxTokens, stop), ChatResponse
✅ SystemPrompts — шаблоны с контролем формата
✅ LlmResult sealed class
✅ AppConfig + ConfigRepository
```

## Что меняется в этот день

Этот день — про **промпт-инженерию**, а не про архитектуру. Основной код не меняется,
но мы вводим **паттерн промпт-шаблонов**, который понадобится для будущих дней
(агент с разными режимами рассуждения, стейтфул-агент с PromptBuilder).

---

## Что реализуем

### 1. Промпт-шаблоны стратегий рассуждения

**Файл:** `src/.../llm/model/ReasoningStrategy.kt` (новый)

```kotlin
/**
 * Стратегия рассуждения — определяет, как формулировать промпт
 * для решения задачи. Каждая стратегия модифицирует system message
 * и (опционально) структуру запроса.
 */
enum class ReasoningStrategy(val label: String) {
    DIRECT("direct"),             // Прямой ответ без инструкций
    STEP_BY_STEP("step_by_step"), // «Решай пошагово»
    META_PROMPT("meta_prompt"),   // Сначала составь промпт, потом реши
    EXPERT_GROUP("expert_group")  // Группа экспертов
}
```

**Файл:** `src/.../llm/model/PromptTemplates.kt` (новый)

```kotlin
object PromptTemplates {

    fun buildSystemMessage(strategy: ReasoningStrategy): ChatMessage {
        return when (strategy) {
            ReasoningStrategy.DIRECT -> ChatMessage(
                role = "system",
                content = "You are a helpful AI assistant. Answer the question directly."
            )
            ReasoningStrategy.STEP_BY_STEP -> ChatMessage(
                role = "system",
                content = """
                    You are a helpful AI assistant.
                    Solve the problem step by step:
                    1. Analyze the problem
                    2. Identify key components
                    3. Solve each step
                    4. Provide the final answer
                    Show your reasoning at each step.
                """.trimIndent()
            )
            ReasoningStrategy.META_PROMPT -> ChatMessage(
                role = "system",
                content = """
                    You are a helpful AI assistant.
                    First, create an optimal prompt for solving the given problem.
                    Then, use that prompt to solve the problem.
                    Format your response as:
                    ===PROMPT===
                    [your optimized prompt here]
                    ===SOLUTION===
                    [your solution using the prompt]
                """.trimIndent()
            )
            ReasoningStrategy.EXPERT_GROUP -> ChatMessage(
                role = "system",
                content = """
                    You are a panel of three experts analyzing the problem:
                    - **Analyst**: Identifies the core problem and constraints
                    - **Engineer**: Proposes a practical solution
                    - **Critic**: Reviews the solution for flaws and improvements
                    Each expert provides their analysis, then you give a final synthesis.
                    Format:
                    ### Analyst
                    [analysis]
                    ### Engineer
                    [solution]
                    ### Critic
                    [review]
                    ### Final Answer
                    [synthesis]
                """.trimIndent()
            )
        }
    }
}
```

### 2. Утилита для сравнения ответов

**Файл:** `src/.../Main.kt` — обновить

Демонстрация: одна задача → 4 стратегии → сравнение.

```kotlin
fun main() = runBlocking {
    val config = ConfigRepository().load()
    val client = OpenAiCompatibleClient(config.baseUrl, config.apiKey)
    val task = "Сколько способов раздать 10 одинаковых шаров 3 различным корзинам, если каждая корзина должна содержать хотя бы 2 шара?"

    val results = ReasoningStrategy.entries.associateWith { strategy ->
        val systemMsg = PromptTemplates.buildSystemMessage(strategy)
        val request = ChatRequest(
            model = config.model,
            messages = listOf(systemMsg, ChatMessage(role = "user", content = task))
        )
        when (val result = client.chat(request)) {
            is LlmResult.Success -> result.data.choices.first().message.content
            is LlmResult.Error -> "ERROR: ${result.code} — ${result.message}"
        }
    }

    // Вывод сравнения
    results.forEach { (strategy, answer) ->
        println("=== ${strategy.label} ===")
        println(answer)
        println()
    }
}
```

### 3. Последовательные запросы для META_PROMPT

Для стратегии `META_PROMPT` курс требует **два последовательных запроса**:
1. Сначала модель создаёт промпт
2. Затем этот промпт используется для решения

Добавить вспомогательный метод в `OpenAiCompatibleClient` или в `Main.kt`:

```kotlin
// Двухшаговый запрос для meta-prompt стратегии
suspend fun metaPromptSolve(client: LlmClient, config: AppConfig, task: String): String {
    // Шаг 1: Просим модель создать промпт
    val step1Request = ChatRequest(
        model = config.model,
        messages = listOf(
            ChatMessage(role = "system", content = "Create an optimal prompt for solving the following problem. Output only the prompt, nothing else."),
            ChatMessage(role = "user", content = task)
        )
    )
    val generatedPrompt = when (val r = client.chat(step1Request)) {
        is LlmResult.Success -> r.data.choices.first().message.content
        is LlmResult.Error -> return "ERROR: ${r.message}"
    }

    // Шаг 2: Используем сгенерированный промпт
    val step2Request = ChatRequest(
        model = config.model,
        messages = listOf(
            ChatMessage(role = "system", content = generatedPrompt),
            ChatMessage(role = "user", content = task)
        )
    )
    return when (val r = client.chat(step2Request)) {
        is LlmResult.Success -> r.data.choices.first().message.content
        is LlmResult.Error -> "ERROR: ${r.message}"
    }
}
```

> Этот двухшаговый паттерн — прототип будущего **агентного цикла**
> (день 6+), где агент делает несколько LLM-вызовов для одной задачи.

---

## Изменения в существующих файлах

| Файл | Изменение |
|---|---|
| `Main.kt` | Заменить на демонстрацию 4 стратегий + сравнение |

## Новые файлы

| Файл | Описание |
|---|---|
| `llm/model/ReasoningStrategy.kt` | Enum стратегий рассуждения |
| `llm/model/PromptTemplates.kt` | Шаблоны system-промптов для каждой стратегии |

---

## На что обратить внимание

1. **Не дублировать SystemPrompts** — `SystemPrompts` из дня 2 отвечает за
   контроль формата (JSON, длина, stop). `PromptTemplates` из дня 3 — за
   стратегию рассуждения. Это разные ортогональные оси. В будущем они
   комбинируются: стратегия рассуждения + формат ответа.

2. **Cost awareness** — стратегия EXPERT_GROUP генерирует в 3–4 раза больше
   токенов. Если `usage` в ответе не null, выводить количество токенов для сравнения.

3. **META_PROMPT — два запроса** — это удваивает стоимость и время.
   Это нормально для демонстрации, но в продакшене нужен кэш сгенерированных промптов.

4. **Результаты могут быть одинаковыми** — для простых задач все 4 стратегии
   дадут одинаковый ответ. Для демонстрации брать задачу, где рассуждение реально
   влияет на результат (логическая/математическая задача).

---

## Критерии проверки

- [ ] DIRECT — простой ответ без рассуждений
- [ ] STEP_BY_STEP — ответ с пошаговым разбором
- [ ] META_PROMPT — два запроса, второй использует промпт из первого
- [ ] EXPERT_GROUP — ответ от трёх "экспертов" + синтез
- [ ] Выводится сравнение всех 4 стратегий
- [ ] (Опционально) Выводится количество токенов для каждой стратегии

---

## Состояние проекта после дня 3

```
✅ Всё из дней 1–2
✅ ReasoningStrategy enum — 4 стратегии рассуждения
✅ PromptTemplates — шаблоны для каждой стратегии
✅ Двухшаговый запрос (meta-prompt) — прототип агентного цикла
❌ LlmParameters (temperature, top_p) (день 4)
❌ REPL-режим (день 6)
```

---

## Что изменилось после сравнения с Android-реализацией

> Подробности: `plan/changelog-android-diff.md`

| Изменение | Тип | Описание |
|---|---|---|
| Связь с GenerationPresets | NEW | Стратегии PromptTemplates комбинируются с форматом SystemPrompts через пресеты (день 2). Это паттерн из Android `GenerationPresets.kt` |

### Связь с GenerationPresets

`GenerationPresets` (из дня 2) объединяет формат ответа и стратегию рассуждения:

```kotlin
// Пользователь выбирает пресет → получает и формат, и стратегию
GenerationPreset.EXPERTS.toSystemMessage()      // → PromptTemplates.EXPERT_GROUP промпт
GenerationPreset.EXPERTS.toReasoningStrategy()  // → ReasoningStrategy.EXPERT_GROUP
```

> В Android пресеты реализованы как `GenerationPresets` с 4 вариантами:
> Standard, Concise, Prompted, Experts — каждый задаёт и system prompt, и параметры генерации.
> В CLI пресеты — удобная обёртка, но PromptTemplates остаётся основной абстракцией.
