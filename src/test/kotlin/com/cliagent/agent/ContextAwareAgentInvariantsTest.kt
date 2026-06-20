package com.cliagent.agent

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import com.cliagent.state.invariant.Invariant
import com.cliagent.state.invariant.InvariantCategory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextAwareAgentInvariantsTest {

    private fun fakeResponse(content: String = "ok"): ChatResponse = ChatResponse(
        id = "resp",
        choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = content))),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
    )

    /** MemoryStore mock с backing-полем (как в ContextAwareAgentTaskStateTest). */
    private fun storeMock(initialLtm: LongTermMemory? = null): MemoryStore {
        var ltm: LongTermMemory? = initialLtm
        var working: WorkingMemory? = null
        return mockk {
            coEvery { loadHistory(any()) } returns emptyList()
            coEvery { loadWorkingMemory(any()) } answers { working }
            coEvery { saveWorkingMemory(any(), any()) } answers { working = secondArg() }
            coEvery { loadLongTermMemory() } answers { ltm ?: LongTermMemory() }
            coEvery { saveLongTermMemory(any()) } answers { ltm = firstArg() }
            coEvery { loadSummary(any()) } returns null
            coEvery { saveMessage(any(), any()) } returns Unit
            coEvery { saveFacts(any(), any()) } returns Unit
            coEvery { loadFacts(any()) } returns emptyMap()
        }
    }

    private fun stubLlm(): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Success(fakeResponse())
    }

    private fun agent(store: MemoryStore) = ContextAwareAgent(stubLlm(), store, "m", "chat-1")

    private val compose = Invariant("no-compose", "no Compose", InvariantCategory.BAN)
    private val kotlin = Invariant("kotlin-only", "Kotlin only", InvariantCategory.STACK)

    @Test
    fun `getInvariants returns empty by default`() = runTest {
        val a = agent(storeMock())
        assertEquals(emptyList<Invariant>(), a.getInvariants())
    }

    @Test
    fun `setInvariants persists and reads back`() = runTest {
        val a = agent(storeMock())
        a.setInvariants(listOf(compose))
        assertEquals(listOf(compose), a.getInvariants())
    }

    @Test
    fun `addInvariant adds to list`() = runTest {
        val a = agent(storeMock())
        a.addInvariant(compose)
        a.addInvariant(kotlin)
        assertEquals(2, a.getInvariants().size)
        // sortedBy category: BAN(no-compose) before STACK(kotlin-only)
        assertEquals("no-compose", a.getInvariants().first().id)
        assertEquals("kotlin-only", a.getInvariants().last().id)
    }

    @Test
    fun `addInvariant with same id updates without duplicate`() = runTest {
        val a = agent(storeMock())
        a.addInvariant(Invariant("no-compose", "old rule", InvariantCategory.BAN))
        a.addInvariant(Invariant("no-compose", "new rule", InvariantCategory.BAN))
        val list = a.getInvariants()
        assertEquals(1, list.size)
        assertEquals("new rule", list.first().rule)
    }

    @Test
    fun `removeInvariant returns true and removes when id exists`() = runTest {
        val a = agent(storeMock())
        a.setInvariants(listOf(compose, kotlin))
        assertTrue(a.removeInvariant("no-compose"))
        assertEquals(listOf(kotlin), a.getInvariants())
    }

    @Test
    fun `removeInvariant returns false when id missing`() = runTest {
        val a = agent(storeMock())
        a.setInvariants(listOf(compose))
        assertFalse(a.removeInvariant("zzz"))
        assertEquals(listOf(compose), a.getInvariants())
    }

    @Test
    fun `setInvariants does not clobber profile and knowledge`() = runTest {
        val initial = LongTermMemory(
            knowledge = mapOf("stack" to "Kotlin"),
            profile = UserProfile(style = "concise")
        )
        val a = agent(storeMock(initial))
        a.setInvariants(listOf(compose))
        val ltm = a.getLongTermMemory()
        assertEquals("Kotlin", ltm.knowledge["stack"])       // сохранено
        assertEquals("concise", ltm.profile?.style)          // сохранено
        assertEquals(listOf(compose), ltm.invariants)        // установлено
    }
}
