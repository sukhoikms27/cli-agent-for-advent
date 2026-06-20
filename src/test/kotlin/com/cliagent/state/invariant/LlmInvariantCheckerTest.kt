package com.cliagent.state.invariant

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmInvariantCheckerTest {

    private val invariants = listOf(
        Invariant("no-compose", "UI только View-based, запрещён Compose", InvariantCategory.BAN),
        Invariant("kotlin-only", "Код только на Kotlin", InvariantCategory.STACK)
    )

    private fun fakeResponse(content: String): ChatResponse = ChatResponse(
        id = "r",
        choices = listOf(Choice(0, ChatMessage(role = "assistant", content = content))),
        usage = Usage(1, 1, 2)
    )

    private fun llm(content: String): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Success(fakeResponse(content))
    }

    private fun errLlm(): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Error(500, "boom")
    }

    @Test
    fun `violated JSON yields Violated with rule and explanation`() = runTest {
        val checker = LlmInvariantChecker(
            llm("""{"violated":true,"ruleId":"no-compose","explanation":"содержит setContent{}"}"""), "m"
        )
        val result = checker.checkResponse("экран на Compose", invariants)
        assertTrue(result is InvariantResult.Violated)
        result as InvariantResult.Violated
        assertEquals("no-compose", result.ruleId)
        assertEquals("UI только View-based, запрещён Compose", result.rule)
        assertEquals("содержит setContent{}", result.explanation)
    }

    @Test
    fun `violated false yields Valid`() = runTest {
        val checker = LlmInvariantChecker(
            llm("""{"violated":false,"ruleId":"","explanation":""}"""), "m"
        )
        assertEquals(InvariantResult.Valid, checker.checkResponse("нормальный ответ", invariants))
    }

    @Test
    fun `LLM error falls back to Valid`() = runTest {
        val checker = LlmInvariantChecker(errLlm(), "m")
        assertEquals(InvariantResult.Valid, checker.checkResponse("что угодно", invariants))
    }

    @Test
    fun `garbage non-JSON falls back to Valid`() = runTest {
        val checker = LlmInvariantChecker(llm("бананы и апельсины"), "m")
        assertEquals(InvariantResult.Valid, checker.checkResponse("x", invariants))
    }

    @Test
    fun `markdown-wrapped JSON is parsed`() = runTest {
        val checker = LlmInvariantChecker(
            llm("```json\n{\"violated\":true,\"ruleId\":\"kotlin-only\",\"explanation\":\"Java-класс\"}\n```"), "m"
        )
        val result = checker.checkResponse("x", invariants)
        assertTrue(result is InvariantResult.Violated)
        assertEquals("kotlin-only", (result as InvariantResult.Violated).ruleId)
    }

    @Test
    fun `empty invariants returns Valid without LLM call`() = runTest {
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } throws IllegalStateException("should not be called")
        val checker = LlmInvariantChecker(llm, "m")
        assertEquals(InvariantResult.Valid, checker.checkResponse("x", emptyList()))
        coVerify(exactly = 0) { llm.chat(any()) }
    }

    @Test
    fun `temperature is zero for determinism`() = runTest {
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(
            fakeResponse("""{"violated":false}""")
        )
        val checker = LlmInvariantChecker(llm, "m")
        checker.checkResponse("x", invariants)
        assertEquals(0.0, requestSlot.captured.temperature)
    }

    @Test
    fun `checkRequest and checkResponse use different judge wording`() = runTest {
        val llm = mockk<LlmClient>()
        val reqSlot = mutableListOf<ChatRequest>()
        coEvery { llm.chat(capture(reqSlot)) } returns LlmResult.Success(
            fakeResponse("""{"violated":false}""")
        )
        val checker = LlmInvariantChecker(llm, "m")
        checker.checkRequest("мигрируй на Compose", invariants)
        checker.checkResponse("вот код на Compose", invariants)

        val reqPrompt = reqSlot[0].messages.first().content
        val respPrompt = reqSlot[1].messages.first().content
        assertTrue(reqPrompt.contains("запрос пользователя"), "request prompt should mention запрос")
        assertTrue(respPrompt.contains("ответ ассистента"), "response prompt should mention ответ")
    }

    @Test
    fun `unknown ruleId falls back to first invariant rule`() = runTest {
        val checker = LlmInvariantChecker(
            llm("""{"violated":true,"ruleId":"zzz-not-in-list","explanation":"почему"}"""), "m"
        )
        val result = checker.checkResponse("x", invariants) as InvariantResult.Violated
        // ruleId сохранён из ответа, rule — из первого инварианта (best-effort)
        assertEquals("zzz-not-in-list", result.ruleId)
        assertEquals(invariants.first().rule, result.rule)
    }

    @Test
    fun `blank explanation falls back to rule text`() = runTest {
        val checker = LlmInvariantChecker(
            llm("""{"violated":true,"ruleId":"no-compose","explanation":""}"""), "m"
        )
        val result = checker.checkResponse("x", invariants) as InvariantResult.Violated
        assertTrue(result.explanation.contains("no-compose"))
    }
}
