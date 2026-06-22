package com.cliagent.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransitionGuardTest {

    // ── Allowed: легальные переходы с артефактом ──

    @Test
    fun `allowed forward transition with artifact`() {
        val state = TaskState(stage = TaskStage.PLANNING, approvedPlan = "1) ... 2) ...")
        val outcome = TransitionGuard.attempt(state, TaskStage.EXECUTION)
        assertTrue(outcome is TransitionOutcome.Allowed)
        assertEquals(TaskStage.EXECUTION, (outcome as TransitionOutcome.Allowed).newState.stage)
    }

    @Test
    fun `allowed clarify to planning always (no artifact needed)`() {
        val state = TaskState(stage = TaskStage.CLARIFY)
        val outcome = TransitionGuard.attempt(state, TaskStage.PLANNING)
        assertTrue(outcome is TransitionOutcome.Allowed)
        assertEquals(TaskStage.PLANNING, (outcome as TransitionOutcome.Allowed).newState.stage)
    }

    @Test
    fun `allowed transition appends history`() {
        val state = TaskState(stage = TaskStage.PLANNING, approvedPlan = "plan")
        val outcome = TransitionGuard.attempt(state, TaskStage.EXECUTION)
        assertEquals(1, (outcome as TransitionOutcome.Allowed).newState.stageHistory.size)
    }

    // ── Боковые переходы (rework/replan/new-task) — без gate ──

    @Test
    fun `side transition validation to execution without artifact is allowed`() {
        // rework: validation→execution легален, не требует verdict
        val state = TaskState(stage = TaskStage.VALIDATION)
        val outcome = TransitionGuard.attempt(state, TaskStage.EXECUTION)
        assertTrue(outcome is TransitionOutcome.Allowed)
        assertEquals(TaskStage.EXECUTION, (outcome as TransitionOutcome.Allowed).newState.stage)
    }

    @Test
    fun `side transition execution to planning without artifact is allowed`() {
        // replan: execution→planning легален, не требует implementation
        val state = TaskState(stage = TaskStage.EXECUTION)
        val outcome = TransitionGuard.attempt(state, TaskStage.PLANNING)
        assertTrue(outcome is TransitionOutcome.Allowed)
    }

    @Test
    fun `side transition done to planning without artifact is allowed`() {
        // new task: done→planning легален
        val state = TaskState(stage = TaskStage.DONE)
        val outcome = TransitionGuard.attempt(state, TaskStage.PLANNING)
        assertTrue(outcome is TransitionOutcome.Allowed)
    }

    // ── Illegal: перепрыгивание ──

    @Test
    fun `illegal jump planning to done`() {
        val state = TaskState(stage = TaskStage.PLANNING, approvedPlan = "plan")
        val outcome = TransitionGuard.attempt(state, TaskStage.DONE)
        assertTrue(outcome is TransitionOutcome.Illegal)
        val il = outcome as TransitionOutcome.Illegal
        assertEquals(TaskStage.PLANNING, il.from)
        assertEquals(TaskStage.DONE, il.to)
        assertEquals(setOf(TaskStage.PLANNING, TaskStage.EXECUTION), il.allowedTargets)
    }

    @Test
    fun `illegal jump clarify to execution`() {
        val state = TaskState(stage = TaskStage.CLARIFY)
        val outcome = TransitionGuard.attempt(state, TaskStage.EXECUTION)
        assertTrue(outcome is TransitionOutcome.Illegal)
        assertEquals(setOf(TaskStage.CLARIFY, TaskStage.PLANNING),
            (outcome as TransitionOutcome.Illegal).allowedTargets)
    }

    // ── ArtifactMissing: forward-canonical без артефакта ──

    @Test
    fun `artifact missing planning to execution without plan`() {
        val state = TaskState(stage = TaskStage.PLANNING)  // approvedPlan пуст
        val outcome = TransitionGuard.attempt(state, TaskStage.EXECUTION)
        assertTrue(outcome is TransitionOutcome.ArtifactMissing)
        val am = outcome as TransitionOutcome.ArtifactMissing
        assertEquals(TaskStage.PLANNING, am.from)
        assertEquals(TaskStage.EXECUTION, am.to)
        assertTrue(am.hint.contains("/task plan"))
    }

    @Test
    fun `artifact missing validation to done without verdict`() {
        val state = TaskState(stage = TaskStage.VALIDATION)  // verdict пуст
        val outcome = TransitionGuard.attempt(state, TaskStage.DONE)
        assertTrue(outcome is TransitionOutcome.ArtifactMissing)
        assertTrue((outcome as TransitionOutcome.ArtifactMissing).hint.contains("/task verdict"))
    }

    @Test
    fun `artifact missing hint for execution stage mentions impl`() {
        val state = TaskState(stage = TaskStage.EXECUTION)  // implementation пуст
        val outcome = TransitionGuard.attempt(state, TaskStage.VALIDATION)
        assertTrue(outcome is TransitionOutcome.ArtifactMissing)
        assertTrue((outcome as TransitionOutcome.ArtifactMissing).hint.contains("/task impl"))
    }

    // ── Force: escape hatch ──

    @Test
    fun `force performs illegal transition via forceSet`() {
        val state = TaskState(stage = TaskStage.PLANNING)
        val outcome = TransitionGuard.attempt(state, TaskStage.DONE, force = true)
        assertTrue(outcome is TransitionOutcome.Allowed)
        val allowed = outcome as TransitionOutcome.Allowed
        assertEquals(TaskStage.DONE, allowed.newState.stage)
        assertEquals("forced", allowed.newState.stageHistory.first().note)
    }

    @Test
    fun `force bypasses artifact gate`() {
        val state = TaskState(stage = TaskStage.PLANNING)  // нет плана
        val outcome = TransitionGuard.attempt(state, TaskStage.EXECUTION, force = true)
        assertTrue(outcome is TransitionOutcome.Allowed)
        assertEquals(TaskStage.EXECUTION, (outcome as TransitionOutcome.Allowed).newState.stage)
    }

    // ── Self-transitions ──

    @Test
    fun `self transition is allowed without history change`() {
        val state = TaskState(stage = TaskStage.PLANNING, approvedPlan = "plan")
        val outcome = TransitionGuard.attempt(state, TaskStage.PLANNING)
        assertTrue(outcome is TransitionOutcome.Allowed)
        val allowed = outcome as TransitionOutcome.Allowed
        assertEquals(TaskStage.PLANNING, allowed.newState.stage)
        assertTrue(allowed.newState.stageHistory.isEmpty())
    }

    @Test
    fun `self transition without artifact is still allowed`() {
        // self не проходит artifact-gate (это не forward-canonical при to==from)
        val state = TaskState(stage = TaskStage.PLANNING)  // нет плана
        val outcome = TransitionGuard.attempt(state, TaskStage.PLANNING)
        assertTrue(outcome is TransitionOutcome.Allowed)
    }
}
