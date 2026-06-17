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
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextAwareAgentMemoryInjectionTest {

    private fun fakeResponse(): ChatResponse = ChatResponse(
        id = "resp-1",
        choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = "ok"))),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
    )

    private fun memoryStoreMock(
        history: List<ChatMessage> = emptyList(),
        working: WorkingMemory? = null,
        longTerm: LongTermMemory = LongTermMemory()
    ): MemoryStore = mockk {
        coEvery { loadHistory(any()) } returns history
        coEvery { loadWorkingMemory(any()) } returns working
        coEvery { loadLongTermMemory() } returns longTerm
        coEvery { loadSummary(any()) } returns null
        coEvery { saveMessage(any(), any()) } returns Unit
    }

    @Test
    fun `empty memory produces base system prompt`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())

        val agent = ContextAwareAgent(
            llmClient = llm,
            memoryStore = store,
            model = "test-model",
            chatId = "chat-1"
        )
        agent.chat("hi")

        val systemContent = requestSlot.captured.messages.first().content
        // Без памяти — только базовый промпт, без блоков слоёв
        assertFalse(systemContent.contains("[Long-term memory]"))
        assertFalse(systemContent.contains("[Working memory"))
        assertTrue(systemContent.contains("helpful"))
    }

    @Test
    fun `non-empty working and long-term memory injected into system prompt`() = runTest {
        val store = memoryStoreMock(
            working = WorkingMemory(currentTask = "auth service", plan = "1) routes"),
            longTerm = LongTermMemory(knowledge = mapOf("stack" to "Kotlin"))
        )
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())

        val agent = ContextAwareAgent(
            llmClient = llm,
            memoryStore = store,
            model = "test-model",
            chatId = "chat-1"
        )
        agent.chat("what next?")

        val systemContent = requestSlot.captured.messages.first().content
        assertTrue(systemContent.contains("[Long-term memory]"))
        assertTrue(systemContent.contains("Kotlin"))
        assertTrue(systemContent.contains("[Working memory — current task]"))
        assertTrue(systemContent.contains("auth service"))
    }
}
