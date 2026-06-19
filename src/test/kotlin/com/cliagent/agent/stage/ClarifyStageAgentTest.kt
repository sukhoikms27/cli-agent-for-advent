package com.cliagent.agent.stage

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClarifyStageAgentTest {

    private val ctx = StageContext(taskDescription = "сделай калькулятор")

    @Test
    fun `CLEAR marker yields requirements artifact and ready to advance`() = runTest {
        val agent = ClarifyStageAgent()
        val result = agent.run(ctx) { "[CLEAR] Стек=Kotlin, операции=+-*/" }
        assertEquals("Стек=Kotlin, операции=+-*/", result.artifact)
        assertTrue(result.readyToAdvance)
        assertTrue(result.display.contains("Требования собраны"))
    }

    @Test
    fun `ASK marker yields no artifact and not ready`() = runTest {
        val agent = ClarifyStageAgent()
        val result = agent.run(ctx) { "[ASK] Какой стек? Какие операции?" }
        assertNull(result.artifact)
        assertFalse(result.readyToAdvance)
        assertTrue(result.display.contains("Какой стек"))
    }

    @Test
    fun `no marker treated as question (not ready)`() = runTest {
        val agent = ClarifyStageAgent()
        val result = agent.run(ctx) { "Какие операции нужны?" }
        assertNull(result.artifact)
        assertFalse(result.readyToAdvance)
        assertTrue(result.display.contains("Какие операции"))
    }

    @Test
    fun `CLEAR marker is case insensitive`() = runTest {
        val agent = ClarifyStageAgent()
        val result = agent.run(ctx) { "[clear] всё ясно" }
        assertTrue(result.readyToAdvance)
        assertEquals("всё ясно", result.artifact)
    }

    @Test
    fun `blank CLEAR content falls back to task description`() = runTest {
        val agent = ClarifyStageAgent()
        val result = agent.run(ctx) { "[CLEAR]   " }
        assertTrue(result.readyToAdvance)
        assertEquals("сделай калькулятор", result.artifact)
    }

    @Test
    fun `feedback is included in the prompt to the model`() = runTest {
        val agent = ClarifyStageAgent()
        var captured = ""
        agent.run(ctx.copy(feedback = "Kotlin, +-*")) { captured = it; "[CLEAR]" }
        assertTrue(captured.contains("Kotlin, +-*"))
    }

    @Test
    fun `stage is CLARIFY`() {
        assertEquals(com.cliagent.state.TaskStage.CLARIFY, ClarifyStageAgent().stage)
    }
}
