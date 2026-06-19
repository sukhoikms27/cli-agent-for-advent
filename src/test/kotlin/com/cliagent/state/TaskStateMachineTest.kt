package com.cliagent.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskStateMachineTest {

    @Test
    fun `allowed forward transitions`() {
        assertTrue(TaskStateMachine.isAllowed(TaskStage.CLARIFY, TaskStage.PLANNING))
        assertTrue(TaskStateMachine.isAllowed(TaskStage.PLANNING, TaskStage.EXECUTION))
        assertTrue(TaskStateMachine.isAllowed(TaskStage.EXECUTION, TaskStage.VALIDATION))
        assertTrue(TaskStateMachine.isAllowed(TaskStage.VALIDATION, TaskStage.DONE))
    }

    @Test
    fun `allowed rework and restart transitions`() {
        assertTrue(TaskStateMachine.isAllowed(TaskStage.VALIDATION, TaskStage.EXECUTION))
        assertTrue(TaskStateMachine.isAllowed(TaskStage.EXECUTION, TaskStage.PLANNING))
        assertTrue(TaskStateMachine.isAllowed(TaskStage.DONE, TaskStage.PLANNING))
    }

    @Test
    fun `self-transitions are allowed (idempotent)`() {
        TaskStage.entries.forEach { stage ->
            assertTrue(TaskStateMachine.isAllowed(stage, stage), "self $stage→$stage should be allowed")
        }
    }

    @Test
    fun `forbidden transitions`() {
        assertFalse(TaskStateMachine.isAllowed(TaskStage.PLANNING, TaskStage.DONE))
        assertFalse(TaskStateMachine.isAllowed(TaskStage.DONE, TaskStage.EXECUTION))
        assertFalse(TaskStateMachine.isAllowed(TaskStage.VALIDATION, TaskStage.PLANNING))
        assertFalse(TaskStateMachine.isAllowed(TaskStage.CLARIFY, TaskStage.EXECUTION))
    }

    @Test
    fun `next returns canonical successor`() {
        assertEquals(TaskStage.PLANNING, TaskStateMachine.next(TaskStage.CLARIFY))
        assertEquals(TaskStage.EXECUTION, TaskStateMachine.next(TaskStage.PLANNING))
        assertEquals(TaskStage.VALIDATION, TaskStateMachine.next(TaskStage.EXECUTION))
        assertEquals(TaskStage.DONE, TaskStateMachine.next(TaskStage.VALIDATION))
    }

    @Test
    fun `next from done is null`() {
        assertNull(TaskStateMachine.next(TaskStage.DONE))
    }

    @Test
    fun `transition updates stage and appends history`() {
        val state = TaskState(stage = TaskStage.PLANNING)
        val updated = TaskStateMachine.transition(state, TaskStage.EXECUTION)
        assertEquals(TaskStage.EXECUTION, updated.stage)
        assertEquals(1, updated.stageHistory.size)
        assertEquals(TaskStage.PLANNING, updated.stageHistory.first().from)
        assertEquals(TaskStage.EXECUTION, updated.stageHistory.first().to)
    }

    @Test
    fun `transition records note when provided`() {
        val updated = TaskStateMachine.transition(TaskState(stage = TaskStage.PLANNING), TaskStage.EXECUTION, "approved")
        assertEquals("approved", updated.stageHistory.first().note)
    }

    @Test
    fun `transition throws on illegal transition`() {
        val state = TaskState(stage = TaskStage.PLANNING)
        assertThrows(IllegalArgumentException::class.java) {
            TaskStateMachine.transition(state, TaskStage.DONE)
        }
    }

    @Test
    fun `self-transition does not append history`() {
        val state = TaskState(stage = TaskStage.PLANNING)
        val updated = TaskStateMachine.transition(state, TaskStage.PLANNING)
        assertEquals(TaskStage.PLANNING, updated.stage)
        assertTrue(updated.stageHistory.isEmpty())
    }

    @Test
    fun `forceSet bypasses rules and records forced note`() {
        val state = TaskState(stage = TaskStage.PLANNING)
        val updated = TaskStateMachine.forceSet(state, TaskStage.DONE)
        assertEquals(TaskStage.DONE, updated.stage)
        assertEquals(1, updated.stageHistory.size)
        assertEquals("forced", updated.stageHistory.first().note)
    }

    @Test
    fun `forceSet self-transition is no-op`() {
        val state = TaskState(stage = TaskStage.PLANNING)
        val updated = TaskStateMachine.forceSet(state, TaskStage.PLANNING)
        assertTrue(updated.stageHistory.isEmpty())
    }

    @Test
    fun `back reverts to previous stage by history`() {
        // PLANNING → EXECUTION → VALIDATION, then back once → EXECUTION
        var state = TaskState(stage = TaskStage.PLANNING)
        state = TaskStateMachine.transition(state, TaskStage.EXECUTION)
        state = TaskStateMachine.transition(state, TaskStage.VALIDATION)
        assertEquals(TaskStage.VALIDATION, state.stage)

        val reverted = TaskStateMachine.back(state)
        assertEquals(TaskStage.EXECUTION, reverted?.stage)
        assertEquals(1, reverted?.stageHistory?.size)
    }

    @Test
    fun `back returns null on empty history`() {
        assertNull(TaskStateMachine.back(TaskState(stage = TaskStage.PLANNING)))
    }

    // ── canAdvance: artifact-gate (доработка Day 13, Вариант 2) ──

    @Test
    fun `canAdvance clarify is always true`() {
        assertTrue(TaskStateMachine.canAdvance(TaskState(stage = TaskStage.CLARIFY)))
    }

    @Test
    fun `canAdvance done is always false`() {
        assertFalse(TaskStateMachine.canAdvance(TaskState(stage = TaskStage.DONE)))
    }

    @Test
    fun `canAdvance planning requires approvedPlan`() {
        assertFalse(TaskStateMachine.canAdvance(TaskState(stage = TaskStage.PLANNING)))
        assertTrue(TaskStateMachine.canAdvance(TaskState(stage = TaskStage.PLANNING, approvedPlan = "1) ... 2) ...")))
    }

    @Test
    fun `canAdvance execution requires implementation`() {
        assertFalse(TaskStateMachine.canAdvance(TaskState(stage = TaskStage.EXECUTION)))
        assertTrue(TaskStateMachine.canAdvance(TaskState(stage = TaskStage.EXECUTION, implementation = "Reducer done")))
    }

    @Test
    fun `canAdvance validation requires verdict`() {
        assertFalse(TaskStateMachine.canAdvance(TaskState(stage = TaskStage.VALIDATION)))
        assertTrue(TaskStateMachine.canAdvance(TaskState(stage = TaskStage.VALIDATION, verdict = "all tests green")))
    }

    @Test
    fun `canAdvance blank artifact does not pass`() {
        assertFalse(TaskStateMachine.canAdvance(TaskState(stage = TaskStage.PLANNING, approvedPlan = "   ")))
        assertFalse(TaskStateMachine.canAdvance(TaskState(stage = TaskStage.EXECUTION, implementation = "")))
    }
}
