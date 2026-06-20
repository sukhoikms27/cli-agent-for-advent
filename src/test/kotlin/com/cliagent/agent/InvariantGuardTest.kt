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

class InvariantGuardTest {

    private val compose = Invariant("no-compose", "no Compose", InvariantCategory.BAN)

    private fun delegated(vararg replies: String): Agent = mockk {
        coEvery { chat(any()) } returnsMany listOf(*replies)
        coEvery { getHistory() } returns emptyList()
        coEvery { reset() } returns Unit
    }

    private fun checker(request: InvariantResult, response: InvariantResult): InvariantChecker = mockk {
        coEvery { checkRequest(any(), any()) } returns request
        coEvery { checkResponse(any(), any()) } returns response
    }

    /** Guard с одним инвариантом (compose). */
    private fun guard(delegated: Agent, checker: InvariantChecker) =
        InvariantGuard(delegated, checker) { listOf(compose) }

    @Test
    fun `empty invariants fast-path skips checker and delegates directly`() = runTest {
        val delegated = delegated("ответ")
        val checker = mockk<InvariantChecker>()
        coEvery { checker.checkRequest(any(), any()) } throws IllegalStateException("checker не должен зваться при пустых инвариантах")
        coEvery { checker.checkResponse(any(), any()) } throws IllegalStateException("checker не должен зваться")
        val guard = InvariantGuard(delegated, checker) { emptyList() }

        assertEquals("ответ", guard.chat("вопрос"))
        coVerify(exactly = 1) { delegated.chat("вопрос") }   // chat делегата вызван
    }

    @Test
    fun `request violation refuses without delegated chat`() = runTest {
        val delegated = delegated("никогда не вернусь")
        val checker = checker(
            request = InvariantResult.Violated("no-compose", "no Compose", "запрос просит Compose"),
            response = InvariantResult.Valid
        )
        val guard = guard(delegated, checker)

        val result = guard.chat("напиши на Compose")
        assertTrue(result.contains("⛔"))
        assertTrue(result.contains("no Compose"))
        assertTrue(result.contains("запрос просит Compose"))
        coVerify(exactly = 0) { delegated.chat(any()) }   // отказ без LLM
    }

    @Test
    fun `request valid and response valid returns delegated answer in one call`() = runTest {
        val delegated = delegated("хороший ответ")
        val checker = checker(InvariantResult.Valid, InvariantResult.Valid)
        val guard = guard(delegated, checker)

        assertEquals("хороший ответ", guard.chat("вопрос"))
        coVerify(exactly = 1) { delegated.chat(any()) }
    }

    @Test
    fun `response always violated exhausts retries and returns fallback`() = runTest {
        // initial + 3 retry = 4 вызова delegated.chat
        val delegated = delegated("плохо1", "плохо2", "плохо3", "плохо4")
        val checker = checker(InvariantResult.Valid, InvariantResult.Violated("no-compose", "no Compose", "всё ещё Compose"))
        val guard = guard(delegated, checker)

        val result = guard.chat("вопрос")
        assertTrue(result.contains("⚠️"))
        assertTrue(result.contains("3"))
        assertTrue(result.contains("плохо4"))   // последний ответ в fallback
        coVerify(exactly = 4) { delegated.chat(any()) }   // 1 initial + 3 retry
    }

    @Test
    fun `response violated then valid returns second answer in two calls`() = runTest {
        val delegated = delegated("плохо", "хорошо")
        // checkResponse: первый раз Violated, второй Valid
        val checker = mockk<InvariantChecker> {
            coEvery { checkRequest(any(), any()) } returns InvariantResult.Valid
            coEvery { checkResponse(any(), any()) } returnsMany
                listOf(InvariantResult.Violated("no-compose", "no Compose", "Compose"), InvariantResult.Valid)
        }
        val guard = guard(delegated, checker)

        assertEquals("хорошо", guard.chat("вопрос"))
        coVerify(exactly = 2) { delegated.chat(any()) }
    }

    @Test
    fun `feedback message includes original request`() = runTest {
        var capturedFeedback = ""
        val delegated = mockk<Agent> {
            coEvery { chat(any()) } answers {
                val msg = firstArg<String>()
                if (msg == "вопрос") {
                    capturedFeedback = ""   // первый вызов — не feedback
                    "плохо"
                } else {
                    capturedFeedback = msg  // второй — feedback-промпт
                    "хорошо"
                }
            }
            coEvery { getHistory() } returns emptyList()
            coEvery { reset() } returns Unit
        }
        val checker = mockk<InvariantChecker> {
            coEvery { checkRequest(any(), any()) } returns InvariantResult.Valid
            coEvery { checkResponse(any(), any()) } returnsMany
                listOf(InvariantResult.Violated("no-compose", "no Compose", "x"), InvariantResult.Valid)
        }
        val guard = guard(delegated, checker)

        guard.chat("вопрос")
        assertTrue(capturedFeedback.contains("вопрос"))           // исходный запрос в feedback
        assertTrue(capturedFeedback.contains("no Compose"))       // rule в feedback
    }

    @Test
    fun `getHistory and reset delegate to wrapped agent`() = runTest {
        val delegated = mockk<Agent> {
            coEvery { getHistory() } returns listOf(ChatMessage(role = "user", content = "x"))
            coEvery { reset() } returns Unit
        }
        val guard = guard(delegated, checker(InvariantResult.Valid, InvariantResult.Valid))

        assertEquals(1, guard.getHistory().size)
        guard.reset()
        coVerify { delegated.getHistory() }
        coVerify { delegated.reset() }
    }

    @Test
    fun `MAX_RETRIES is 3`() = runTest {
        // косвенно: всегда-Violated даёт ровно 4 вызова (1 + 3). Если MAX_RETRIES поменяли —
        // этот тест провалится и заставит обновить документацию/константу.
        val delegated = delegated("a", "b", "c", "d")
        val checker = checker(InvariantResult.Valid, InvariantResult.Violated("no-compose", "r", "e"))
        val guard = guard(delegated, checker)
        guard.chat("q")
        coVerify(exactly = 4) { delegated.chat(any()) }
    }
}
