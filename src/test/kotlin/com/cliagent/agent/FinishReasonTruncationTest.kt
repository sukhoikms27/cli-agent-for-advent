package com.cliagent.agent

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmCallException
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FinishReasonTruncationTest {

    private fun resp(content: String, finishReason: String?): ChatResponse = ChatResponse(
        id = "r",
        choices = listOf(Choice(0, ChatMessage(role = "assistant", content = content), finishReason)),
        usage = Usage(1, 1, 2)
    )

    /** Relaxed store — возвращает пустоты для всего; явно переопределяем только нужное. */
    private fun storeMock(): MemoryStore = mockk(relaxed = true) {
        coEvery { loadHistory(any()) } returns emptyList()
        coEvery { loadWorkingMemory(any()) } returns null
        coEvery { loadLongTermMemory() } returns LongTermMemory()
        coEvery { loadSummary(any()) } returns null
        coEvery { loadFacts(any()) } returns emptyMap()
    }

    @Test
    fun `finish_reason length throws truncated exception and does not persist assistant`() = runTest {
        val llm = mockk<LlmClient> {
            coEvery { chat(any()) } returns LlmResult.Success(resp("частичный ответ", "length"))
        }
        val store = storeMock()
        val agent = ContextAwareAgent(llm, store, "m", "chat-1")

        val e = try { agent.chat("задача"); null } catch (ex: LlmCallException) { ex }
        assertTrue(e != null && e.isTruncated, "должен бросить LlmCallException.isTruncated")
        assertEquals("частичный ответ", e!!.partial)
        // assistant НЕ сохраняется — только user-сообщение (до LLM-вызова)
        coVerify(exactly = 1) { store.saveMessage(any(), any()) }
    }

    @Test
    fun `finish_reason stop returns content normally`() = runTest {
        val llm = mockk<LlmClient> {
            coEvery { chat(any()) } returns LlmResult.Success(resp("полный ответ", "stop"))
        }
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        assertEquals("полный ответ", agent.chat("задача"))
    }

    @Test
    fun `null finish_reason returns content normally`() = runTest {
        val llm = mockk<LlmClient> {
            coEvery { chat(any()) } returns LlmResult.Success(resp("ответ", null))
        }
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        assertEquals("ответ", agent.chat("задача"))
    }

    @Test
    fun `max_tokens is set on request within bounds`() = runTest {
        val llm = mockk<LlmClient>()
        val reqSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(reqSlot)) } returns LlmResult.Success(resp("ok", "stop"))

        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        agent.chat("задача")

        assertTrue(reqSlot.isCaptured)
        val maxTokens = reqSlot.captured.maxTokens
        assertTrue(maxTokens != null && maxTokens >= 4096, "maxTokens должно быть задано и >= 4096: $maxTokens")
    }
}
