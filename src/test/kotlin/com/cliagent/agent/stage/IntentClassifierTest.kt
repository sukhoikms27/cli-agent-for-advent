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
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } returns LlmResult.Success(
            ChatResponse(
                id = "r",
                choices = listOf(Choice(0, ChatMessage(role = "assistant", content = "QUESTION"))),
                usage = Usage(1, 1, 2)
            )
        )
        IntentClassifier(llm, "m").classify("hi")
        val req = slot<ChatRequest>()
        coVerify { llm.chat(capture(req)) }
        assertEquals(0.0, req.captured.temperature)
    }
}
