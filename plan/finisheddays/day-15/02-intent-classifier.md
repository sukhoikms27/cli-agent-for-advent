# Задача 02. `IntentClassifier` — авто-определение «вопрос vs задача» (п.1)

## Цель
Агент сам решает, что пришло от пользователя: **простой вопрос** (ответить обычным чатом) или
**полноценная задача** (запустить жизненный цикл FSM). Делает это без ручного `/task start`.

## Зависимости
Нет новых. Образцы паттерна в проекте: `agent/stage/EntryStageClassifier.kt` (LLM-классификация
стадии), `agent/ProfileExtractor.kt` (LLM-извлечение, temp=0, fallback «keep as-is»).

## Файл (новый)
`src/main/kotlin/com/cliagent/agent/stage/IntentClassifier.kt`

## Что реализовать

```kotlin
package com.cliagent.agent.stage

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest

/**
 * Результат классификации интента входного сообщения пользователя (день 15, доп. п.1).
 *
 * - [QUESTION] — простой вопрос/просьба: ответить обычным чатом, без заведения задачи.
 * - [TASK]     — полноценная задача: запустить жизненный цикл (clarify→plan→execute→validate→done).
 */
enum class UserIntent { QUESTION, TASK }

/**
 * Авто-определение интента: QUESTION или TASK (день 15, доп. п.1).
 *
 * Один LLM-запрос (temperature 0, паттерн [EntryStageClassifier]/[com.cliagent.agent.ProfileExtractor]):
 * является ли сообщение пользователя разовым вопросом/просьбой или полноценной задачей, требующей
 * жизненного цикла (уточнение → план → реализация → валидация).
 *
 * Ответ — строго `QUESTION` или `TASK`. На ошибку/мусор LLM → fallback **QUESTION** (безопасно:
 * при сомнении НЕ заводить задачу, а ответить в чате — пользователь не застрянет в FSM).
 *
 * Используется в `ChatCommand` else-ветке при `taskState == null` и режиме ≠ MANUAL:
 * QUESTION → обычный чат; TASK → `orchestrator.startTask(input)` (автостарт).
 */
class IntentClassifier(
    private val llmClient: LlmClient,
    private val model: String
) {
    suspend fun classify(userMessage: String): UserIntent {
        if (userMessage.isBlank()) return UserIntent.QUESTION
        val prompt = """
            Определи, является ли сообщение пользователя простым вопросом/просьбой (QUESTION)
            или полноценной задачей, требующей жизненного цикла «уточнение → план → реализация →
            валидация» (TASK).

            Признаки QUESTION: короткий фактологический вопрос, разовое уточнение, просьба объяснить
            концепцию, мета-вопрос о работе инструмента, приветствие/болтовня. Ответ можно дать
            сразу, без плана и нескольких шагов.
            Признаки TASK: запрос реализовать/спроектировать/создать/доработать что-то конкретное,
            многократные шаги, нужна декомпозиция, есть подзадачи.

            Сообщение пользователя:
            ${userMessage.take(MAX_MSG_CHARS)}

            Ответь строго одним словом: QUESTION или TASK.
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            temperature = 0.0
        )
        return when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> {
                val text = result.data.choices.firstOrNull()?.message?.content.orEmpty().uppercase()
                when {
                    text.contains("TASK") -> UserIntent.TASK
                    text.contains("QUESTION") -> UserIntent.QUESTION
                    else -> UserIntent.QUESTION   // мусор → безопасный fallback
                }
            }
            is LlmResult.Error -> UserIntent.QUESTION
        }
    }

    private companion object {
        const val MAX_MSG_CHARS = 2000
    }
}
```

## Ключевые инварианты
- **Один LLM-вызов**, temperature 0 — детерминированность, как у `EntryStageClassifier`.
- **`TASK` проверяется первым** в `when` — если в ответе есть оба слова (маловероятно), TASK не
  «перебивает» QUESTION случайно (в отличие от CLARIFY-приоритета в EntryStageClassifier — там
  безопаснее уточнить; здесь безопаснее НЕ заводить задачу, поэтому default=QUESTION в else-ветке).
- **Fallback QUESTION** на ошибку/мусор LLM — пользователь не застрянет в FSM при сбое
  классификации; задача всегда доступна через явный `/task start`.
- **Blank → QUESTION без LLM-вызова** — экономия токенов, как `EntryStageClassifier.classify(" ")`.
- **`MAX_MSG_CHARS`** — защита от гигантских сообщений, как `MAX_DESC_CHARS` в образце.

## Решения
- **LLM, не эвристика.** Согласовано с пользователем: LLM-классификатор ловит семантику («помоги
  разобраться с MVI» = QUESTION), а не только ключевые слова. Эвристика дала бы много false-positive
  TASK на «как реализовать X?» (это вопрос, не задача).
- **QUESTION как fallback, не TASK** — асимметрия рисков: ложный TASK втягивает пользователя в
  многостадийный FSM против воли; ложный QUESTION просто даёт быстрый ответ, а задача остаётся
  доступной через `/task start`.
- **Класс в `agent/stage/`** — рядом с `EntryStageClassifier`, та же зона ответственности
  («классификация перед стадийным потоком»).

## Критерии готовности
- `IntentClassifier.classify` возвращает `UserIntent.QUESTION`/`TASK`.
- Один LLM-вызов на классификацию, temperature=0.
- Fallback QUESTION на LLM-ошибку и мусор.
- Blank-сообщение → QUESTION без LLM-вызова.

## Зависимости (задачи)
Используется в 03 (роутинг в `ChatCommand`). Тестируется в 04.
