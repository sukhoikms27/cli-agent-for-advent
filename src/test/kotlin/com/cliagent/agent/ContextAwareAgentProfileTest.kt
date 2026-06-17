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
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextAwareAgentProfileTest {

    private fun fakeResponse(content: String = "ok"): ChatResponse = ChatResponse(
        id = "resp",
        choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = content))),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
    )

    private fun storeMock(longTerm: LongTermMemory = LongTermMemory()): MemoryStore = mockk {
        coEvery { loadHistory(any()) } returns emptyList()
        coEvery { loadWorkingMemory(any()) } returns null
        coEvery { loadLongTermMemory() } returns longTerm
        coEvery { loadSummary(any()) } returns null
        coEvery { saveMessage(any(), any()) } returns Unit
        coEvery { saveLongTermMemory(any()) } returns Unit
    }

    @Test
    fun `profile is injected into system prompt`() = runTest {
        val profile = UserProfile(
            style = "concise", format = "with code", about = "backend dev, Ktor",
            constraints = listOf("no RxJava")
        )
        val store = storeMock(LongTermMemory(profile = profile))
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())

        val agent = ContextAwareAgent(llm, store, "m", "chat-1")
        agent.chat("hi")

        val systemContent = requestSlot.captured.messages.first().content
        assertTrue(systemContent.contains("User profile:"))
        assertTrue(systemContent.contains("Style: concise"))
        assertTrue(systemContent.contains("About: backend dev, Ktor"))
        assertTrue(systemContent.contains("no RxJava"))
    }

    @Test
    fun `auto-extraction updates profile every N turns`() = runTest {
        // LLM возвращает текст профиля на любой вызов (и для chat, и для экстрактора)
        val profileText = """
            style: concise
            about: backend dev
            constraints:
            - Kotlin only
        """.trimIndent()
        val llm = mockk<LlmClient> { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse(profileText)) }
        val store = storeMock(LongTermMemory())  // профиль изначально пуст
        val extractor = ProfileExtractor(llm, "m")

        val agent = ContextAwareAgent(
            llm, store, "m", "chat-1",
            profileExtractor = extractor,
            autoProfileEvery = 2
        )

        // ход 1: turnCount=1, извлечения нет
        agent.chat("hi")
        assertEquals(null, agent.getProfile())

        // ход 2: turnCount=2 → извлечение, merge, persist
        agent.chat("again")
        val profile = agent.getProfile()
        assertEquals("concise", profile?.style)
        assertEquals("backend dev", profile?.about)
        assertEquals(listOf("Kotlin only"), profile?.constraints)
    }

    @Test
    fun `setProfile preserves long-term knowledge and decisions`() = runTest {
        val store = storeMock(LongTermMemory(knowledge = mapOf("stack" to "Kotlin"), decisions = mapOf("arch" to "MVI")))
        val agent = ContextAwareAgent(mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            store, "m", "chat-1")

        agent.setProfile(UserProfile(style = "concise"))
        val lt = agent.getLongTermMemory()
        assertEquals("Kotlin", lt.knowledge["stack"])     // сохранено
        assertEquals("MVI", lt.decisions["arch"])          // сохранено
        assertEquals("concise", lt.profile?.style)         // профиль установлен
    }
}
