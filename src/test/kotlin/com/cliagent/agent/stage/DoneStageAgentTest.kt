package com.cliagent.agent.stage

import com.cliagent.state.TaskStage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoneStageAgentTest {

    private val ctx = StageContext(
        taskDescription = "сделай калькулятор",
        approvedPlan = "1) Setup\n2) Impl Calculator",
        implementation = "class Calculator { ... }",
        verdict = "Все шаги реализованы. PASS"
    )

    @Test
    fun `produces summary artifact`() = runTest {
        val agent = DoneStageAgent()
        val result = agent.run(ctx) { "Калькулятор готов: план выполнен, код валиден." }
        assertTrue(result.artifact!!.contains("Калькулятор готов"))
        assertTrue(result.display.contains("Задача завершена"))
    }

    @Test
    fun `done is terminal - never ready to advance`() = runTest {
        val agent = DoneStageAgent()
        val result = agent.run(ctx) { "Готово." }
        assertFalse(result.readyToAdvance)
    }

    @Test
    fun `blank summary shows fallback text`() = runTest {
        val agent = DoneStageAgent()
        val result = agent.run(ctx) { "   " }
        assertTrue(result.display.contains("Итог недоступен"))
    }

    @Test
    fun `prompt includes all artifacts`() = runTest {
        val agent = DoneStageAgent()
        var captured = ""
        agent.run(ctx) { captured = it; "ok" }
        assertTrue(captured.contains("1) Setup"))
        assertTrue(captured.contains("class Calculator"))
        assertTrue(captured.contains("PASS"))
    }

    @Test
    fun `stage is DONE`() {
        assertEquals(TaskStage.DONE, DoneStageAgent().stage)
    }
}
