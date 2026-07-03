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
import com.cliagent.rag.RagChunk
import com.cliagent.rag.RagRetriever
import com.cliagent.rag.ScoredChunk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 22: инъекция retrieved-контекста в [ContextAwareAgent]. Mock [RagRetriever] + mock [LlmClient] —
 * перехватываем отправленный [ChatRequest] и проверяем наличие блока `[Retrieved context]`.
 */
class ContextAwareAgentRagTest {

    private fun fakeResponse(): ChatResponse = ChatResponse(
        id = "resp-1",
        choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = "answer"))),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
    )

    private fun memoryStoreMock(): MemoryStore = mockk {
        coEvery { loadHistory(any()) } returns emptyList()
        coEvery { loadWorkingMemory(any()) } returns null
        coEvery { loadLongTermMemory() } returns LongTermMemory()
        coEvery { loadSummary(any()) } returns null
        coEvery { saveMessage(any(), any()) } returns Unit
    }

    private fun ragChunk(text: String, source: String): ScoredChunk = ScoredChunk(
        RagChunk(chunkId = "c1", documentId = "d", source = source, title = "T", section = "S", text = text, index = 0),
        0.9f,
    )

    @Test
    fun `rag disabled does not call retriever`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())
        val retriever = mockk<RagRetriever>()

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = false,
        )
        agent.chat("hi")

        coVerify(exactly = 0) { retriever.retrieve(any()) }
        assertFalse(requestSlot.captured.messages.first().content.contains("[Retrieved context"))
        assertFalse(agent.isRagEnabled())
    }

    @Test
    fun `rag enabled injects retrieved context into system prompt`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())
        val retriever = mockk<RagRetriever>()
        coEvery { retriever.retrieve(any()) } returns listOf(ragChunk("SlidingWindow keeps last N", "SlidingWindow.kt"))

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
        )
        agent.chat("how does sliding window work?")

        coVerify(exactly = 1) { retriever.retrieve(any()) }
        val systemContent = requestSlot.captured.messages.first().content
        assertTrue(systemContent.contains("[Retrieved context"))
        assertTrue(systemContent.contains("SlidingWindow keeps last N"))
        assertTrue(systemContent.contains("SlidingWindow.kt"))
        assertTrue(agent.isRagEnabled())
    }

    @Test
    fun `rag enabled but retriever returns null degrades gracefully without block`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())
        val retriever = mockk<RagRetriever>()
        coEvery { retriever.retrieve(any()) } returns null   // Ollama down / empty index

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
        )
        agent.chat("hi")

        // retriever был вызван, но вернул null → блока нет, ответ идёт без RAG (мягкая деградация)
        assertFalse(requestSlot.captured.messages.first().content.contains("[Retrieved context"))
    }

    @Test
    fun `rag enabled but null retriever reports disabled and does not inject`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = null, ragEnabled = true,   // нет retriever'а → isRagEnabled = false
        )
        // isRagEnabled учитывает наличие retriever'а
        assertFalse(agent.isRagEnabled())
        agent.chat("hi")
        assertFalse(requestSlot.captured.messages.first().content.contains("[Retrieved context"))
    }

    @Test
    fun `setRagEnabled toggles runtime state`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } returns LlmResult.Success(fakeResponse())
        val retriever = mockk<RagRetriever>()

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = false,
        )
        assertFalse(agent.isRagEnabled())
        agent.setRagEnabled(true)
        assertTrue(agent.isRagEnabled())
        agent.setRagEnabled(false)
        assertFalse(agent.isRagEnabled())
    }
}
