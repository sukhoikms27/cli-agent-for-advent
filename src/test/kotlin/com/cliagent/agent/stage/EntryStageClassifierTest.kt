package com.cliagent.agent.stage

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import com.cliagent.state.TaskStage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EntryStageClassifierTest {

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
    fun `returns CLARIFY when model says CLARIFY`() = runTest {
        val clf = EntryStageClassifier(llm("CLARIFY"), "m")
        assertEquals(TaskStage.CLARIFY, clf.classify("сделай калькулятор"))
    }

    @Test
    fun `returns PLANNING when model says PLANNING`() = runTest {
        val clf = EntryStageClassifier(llm("PLANNING"), "m")
        assertEquals(TaskStage.PLANNING, clf.classify("детальное описание"))
    }

    @Test
    fun `case insensitive and tolerant to surrounding text`() = runTest {
        val clf = EntryStageClassifier(llm("I think clarify is best here."), "m")
        assertEquals(TaskStage.CLARIFY, clf.classify("desc"))
    }

    @Test
    fun `garbage response falls back to PLANNING`() = runTest {
        val clf = EntryStageClassifier(llm("бананы"), "m")
        assertEquals(TaskStage.PLANNING, clf.classify("desc"))
    }

    @Test
    fun `LLM error falls back to PLANNING`() = runTest {
        val clf = EntryStageClassifier(errLlm(), "m")
        assertEquals(TaskStage.PLANNING, clf.classify("desc"))
    }

    @Test
    fun `blank description returns PLANNING without LLM call`() = runTest {
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } throws IllegalStateException("should not be called")
        val clf = EntryStageClassifier(llm, "m")
        assertEquals(TaskStage.PLANNING, clf.classify("   "))
    }

    @Test
    fun `CLARIFY takes precedence when both words present`() = runTest {
        // ответ упоминает оба — выбираем CLARIFY (безопаснее уточнить)
        val clf = EntryStageClassifier(llm("CLARIFY not PLANNING"), "m")
        assertEquals(TaskStage.CLARIFY, clf.classify("desc"))
    }
}
