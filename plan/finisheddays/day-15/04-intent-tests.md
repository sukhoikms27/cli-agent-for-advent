# Задача 04. Тесты `IntentClassifier` (п.1)

## Цель
Покрыть `IntentClassifier.classify` юнит-тестами по образцу `EntryStageClassifierTest`: mock LLM,
проверка QUESTION/TASK, fallback на ошибку/мусор, blank без LLM-вызова.

## Зависимости
02 (`IntentClassifier`). Образец теста: `src/test/kotlin/com/cliagent/agent/stage/EntryStageClassifierTest.kt`.

## Тест-класс (новый)
`src/test/kotlin/com/cliagent/agent/stage/IntentClassifierTest.kt`

## Что реализовать

```kotlin
package com.cliagent.agent.stage

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntentClassifierTest {

    private fun llm(content: String): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Success(
            ChatResponse(
                id = "r",
                choices = listOf(Choice(0, ChatMessage(role = "assistant", content = content))),
                usage = Usage(1, 1, 2)
            )
        )
    }

    private fun errLlm(): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Error(500, "boom")
    }

    @Test
    fun `returns TASK when model says TASK`() = runTest {
        val clf = IntentClassifier(llm("TASK"), "m")
        assertEquals(UserIntent.TASK, clf.classify("напиши REST API на Kotlin"))
    }

    @Test
    fun `returns QUESTION when model says QUESTION`() = runTest {
        val clf = IntentClassifier(llm("QUESTION"), "m")
        assertEquals(UserIntent.QUESTION, clf.classify("что такое MVI?"))
    }

    @Test
    fun `case insensitive and tolerant to surrounding text`() = runTest {
        val clf = IntentClassifier(llm("I believe this is a task."), "m")
        assertEquals(UserIntent.TASK, clf.classify("desc"))
    }

    @Test
    fun `garbage response falls back to QUESTION`() = runTest {
        val clf = IntentClassifier(llm("бананы"), "m")
        assertEquals(UserIntent.QUESTION, clf.classify("desc"))
    }

    @Test
    fun `LLM error falls back to QUESTION`() = runTest {
        val clf = IntentClassifier(errLlm(), "m")
        assertEquals(UserIntent.QUESTION, clf.classify("desc"))
    }

    @Test
    fun `blank message returns QUESTION without LLM call`() = runTest {
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } throws IllegalStateException("should not be called")
        val clf = IntentClassifier(llm, "m")
        assertEquals(UserIntent.QUESTION, clf.classify("   "))
    }

    @Test
    fun `both words present prefers TASK`() = runTest {
        // ответ упоминает оба — TASK проверяется первым в when
        val clf = IntentClassifier(llm("this is a task not a question"), "m")
        assertEquals(UserIntent.TASK, clf.classify("desc"))
    }

    @Test
    fun `temperature is zero in request`() = runTest {
        // детерминированность классификации (как EntryStageClassifier)
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } returns LlmResult.Success(
            ChatResponse(
                id = "r",
                choices = listOf(Choice(0, ChatMessage(role = "assistant", content = "QUESTION"))),
                usage = Usage(1, 1, 2)
            )
        )
        IntentClassifier(llm, "m").classify("hi")
        coVerify {
            val req = captureSlot<ChatRequest>()
            llm.chat(req.captured)
            assertEquals(0.0, req.captured.temperature)
        }
    }
}
```

> Примечание: для теста `temperature is zero` можно использовать `slot<ChatRequest>()` из mockk +
> `coVerify`. Если в проекте уже есть утилита для такого перехвата (`ProfileExtractorTest`), держаться
> её стиля.

## Что проверяет
- QUESTION/TASK — прямой маппинг ответа модели.
- Case-insensitive / толерантность к окружающему тексту (как в образце).
- Мусор → QUESTION (fallback, безопаснее не заводить задачу).
- LLM-ошибка → QUESTION (не блокируем пользователя при сбое).
- Blank → QUESTION без LLM-вызова (экономия токенов + нет ложной задачи на пустой ввод).
- Оба слова → TASK (порядок в `when`).
- temperature=0 — детерминированность.

## Граничные кейсы (таблица)

| # | Ввод модели | Ввод пользователя | Ожидание |
|---|---|---|---|
| 1 | `"TASK"` | любое | `TASK` |
| 2 | `"QUESTION"` | любое | `QUESTION` |
| 3 | `"this is a task."` | любое | `TASK` (tolerant) |
| 4 | `"бананы"` | любое | `QUESTION` (garbage fallback) |
| 5 | (error 500) | любое | `QUESTION` (error fallback) |
| 6 | (not called) | `"   "` | `QUESTION` (blank, no LLM) |
| 7 | `"task not question"` | любое | `TASK` (оба слова, TASK first) |

## Критерии готовности
- `./gradlew test --tests "*IntentClassifierTest"` зелёный.
- Покрытие: 7–8 тестов, включая fallback и blank.
