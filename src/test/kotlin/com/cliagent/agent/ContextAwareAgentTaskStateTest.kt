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
import com.cliagent.state.TransitionOutcome
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextAwareAgentTaskStateTest {

    private fun fakeResponse(content: String = "ok"): ChatResponse = ChatResponse(
        id = "resp",
        choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = content))),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
    )

    /**
     * storeMock с поддержкой рабочей памяти: saveWorkingMemory пишет в captured-слот,
     * loadWorkingMemory читает его. Эмулирует персистентность WorkingMemory.
     */
    private fun storeMock(initialWorking: WorkingMemory? = null): MemoryStore {
        var stored: WorkingMemory? = initialWorking
        return mockk {
            coEvery { loadHistory(any()) } returns emptyList()
            coEvery { loadWorkingMemory(any()) } answers { stored }
            coEvery { saveWorkingMemory(any(), any()) } answers { stored = secondArg() }
            coEvery { loadLongTermMemory() } returns LongTermMemory()
            coEvery { loadSummary(any()) } returns null
            coEvery { saveMessage(any(), any()) } returns Unit
            coEvery { saveLongTermMemory(any()) } returns Unit
            coEvery { saveFacts(any(), any()) } returns Unit
            coEvery { loadFacts(any()) } returns emptyMap()
        }
    }

    @Test
    fun `setTaskState persists and reads back`() = runTest {
        val store = storeMock()
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            store, "m", "chat-1"
        )

        agent.setTaskState(TaskState(stage = TaskStage.EXECUTION, currentStep = "step1"))
        val read = agent.getTaskState()
        assertEquals(TaskStage.EXECUTION, read?.stage)
        assertEquals("step1", read?.currentStep)
    }

    @Test
    fun `setTaskState does not clobber currentTask plan and decisions`() = runTest {
        val initial = WorkingMemory(
            currentTask = "task",
            plan = "plan",
            taskDecisions = listOf("d1")
        )
        val store = storeMock(initial)
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            store, "m", "chat-1"
        )

        agent.setTaskState(TaskState(stage = TaskStage.PLANNING))
        val w = agent.getWorkingMemory()
        assertEquals("task", w?.currentTask)          // сохранено
        assertEquals("plan", w?.plan)                  // сохранено
        assertEquals(listOf("d1"), w?.taskDecisions)   // сохранено
        assertEquals(TaskStage.PLANNING, w?.taskState?.stage)  // установлено
    }

    @Test
    fun `setTaskState null clears task state but keeps other working memory`() = runTest {
        val initial = WorkingMemory(
            currentTask = "task",
            taskState = TaskState(stage = TaskStage.EXECUTION)
        )
        val store = storeMock(initial)
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            store, "m", "chat-1"
        )

        agent.setTaskState(null)
        assertNull(agent.getTaskState())
        assertEquals("task", agent.getWorkingMemory()?.currentTask)  // сохранено
    }

    @Test
    fun `advanceTaskState moves forward through canonical stages`() = runTest {
        val store = storeMock()
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            store, "m", "chat-1"
        )

        // День 15: advanceTaskState делегирует в TransitionGuard → каждый forward-canonical
        // требует артефакт соответствующей стадии (canAdvance).
        agent.setTaskState(TaskState(stage = TaskStage.PLANNING, approvedPlan = "plan"))
        assertEquals(TaskStage.EXECUTION, agent.advanceTaskState()?.stage)
        agent.setTaskState(agent.getTaskState()!!.copy(implementation = "impl"))
        assertEquals(TaskStage.VALIDATION, agent.advanceTaskState()?.stage)
        agent.setTaskState(agent.getTaskState()!!.copy(verdict = "pass"))
        assertEquals(TaskStage.DONE, agent.advanceTaskState()?.stage)
        assertNull(agent.advanceTaskState())   // DONE → нет следующей
    }

    @Test
    fun `revertTaskState pops history and restores previous stage`() = runTest {
        val store = storeMock()
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            store, "m", "chat-1"
        )

        // День 15: forward-переходы через guard требуют артефакты.
        agent.setTaskState(TaskState(stage = TaskStage.PLANNING, approvedPlan = "plan"))
        agent.advanceTaskState()  // → execution
        agent.setTaskState(agent.getTaskState()!!.copy(implementation = "impl"))
        agent.advanceTaskState()  // → validation
        assertEquals(TaskStage.VALIDATION, agent.getTaskState()?.stage)

        assertEquals(TaskStage.EXECUTION, agent.revertTaskState()?.stage)
    }

    @Test
    fun `revertTaskState returns null on empty history`() = runTest {
        val store = storeMock()
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            store, "m", "chat-1"
        )
        agent.setTaskState(TaskState(stage = TaskStage.PLANNING))
        assertNull(agent.revertTaskState())
    }

    @Test
    fun `task state is injected into system prompt`() = runTest {
        val store = storeMock(WorkingMemory(taskState = TaskState(stage = TaskStage.EXECUTION, currentStep = "wire")))
        val llm = mockk<LlmClient>()
        val requestSlot = slot<ChatRequest>()
        coEvery { llm.chat(capture(requestSlot)) } returns LlmResult.Success(fakeResponse())

        val agent = ContextAwareAgent(llm, store, "m", "chat-1")
        agent.chat("continue")

        val systemContent = requestSlot.captured.messages.first().content
        assertTrue(systemContent.contains("Task state:"))
        assertTrue(systemContent.contains("Stage: execution"))
        assertTrue(systemContent.contains("Current step: wire"))
    }

    // ── attemptTransition (день 15) ──

    @Test
    fun `attemptTransition returns null when no active task`() = runTest {
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            storeMock(), "m", "chat-1"
        )
        assertNull(agent.attemptTransition(TaskStage.EXECUTION))
    }

    @Test
    fun `attemptTransition allowed persists new state`() = runTest {
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            storeMock(), "m", "chat-1"
        )
        agent.setTaskState(TaskState(stage = TaskStage.PLANNING, approvedPlan = "plan"))
        val outcome = agent.attemptTransition(TaskStage.EXECUTION)
        assertTrue(outcome is TransitionOutcome.Allowed)
        assertEquals(TaskStage.EXECUTION, agent.getTaskState()?.stage)
    }

    @Test
    fun `attemptTransition illegal does not change state`() = runTest {
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            storeMock(), "m", "chat-1"
        )
        agent.setTaskState(TaskState(stage = TaskStage.PLANNING, approvedPlan = "plan"))
        val outcome = agent.attemptTransition(TaskStage.DONE)
        assertTrue(outcome is TransitionOutcome.Illegal)
        assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)  // не изменилось
    }

    @Test
    fun `attemptTransition artifact missing does not change state`() = runTest {
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            storeMock(), "m", "chat-1"
        )
        agent.setTaskState(TaskState(stage = TaskStage.PLANNING))  // нет плана
        val outcome = agent.attemptTransition(TaskStage.EXECUTION)
        assertTrue(outcome is TransitionOutcome.ArtifactMissing)
        assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)
    }

    @Test
    fun `attemptTransition force bypasses gate and persists`() = runTest {
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            storeMock(), "m", "chat-1"
        )
        agent.setTaskState(TaskState(stage = TaskStage.PLANNING))
        val outcome = agent.attemptTransition(TaskStage.DONE, force = true)
        assertTrue(outcome is TransitionOutcome.Allowed)
        assertEquals(TaskStage.DONE, agent.getTaskState()?.stage)
    }

    @Test
    fun `advanceTaskState returns null on artifact missing via guard`() = runTest {
        // обратная совместимость: advanceTaskState делегирует в attemptTransition
        val agent = ContextAwareAgent(
            mockk { coEvery { chat(any()) } returns LlmResult.Success(fakeResponse()) },
            storeMock(), "m", "chat-1"
        )
        agent.setTaskState(TaskState(stage = TaskStage.PLANNING))  // нет плана
        assertNull(agent.advanceTaskState())  // ArtifactMissing → null
        assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)
    }
}
