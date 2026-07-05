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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 25: conversation-aware retrieval (GAP-B). Проверяет, что при `conversationalQuery=true` запрос
 * для `retrieve()` обогащается целью диалога + последними репликами, а при `false` (default) —
 * передаётся как есть (backward-compat с днём 24). Перехватываем аргумент `retrieve()` через slot.
 */
class ContextAwareAgentConversationalTest {

    private fun fakeResponse(): ChatResponse = ChatResponse(
        id = "resp-1",
        choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = "answer"))),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
    )

    private fun memoryStoreMock(): MemoryStore {
        // День 25: loadWorkingMemory должен возвращать последнее сохранённое (чтобы ensureLoaded()
        // в chat() не затирал цель, установленную через setWorkingMemory перед первым chat).
        val workingSlot = slot<WorkingMemory>()
        return mockk {
            coEvery { loadHistory(any()) } returns emptyList()
            coEvery { loadWorkingMemory(any()) } answers { if (workingSlot.isCaptured) workingSlot.captured else null }
            coEvery { loadLongTermMemory() } returns LongTermMemory()
            coEvery { loadSummary(any()) } returns null
            coEvery { saveMessage(any(), any()) } returns Unit
            coEvery { saveWorkingMemory(any(), capture(workingSlot)) } returns Unit
        }
    }

    private fun ragChunk(text: String, source: String): ScoredChunk = ScoredChunk(
        RagChunk(chunkId = "c1", documentId = "d", source = source, title = "T", section = "S", text = text, index = 0),
        0.9f,
    )

    @Test
    fun `conversationalQuery false default passes plain userMessage to retrieve — backward compat day 24`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } returns LlmResult.Success(fakeResponse())
        val retriever = mockk<RagRetriever>()
        val querySlot = slot<String>()
        coEvery { retriever.retrieve(capture(querySlot)) } returns listOf(ragChunk("text", "a.md"))

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
            conversationalQuery = false,   // default — день 24 поведение
        )
        agent.chat("какой размер батча?")

        // retrieve() получил ровно userMessage, без обогащения (backward-compat).
        assertEquals("какой размер батча?", querySlot.captured)
    }

    @Test
    fun `conversationalQuery true enriches query with currentTask and prior turns`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } returns LlmResult.Success(fakeResponse())
        val retriever = mockk<RagRetriever>()
        val querySlot = slot<String>()
        coEvery { retriever.retrieve(capture(querySlot)) } returns listOf(ragChunk("text", "a.md"))

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
            conversationalQuery = true,
        )
        // Устанавливаем цель диалога (память задачи) — как делает /rag scenario.
        agent.setWorkingMemory(WorkingMemory(currentTask = "разобраться в RAG-архитектуре"))
        // Первый ход — задаёт контекст для follow-up.
        agent.chat("какие стратегии чанкинга есть?")
        // Follow-up «а сколько для этого нужно?» — должен быть обогащён целью + первой репликой.
        agent.chat("а сколько для этого нужно токенов?")

        val enriched = querySlot.captured
        // Цель диалога попала в запрос.
        assertTrue(enriched.contains("разобраться в RAG-архитектуре"), "цель должна обогатить запрос: $enriched")
        // Предыдущая реплика попала в запрос (follow-up resolution).
        assertTrue(enriched.contains("какие стратегии чанкинга есть?"), "предыдущая реплика должна обогатить: $enriched")
        // Текущий вопрос — всегда последним.
        assertTrue(enriched.contains("а сколько для этого нужно токонов?") || enriched.contains("сколько для этого нужно"), "текущий вопрос должен быть в запросе: $enriched")
        assertTrue(enriched.contains("Контекст задачи:"), "должен быть блок контекста задачи")
        assertTrue(enriched.contains("Предыдущие вопросы:"), "должен быть блок предыдущих вопросов")
    }

    @Test
    fun `conversationalQuery true first turn no history — query equals plain message`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        coEvery { llm.chat(any()) } returns LlmResult.Success(fakeResponse())
        val retriever = mockk<RagRetriever>()
        val querySlot = slot<String>()
        coEvery { retriever.retrieve(capture(querySlot)) } returns listOf(ragChunk("text", "a.md"))

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            ragRetriever = retriever, ragEnabled = true,
            conversationalQuery = true,
        )
        // Первый ход: история пуста, currentTask не задан → только "Текущий вопрос".
        agent.chat("какой размер батча?")

        val enriched = querySlot.captured
        assertFalse(enriched.contains("Предыдущие вопросы:"), "на первом ходу не должно быть предыдущих реплик")
        assertFalse(enriched.contains("Контекст задачи:"), "без currentTask не должно быть блока задачи")
        assertTrue(enriched.contains("какой размер батча?"))
    }
}
