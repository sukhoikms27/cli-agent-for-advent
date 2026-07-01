package com.cliagent.agent.stage

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import com.cliagent.state.TaskComplexity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaskComplexityClassifierTest {

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
    fun `returns TRIVIAL when model says TRIVIAL`() = runTest {
        val clf = TaskComplexityClassifier(llm("TRIVIAL"), "m")
        assertEquals(TaskComplexity.TRIVIAL, clf.classify("объясни SOLID"))
    }

    @Test
    fun `returns MODERATE when model says MODERATE`() = runTest {
        val clf = TaskComplexityClassifier(llm("MODERATE"), "m")
        assertEquals(TaskComplexity.MODERATE, clf.classify("реализуй функцию"))
    }

    @Test
    fun `returns COMPLEX when model says COMPLEX`() = runTest {
        val clf = TaskComplexityClassifier(llm("COMPLEX"), "m")
        assertEquals(TaskComplexity.COMPLEX, clf.classify("спроектируй модульную систему"))
    }

    @Test
    fun `case insensitive and tolerant to surrounding text`() = runTest {
        val clf = TaskComplexityClassifier(llm("I'd say complex."), "m")
        assertEquals(TaskComplexity.COMPLEX, clf.classify("desc"))
    }

    @Test
    fun `garbage response falls back to MODERATE`() = runTest {
        val clf = TaskComplexityClassifier(llm("бананы"), "m")
        assertEquals(TaskComplexity.MODERATE, clf.classify("desc"))
    }

    @Test
    fun `LLM error falls back to MODERATE`() = runTest {
        val clf = TaskComplexityClassifier(errLlm(), "m")
        assertEquals(TaskComplexity.MODERATE, clf.classify("desc"))
    }

    @Test
    fun `blank description returns MODERATE without LLM call`() = runTest {
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } throws IllegalStateException("should not be called")
        val clf = TaskComplexityClassifier(llm, "m")
        assertEquals(TaskComplexity.MODERATE, clf.classify("   "))
    }
}
