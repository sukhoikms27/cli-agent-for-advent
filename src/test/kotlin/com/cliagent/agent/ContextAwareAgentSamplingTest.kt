package com.cliagent.agent

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.StreamChunk
import com.cliagent.llm.model.Usage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * День 31: проброс sampling-параметров конструктора [ContextAwareAgent] в [ChatRequest] — обе точки
 * сборки ([chat] → [runToolLoop], [chatStreamed]). Проверяем, что topK/topP/seed/stop/penalties,
 * заданные в конструкторе, материализуются в захваченном запросе к LLM.
 *
 * Паттерн симметричен [ContextAwareAgentRagTest]: mock [LlmClient] + slot<ChatRequest> перехватывает
 * отправленный запрос; assertions на sampling-полях. Без RAG/tools — изолированный unit.
 */
class ContextAwareAgentSamplingTest {

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

    @Test
    fun `chat propagates topK into ChatRequest via runToolLoop`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            topK = 40, topP = 0.9, seed = 42L,
            stop = listOf("END"), frequencyPenalty = 0.3, presencePenalty = 0.2,
        )
        agent.chat("hi")

        val req = requestSlot.captured
        assertEquals(40, req.topK, "topK must propagate to ChatRequest")
        assertEquals(0.9, req.topP, "topP must propagate")
        assertEquals(42L, req.seed, "seed must propagate")
        assertEquals(listOf("END"), req.stop, "stop must propagate")
        assertEquals(0.3, req.frequencyPenalty, "frequencyPenalty must propagate")
        assertEquals(0.2, req.presencePenalty, "presencePenalty must propagate")
    }

    @Test
    fun `chatStreamed propagates topK into ChatRequest`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        // chatStreamed собирает Delta в полный контент; эмитим один Delta + Done.
        coEvery { llm.chatStream(capture(requestSlot)) } returns flow {
            emit(StreamChunk.Delta("answer"))
            emit(StreamChunk.Done(usage = Usage(1, 1, 2), finishReason = "stop"))
        }

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
            topK = 40, topP = 0.9, seed = 42L,
        )
        val content = agent.chatStreamed("hi", onToken = {})

        assertEquals("answer", content)
        val req = requestSlot.captured
        assertEquals(40, req.topK, "topK must propagate to streaming ChatRequest")
        assertEquals(0.9, req.topP)
        assertEquals(42L, req.seed)
        // chatStreamed форсирует stream=true (без tools — свободный чат).
        assertEquals(true, req.stream)
    }

    @Test
    fun `sampling defaults to null when not set (backward-compat days 1-30)`() = runTest {
        val store = memoryStoreMock()
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())

        val agent = ContextAwareAgent(
            llmClient = llm, memoryStore = store, model = "m", chatId = "c",
        )
        agent.chat("hi")

        val req = requestSlot.captured
        assertNull(req.topK, "topK default null (no sampling configured)")
        assertNull(req.topP)
        assertNull(req.seed)
        assertNull(req.stop)
        assertNull(req.frequencyPenalty)
        assertNull(req.presencePenalty)
    }
}
