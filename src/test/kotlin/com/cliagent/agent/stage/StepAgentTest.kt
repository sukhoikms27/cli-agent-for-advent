package com.cliagent.agent.stage

import com.cliagent.memory.UserProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StepAgentTest {

    @Test
    fun `runs a single step and returns its result`() = runTest {
        val agent = StepAgent()
        val result = agent.run(
            step = "Impl Calculator class",
            plan = "1) Setup\n2) Impl Calculator\n3) CLI",
            doneSteps = listOf("Setup done"),
            taskDescription = "сделай калькулятор",
            profileBlock = null
        ) { "class Calculator { ... }" }
        assertTrue(result.contains("class Calculator"))
    }

    @Test
    fun `prompt includes plan current step and done steps`() = runTest {
        val agent = StepAgent()
        var captured = ""
        agent.run(
            step = "Impl Calculator",
            plan = "1) Setup\n2) Impl Calculator",
            doneSteps = listOf("Setup done"),
            taskDescription = "calc",
            profileBlock = null
        ) { captured = it; "ok" }
        assertTrue(captured.contains("1) Setup"))
        assertTrue(captured.contains("Impl Calculator"))
        assertTrue(captured.contains("Setup done"))
    }

    @Test
    fun `profile block is included when present`() = runTest {
        val agent = StepAgent()
        var captured = ""
        val block = UserProfile(constraints = listOf("только Kotlin")).let {
            "[User profile]\nConstraints:\n  - только Kotlin"
        }
        agent.run("step", "plan", emptyList(), "task", block) { captured = it; "ok" }
        assertTrue(captured.contains("только Kotlin"))
    }
}
