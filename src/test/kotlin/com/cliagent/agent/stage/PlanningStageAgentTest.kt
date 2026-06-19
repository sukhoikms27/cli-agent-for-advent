package com.cliagent.agent.stage

import com.cliagent.memory.UserProfile
import com.cliagent.state.TaskStage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanningStageAgentTest {

    private val ctx = StageContext(
        taskDescription = "сделай калькулятор",
        requirements = "Стек=Kotlin, операции=+-*/"
    )

    @Test
    fun `whole model response becomes the approved plan artifact`() = runTest {
        val agent = PlanningStageAgent()
        val plan = "1) Setup Gradle\n2) Impl Calculator\n3) Add CLI"
        val result = agent.run(ctx) { plan }
        assertEquals(plan, result.artifact)
        assertTrue(result.readyToAdvance)
        assertTrue(result.display.contains("План"))
    }

    @Test
    fun `requirements are included in the prompt`() = runTest {
        val agent = PlanningStageAgent()
        var captured = ""
        agent.run(ctx) { captured = it; "1) ..." }
        assertTrue(captured.contains("Стек=Kotlin"))
    }

    @Test
    fun `feedback is included in the prompt for re-generation`() = runTest {
        val agent = PlanningStageAgent()
        var captured = ""
        agent.run(ctx.copy(feedback = "добавь шаг про тесты")) { captured = it; "1) ..." }
        assertTrue(captured.contains("добавь шаг про тесты"))
    }

    @Test
    fun `blank response is not ready to advance`() = runTest {
        val agent = PlanningStageAgent()
        val result = agent.run(ctx) { "   " }
        assertEquals(false, result.readyToAdvance)
    }

    @Test
    fun `profile block with constraints is included`() = runTest {
        val agent = PlanningStageAgent()
        var captured = ""
        val profile = UserProfile(constraints = listOf("только Kotlin", "без RxJava"))
        agent.run(ctx.copy(profile = profile)) { captured = it; "1) ..." }
        assertTrue(captured.contains("только Kotlin"))
        assertTrue(captured.contains("без RxJava"))
    }

    @Test
    fun `stage is PLANNING`() {
        assertEquals(TaskStage.PLANNING, PlanningStageAgent().stage)
    }
}
