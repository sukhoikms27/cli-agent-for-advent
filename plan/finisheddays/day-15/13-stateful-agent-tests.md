# Задача 13. Тесты `StatefulAgent` (Этап B)

## Цель
Покрыть `StatefulAgent` юнит-тестами: делегирование `chat`/`getHistory`/`reset`, опц. инварианты
(через `InvariantGuard`), expose `contextAware`. По стилю `InvariantGuardTest` (mock base).

## Зависимости
12 (`StatefulAgent`). Образец: `src/test/kotlin/com/cliagent/agent/InvariantGuardTest.kt`.

## Тест-класс (новый)
`src/test/kotlin/com/cliagent/agent/StatefulAgentTest.kt`

## Что реализовать

```kotlin
package com.cliagent.agent

import com.cliagent.llm.model.ChatMessage
import com.cliagent.state.invariant.Invariant
import com.cliagent.state.invariant.InvariantCategory
import com.cliagent.state.invariant.InvariantChecker
import com.cliagent.state.invariant.InvariantResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatefulAgentTest {

    private val compose = Invariant("no-compose", "no Compose", InvariantCategory.BAN)

    /** Mock базового ContextAwareAgent для делегирования. */
    private fun baseMock(vararg replies: String): ContextAwareAgent = mockk {
        coEvery { chat(any()) } returnsMany listOf(*replies)
        coEvery { getHistory() } returns emptyList()
        coEvery { reset() } returns Unit
    }

    private fun checker(request: InvariantResult, response: InvariantResult): InvariantChecker = mockk {
        coEvery { checkRequest(any(), any()) } returns request
        coEvery { checkResponse(any(), any()) } returns response
    }

    // ── Без checker (прозрачный делегат) ──

    @Test
    fun `without checker chat delegates directly to base`() = runTest {
        val base = baseMock("ответ")
        val agent = StatefulAgent(base, checker = null) { emptyList() }
        assertEquals("ответ", agent.chat("вопрос"))
        coVerify(exactly = 1) { base.chat("вопрос") }
    }

    @Test
    fun `without checker getHistory delegates to base`() = runTest {
        val base = mockk<ContextAwareAgent> {
            coEvery { getHistory() } returns listOf(ChatMessage(role = "user", content = "x"))
            coEvery { reset() } returns Unit
        }
        val agent = StatefulAgent(base, checker = null) { emptyList() }
        assertEquals(1, agent.getHistory().size)
    }

    @Test
    fun `reset delegates to base`() = runTest {
        val base = baseMock()
        val agent = StatefulAgent(base, checker = null) { emptyList() }
        agent.reset()
        coVerify { base.reset() }
    }

    // ── С checker (инварианты через InvariantGuard) ──

    @Test
    fun `with checker empty invariants fast-path delegates`() = runTest {
        val base = baseMock("ответ")
        val checker = mockk<InvariantChecker> {
            coEvery { checkRequest(any(), any()) } throws IllegalStateException("не звать при пустых")
            coEvery { checkResponse(any(), any()) } throws IllegalStateException("не звать")
        }
        val agent = StatefulAgent(base, checker) { emptyList() }
        assertEquals("ответ", agent.chat("вопрос"))
        coVerify(exactly = 1) { base.chat("вопрос") }
    }

    @Test
    fun `with checker request violation refuses without base chat`() = runTest {
        val base = baseMock("никогда")
        val checker = checker(
            request = InvariantResult.Violated("no-compose", "no Compose", "запрос просит Compose"),
            response = InvariantResult.Valid
        )
        val agent = StatefulAgent(base, checker) { listOf(compose) }
        val result = agent.chat("напиши на Compose")
        assertTrue(result.contains("⛔"))
        assertTrue(result.contains("no Compose"))
        coVerify(exactly = 0) { base.chat(any()) }   // отказ без LLM
    }

    @Test
    fun `with checker response valid returns answer`() = runTest {
        val base = baseMock("хороший ответ")
        val checker = checker(InvariantResult.Valid, InvariantResult.Valid)
        val agent = StatefulAgent(base, checker) { listOf(compose) }
        assertEquals("хороший ответ", agent.chat("вопрос"))
        coVerify(exactly = 1) { base.chat(any()) }
    }

    @Test
    fun `invariantsProvider is called to fetch invariants`() = runTest {
        val base = baseMock("ответ")
        val checker = checker(InvariantResult.Valid, InvariantResult.Valid)
        var providerCalled = false
        val agent = StatefulAgent(base, checker) {
            providerCalled = true
            listOf(compose)
        }
        agent.chat("вопрос")
        assertTrue(providerCalled, "invariantsProvider должен вызываться")
    }

    // ── contextAware expose ──

    @Test
    fun `contextAware exposes base for accessors`() {
        val base = baseMock()
        val agent = StatefulAgent(base, checker = null) { emptyList() }
        assertTrue(agent.contextAware === base,
            "contextAware должен возвращать тот же экземпляр base")
    }

    @Test
    fun `contextAware allows accessing state accessors`() = runTest {
        // демонстрирует, что через contextAware доступны аксессоры ContextAwareAgent
        val base = mockk<ContextAwareAgent>(relaxed = true) {
            coEvery { getTaskState() } returns null
        }
        val agent = StatefulAgent(base, checker = null) { emptyList() }
        assertEquals(null, agent.contextAware.getTaskState())
    }
}
```

## Что проверяет
- **Без checker** — прозрачное делегирование `chat`/`getHistory`/`reset` (текущее поведение без
  `--invariants`).
- **С checker** — инварианты через `InvariantGuard`: fast-path при пустых, отказ без LLM при
  Violated-запросе, ответ при Valid.
- **`invariantsProvider` вызывается** — провайдер интегрирован в guard.
- **`contextAware` expose** — возвращает тот же `base`, аксессоры доступны.

## Критерии готовности
- `./gradlew test --tests "*StatefulAgentTest"` зелёный.
- ~9 тестов покрывают: делегирование (3), инварианты (4), expose (2).
- Существующие тесты `InvariantGuardTest` (9 шт.) и `ContextAwareAgentTest`/`*InvariantsTest`
  остаются зелёными (не модифицированы).

## Зависимости (задачи)
12. Wiring в 15.
