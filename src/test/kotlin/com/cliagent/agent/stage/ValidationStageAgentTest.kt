package com.cliagent.agent.stage

import com.cliagent.state.TaskStage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidationStageAgentTest {

    private val ctx = StageContext(
        taskDescription = "сделай калькулятор",
        approvedPlan = "1) Setup\n2) Impl Calculator",
        implementation = "class Calculator { ... }"
    )

    @Test
    fun `PASS verdict is ready to advance`() = runTest {
        val agent = ValidationStageAgent()
        val result = agent.run(ctx) { "Все шаги реализованы, тесты проходят. PASS" }
        assertTrue(result.readyToAdvance)
        assertTrue(result.artifact!!.contains("PASS"))
        assertTrue(result.display.contains("Проверка"))
    }

    @Test
    fun `REWORK verdict is not ready to advance`() = runTest {
        val agent = ValidationStageAgent()
        val result = agent.run(ctx) { "Шаг 2 не реализован до конца. REWORK" }
        assertFalse(result.readyToAdvance)
        assertTrue(result.artifact!!.contains("REWORK"))
        assertTrue(result.display.contains("проблемы"))
    }

    @Test
    fun `both PASS and REWORK present favors REWORK (safer)`() = runTest {
        val agent = ValidationStageAgent()
        val result = agent.run(ctx) { "Шаг 1 PASS, но шаг 2 нужно доработать REWORK" }
        assertFalse(result.readyToAdvance)
    }

    @Test
    fun `no verdict marker is not ready`() = runTest {
        val agent = ValidationStageAgent()
        val result = agent.run(ctx) { "Вроде всё ок" }
        assertFalse(result.readyToAdvance)
    }

    @Test
    fun `prompt includes plan and implementation`() = runTest {
        val agent = ValidationStageAgent()
        var captured = ""
        agent.run(ctx) { captured = it; "PASS" }
        assertTrue(captured.contains("1) Setup"))
        assertTrue(captured.contains("class Calculator"))
    }

    @Test
    fun `stage is VALIDATION`() {
        assertEquals(TaskStage.VALIDATION, ValidationStageAgent().stage)
    }
}
