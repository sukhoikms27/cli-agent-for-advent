package com.cliagent.agent.stage

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
import org.junit.jupiter.api.Test

class ConfirmationClassifierTest {

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
    fun `returns CONFIRM when model says CONFIRM`() = runTest {
        val clf = ConfirmationClassifier(llm("CONFIRM"), "m")
        assertEquals(ConfirmationIntent.CONFIRM, clf.classify("поехали"))
    }

    @Test
    fun `returns REFINE when model says REFINE`() = runTest {
        val clf = ConfirmationClassifier(llm("REFINE"), "m")
        assertEquals(ConfirmationIntent.REFINE, clf.classify("перепиши короче"))
    }

    @Test
    fun `returns AMBIGUOUS when model says AMBIGUOUS`() = runTest {
        val clf = ConfirmationClassifier(llm("AMBIGUOUS"), "m")
        assertEquals(ConfirmationIntent.AMBIGUOUS, clf.classify("хм"))
    }

    @Test
    fun `case insensitive and tolerant to surrounding text`() = runTest {
        val clf = ConfirmationClassifier(llm("The answer is Confirm."), "m")
        assertEquals(ConfirmationIntent.CONFIRM, clf.classify("давай"))
    }

    @Test
    fun `garbage response falls back to REFINE`() = runTest {
        // safe-bias: мусор → REFINE (не подтверждать переход)
        val clf = ConfirmationClassifier(llm("бананы"), "m")
        assertEquals(ConfirmationIntent.REFINE, clf.classify("что-то"))
    }

    @Test
    fun `LLM error falls back to REFINE`() = runTest {
        val clf = ConfirmationClassifier(errLlm(), "m")
        assertEquals(ConfirmationIntent.REFINE, clf.classify("давай"))
    }

    @Test
    fun `blank reply returns REFINE without LLM call`() = runTest {
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } throws IllegalStateException("should not be called")
        val clf = ConfirmationClassifier(llm, "m")
        assertEquals(ConfirmationIntent.REFINE, clf.classify("   "))
    }

    @Test
    fun `temperature is zero in request`() = runTest {
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } returns LlmResult.Success(
            ChatResponse(
                id = "r",
                choices = listOf(Choice(0, ChatMessage(role = "assistant", content = "CONFIRM"))),
                usage = Usage(1, 1, 2)
            )
        )
        ConfirmationClassifier(llm, "m").classify("да")
        val req = slot<ChatRequest>()
        coVerify { llm.chat(capture(req)) }
        assertEquals(0.0, req.captured.temperature)
    }
}
