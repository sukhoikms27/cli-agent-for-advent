package com.cliagent.agent.stage

import com.cliagent.state.TaskKind
import com.cliagent.state.TaskStage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutionStageAgentTest {

    private val ctx = StageContext(
        taskDescription = "сделай калькулятор",
        approvedPlan = "1) Setup Gradle\n2) Impl Calculator\n3) Add CLI"
    )

    @Test
    fun `runs a StepAgent for each plan step and aggregates`() = runTest {
        val agent = ExecutionStageAgent()
        var calls = 0
        val result = agent.run(ctx) { msg ->
            calls++
            // Каждый шаг — отдельный вызов; возвращаем уникальный результат по номеру вызова
            "result-$calls"
        }
        assertEquals(3, calls)   // 3 шага → 3 LLM-вызова
        assertTrue(result.artifact!!.contains("result-1"))
        assertTrue(result.artifact!!.contains("result-2"))
        assertTrue(result.artifact!!.contains("result-3"))
        assertTrue(result.readyToAdvance)
    }

    @Test
    fun `artifact contains step headers`() = runTest {
        val agent = ExecutionStageAgent()
        val result = agent.run(ctx) { "ok" }
        assertTrue(result.artifact!!.contains("Шаг 1/3"))
        assertTrue(result.artifact!!.contains("Шаг 3/3"))
    }

    @Test
    fun `prose plan falls back to whole plan as single step`() = runTest {
        val proseCtx = StageContext(
            taskDescription = "сделай калькулятор",
            approvedPlan = "Просто сделай калькулятор с операциями."
        )
        val agent = ExecutionStageAgent()
        var calls = 0
        val result = agent.run(proseCtx) { calls++; "impl" }
        assertEquals(1, calls)   // prose → один шаг
        assertTrue(result.artifact!!.contains("impl"))
    }

    @Test
    fun `empty plan warns and runs direct implementation`() = runTest {
        val emptyCtx = StageContext(taskDescription = "task", approvedPlan = "   ")
        val agent = ExecutionStageAgent()
        val result = agent.run(emptyCtx) { "direct impl" }
        assertTrue(result.display.contains("План пуст"))
        assertEquals("direct impl", result.artifact)
    }

    @Test
    fun `feedback triggers refinement pass on top of steps`() = runTest {
        val agent = ExecutionStageAgent()
        // Сначала идут 3 шага, затем refinement-вызов, промпт которого содержит
        // «Текущая реализация» (маркер refine-ветки).
        val result = agent.run(ctx.copy(feedback = "добавь тесты")) { msg ->
            when {
                msg.contains("Текущая реализация") -> "refined with tests"
                else -> "step"
            }
        }
        assertTrue(result.artifact!!.contains("refined with tests"))
        assertTrue(result.artifact!!.contains("добавь тесты"))
    }

    @Test
    fun `stage is EXECUTION`() {
        assertEquals(TaskStage.EXECUTION, ExecutionStageAgent().stage)
    }

    // ── День 15 фикс #1: taskKind ветвит промпт шага ──

    @Test
    fun `REASONING taskKind produces non-code step instruction`() = runTest {
        val agent = ExecutionStageAgent()
        var captured = ""
        val reasonCtx = ctx.copy(taskKind = TaskKind.REASONING)
        agent.run(reasonCtx) { captured = it; "результат шага" }
        // промпт шага просит ответ/решение и явно запрещает код
        assertTrue(captured.contains("Не пиши код"))
        assertFalse(captured.contains("Дай реализацию текущего шага: код"))
    }

    @Test
    fun `CODE taskKind keeps code instruction`() = runTest {
        val agent = ExecutionStageAgent()
        var captured = ""
        val codeCtx = ctx.copy(taskKind = TaskKind.CODE)
        agent.run(codeCtx) { captured = it; "code" }
        assertTrue(captured.contains("Дай реализацию текущего шага: код"))
    }
}
