package com.cliagent.agent

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import com.cliagent.memory.WorkingMemory
import com.cliagent.state.TaskStage
import com.cliagent.state.TaskState
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextAwareAgentStagePromptTest {

    private fun fakeResponse(content: String = "ok"): ChatResponse = ChatResponse(
        id = "resp",
        choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = content))),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
    )

    private fun storeMock(working: WorkingMemory? = null): MemoryStore = mockk {
        coEvery { loadHistory(any()) } returns emptyList()
        coEvery { loadWorkingMemory(any()) } returns working
        coEvery { saveWorkingMemory(any(), any()) } returns Unit
        coEvery { loadLongTermMemory() } returns LongTermMemory()
        coEvery { loadSummary(any()) } returns null
        coEvery { saveMessage(any(), any()) } returns Unit
        coEvery { saveLongTermMemory(any()) } returns Unit
        coEvery { saveFacts(any(), any()) } returns Unit
        coEvery { loadFacts(any()) } returns emptyMap()
    }

    @Test
    fun `active task uses stage prompt instead of default base`() = runTest {
        val working = WorkingMemory(taskState = TaskState(stage = TaskStage.CLARIFY))
        val store = storeMock(working)
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())

        val agent = ContextAwareAgent(llm, store, "m", "chat-1")
        agent.chat("что мне учесть?")

        val systemContent = requestSlot.captured.messages.first().content
        // stage-prompt clarify содержит требование задавать вопросы и запрет на код
        assertTrue(systemContent.contains("CLARIFY") || systemContent.contains("clarif") || systemContent.contains("question"))
        // дефолтный base («You are a helpful AI assistant.») не должен быть единственным system-контентом
        assertFalse(systemContent == "You are a helpful AI assistant.")
    }

    @Test
    fun `different stages produce different stage prompts`() = runTest {
        suspend fun systemContentFor(stage: TaskStage): String {
            val store = storeMock(WorkingMemory(taskState = TaskState(stage = stage)))
            val llm = mockk<LlmClient>()
            val requestSlot = slot<ChatRequest>()
            coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())
            val agent = ContextAwareAgent(llm, store, "m", "chat-1")
            agent.chat("go")
            return requestSlot.captured.messages.first().content
        }

        val clarify = systemContentFor(TaskStage.CLARIFY)
        val execution = systemContentFor(TaskStage.EXECUTION)
        val validation = systemContentFor(TaskStage.VALIDATION)
        assertTrue(clarify != execution)
        assertTrue(execution != validation)
        assertTrue(clarify != validation)
    }

    @Test
    fun `no active task falls back to default base — day 13 invariant`() = runTest {
        // workingMemory null → taskState null → прежнее поведение (статический systemPrompt)
        val store = storeMock(null)
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())

        val agent = ContextAwareAgent(llm, store, "m", "chat-1")
        agent.chat("hi")

        val systemContent = requestSlot.captured.messages.first().content
        assertTrue(systemContent.contains("You are a helpful AI assistant."))
        // не содержит stage-маркеров
        assertFalse(systemContent.contains("CLARIFY stage") || systemContent.contains("EXECUTION stage"))
    }
}
