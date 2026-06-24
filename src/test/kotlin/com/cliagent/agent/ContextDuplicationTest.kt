package com.cliagent.agent

import com.cliagent.context.ContextManager
import com.cliagent.context.strategy.SlidingWindowStrategy
import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * День 17 (регрессия дублирования): при активном [ContextManager] агент добавляет userMsg в history
 * до сборки сообщений (нужно legacy-пути + persist), но стратегии по контракту сами делают
 * `history + newMessage`. Без фикс user-сообщение уезжало в LLM дважды (лишние токены; замечено в
 * debug-дампе Request body). Фикс: strategy-ветка передаёт history без userMsg.
 */
class ContextDuplicationTest {

    private fun storeMock(): MemoryStore = mockk(relaxed = true) {
        coEvery { loadHistory(any()) } returns emptyList()
        coEvery { loadWorkingMemory(any()) } returns null
        coEvery { loadLongTermMemory() } returns LongTermMemory()
        coEvery { loadSummary(any()) } returns null
        coEvery { loadFacts(any()) } returns emptyMap()
    }

    private fun finalResp(content: String): ChatResponse = ChatResponse(
        id = "r",
        choices = listOf(Choice(0, ChatMessage(role = "assistant", content = content), "stop")),
        usage = Usage(1, 1, 2)
    )

    @Test
    fun `user message appears exactly once in request with context strategy`() = runTest {
        val requestSlot = slot<ChatRequest>()
        val llm = mockk<LlmClient> {
            coEvery { chat(capture(requestSlot)) } returns LlmResult.Success(finalResp("ok"))
        }
        val agent = ContextAwareAgent(
            llmClient = llm,
            memoryStore = storeMock(),
            model = "m",
            chatId = "chat-1",
            contextManager = ContextManager(SlidingWindowStrategy(windowSize = 10)),
        )

        agent.chat("расскажи о репозитории telegram")

        val userMsgs = requestSlot.captured.messages
            .filter { it.role == "user" && it.content == "расскажи о репозитории telegram" }
        assertEquals(1, userMsgs.size, "user message must not be duplicated: ${requestSlot.captured.messages}")
    }
}
