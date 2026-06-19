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

        agent.setTaskState(TaskState(stage = TaskStage.PLANNING))
        assertEquals(TaskStage.EXECUTION, agent.advanceTaskState()?.stage)
        assertEquals(TaskStage.VALIDATION, agent.advanceTaskState()?.stage)
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

        agent.setTaskState(TaskState(stage = TaskStage.PLANNING))
        agent.advanceTaskState()  // → execution
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
}
