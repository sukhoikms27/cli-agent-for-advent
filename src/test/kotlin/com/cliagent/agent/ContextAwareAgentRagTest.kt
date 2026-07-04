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

    // ── День 24: анти-галлюцинации (canned «не знаю» + пост-чек цитирования) ──────────

    @Test
    fun `weak context below threshold returns canned response without LLM call — день 24`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val retriever = mockk<RagRetriever>()
        // Retrieve дал чанк, но с низким сходством (0.2 < threshold 0.4).
        coEvery { retriever.retrieve(any()) } returns listOf(ragChunk("irrelevant text", "x.md").copy(score = 0.2f))

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
            dontKnowThreshold = 0.4f,
        )
        val answer = agent.chat("что-то совсем нерелевантное")

        // LLM НЕ вызывался — canned-response без обращения к модели.
        coVerify(exactly = 0) { llm.chat(any()) }
        assertTrue(answer.contains("не знаю"), "canned должен содержать «не знаю»")
        // score/threshold рендерятся через %.2f — Locale-dependent (0.20 или 0,20). Толерантно.
        assertTrue(answer.contains("0.20") || answer.contains("0,20"), "canned должен показать max similarity")
        assertTrue(answer.contains("0.40") || answer.contains("0,40"), "canned должен показать threshold")
    }

    @Test
    fun `empty retrieved list below threshold returns canned — день 24`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val retriever = mockk<RagRetriever>()
        coEvery { retriever.retrieve(any()) } returns emptyList()   // 0 чанков совпали

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
            dontKnowThreshold = 0.3f,
        )
        val answer = agent.chat("непонятный запрос")
        coVerify(exactly = 0) { llm.chat(any()) }
        assertTrue(answer.contains("не знаю"))
    }

    @Test
    fun `strong context above threshold calls LLM normally — день 24`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } returns LlmResult.Success(fakeResponse())
        val retriever = mockk<RagRetriever>()
        // Сходство 0.8 ≥ threshold 0.4 → LLM вызывается (backward-compat с днём 23).
        coEvery { retriever.retrieve(any()) } returns listOf(ragChunk("relevant text", "a.md").copy(score = 0.8f))

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
            dontKnowThreshold = 0.4f,
        )
        agent.chat("релевантный вопрос")
        coVerify(exactly = 1) { llm.chat(any()) }
    }

    @Test
    fun `threshold zero disables dont-know mode — backward compat with day 23`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } returns LlmResult.Success(fakeResponse())
        val retriever = mockk<RagRetriever>()
        // Низкое сходство (0.1), но threshold=0.0 → режим выключен, LLM зовётся (день 23).
        coEvery { retriever.retrieve(any()) } returns listOf(ragChunk("weak text", "a.md").copy(score = 0.1f))

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
            dontKnowThreshold = 0.0f,   // выключено
        )
        agent.chat("вопрос")
        coVerify(exactly = 1) { llm.chat(any()) }
    }

    @Test
    fun `retrieve null with positive threshold does not trigger canned — day 22 graceful degradation`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } returns LlmResult.Success(fakeResponse())
        val retriever = mockk<RagRetriever>()
        // retrieve()=null → Ollama down/пустой индекс → мягкая деградация дня 22 (НЕ canned «не знаю»).
        coEvery { retriever.retrieve(any()) } returns null

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
            dontKnowThreshold = 0.5f,
        )
        agent.chat("вопрос")
        // LLM вызывается — это мягкая деградация (без RAG-блока), а не отказ.
        coVerify(exactly = 1) { llm.chat(any()) }
    }

    @Test
    fun `canned response is persisted in history — день 24`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val retriever = mockk<RagRetriever>()
        coEvery { retriever.retrieve(any()) } returns emptyList()

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
            dontKnowThreshold = 0.3f,
        )
        agent.chat("непонятный вопрос")

        val history = agent.getHistory()
        // user + canned assistant — последний ответ сохранён как обычный assistant message.
        assertTrue(history.any { it.role == "assistant" && it.content.contains("не знаю") })
    }
}
