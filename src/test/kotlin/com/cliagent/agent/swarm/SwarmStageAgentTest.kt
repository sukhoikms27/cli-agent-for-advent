package com.cliagent.agent.swarm

import com.cliagent.agent.stage.StageContext
import com.cliagent.llm.LlmCallException
import com.cliagent.state.TaskStage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwarmStageAgentTest {

    private val ctx = StageContext(
        taskDescription = "сделай калькулятор",
        approvedPlan = "1) Setup\n2) Impl",
        implementation = "class Calculator { ... }"
    )

    /** Маршрутизация chat-вызовов по сигнатуре промпта: lead / worker / integrator. */
    private fun routingChat(
        lead: () -> String = { "1) часть А\n2) часть Б\n3) часть В" },
        worker: (Int) -> String = { "результат worker'а" },
        integrate: () -> String = { "ФИНАЛ. PASS" },
        counters: Triple<MutableList<Int>, MutableList<Int>, MutableList<Int>>? = null
    ): suspend (String) -> String = { msg ->
        // Порядок важен: integratePrompt содержит «worker'ов» → проверяем integrator раньше worker.
        when {
            msg.contains("lead-агент") -> { counters?.first?.add(1); lead() }
            msg.contains("integrator") -> { counters?.third?.add(1); integrate() }
            msg.contains("worker") -> {
                counters?.second?.add(1)
                worker(counters?.second?.size ?: 0)
            }
            else -> "x"
        }
    }

    @Test
    fun `partition fans out workers and integrates`() = runTest {
        val agent = SwarmStageAgent(TaskStage.VALIDATION, SwarmSpec(SwarmStrategy.PARTITION, 3))
        val c = Triple(mutableListOf<Int>(), mutableListOf<Int>(), mutableListOf<Int>())
        val result = agent.run(ctx, routingChat(counters = c))

        assertEquals(1, c.first.size)     // 1 lead
        assertEquals(3, c.second.size)    // 3 workers
        assertEquals(1, c.third.size)     // 1 integrate
        assertTrue(result.readyToAdvance) // PASS
        assertTrue(result.display.contains("Проверка"))
    }

    @Test
    fun `redundancy replicates full task to N workers without lead`() = runTest {
        val agent = SwarmStageAgent(TaskStage.PLANNING, SwarmSpec(SwarmStrategy.REDUNDANCY, 3))
        val c = Triple(mutableListOf<Int>(), mutableListOf<Int>(), mutableListOf<Int>())
        agent.run(ctx.copy(approvedPlan = null), routingChat(integrate = { "1) план" }, counters = c))

        assertEquals(0, c.first.size)     // REDUNDANCY не зовёт lead
        assertEquals(3, c.second.size)    // 3 workers (реплика)
    }

    @Test
    fun `lead non-list output degrades to single worker`() = runTest {
        val agent = SwarmStageAgent(TaskStage.PLANNING, SwarmSpec(SwarmStrategy.PARTITION, 5))
        val c = Triple(mutableListOf<Int>(), mutableListOf<Int>(), mutableListOf<Int>())
        agent.run(
            ctx.copy(approvedPlan = null),
            routingChat(lead = { "это не список, просто текст" }, integrate = { "1) план" }, counters = c)
        )
        assertEquals(1, c.second.size)    // 1 worker (degraded)
    }

    @Test
    fun `worker failure does not crash stage - stub fed to integrator`() = runTest {
        val agent = SwarmStageAgent(TaskStage.PLANNING, SwarmSpec(SwarmStrategy.PARTITION, 2))
        val c = Triple(mutableListOf<Int>(), mutableListOf<Int>(), mutableListOf<Int>())
        val result = agent.run(
            ctx.copy(approvedPlan = null),
            routingChat(
                lead = { "1) модуль А\n2) модуль Б" },
                worker = { idx -> if (idx == 1) throw LlmCallException(0, "boom") else "план модуля Б" },
                integrate = { "1) План А\n2) План Б" },
                counters = c
            )
        )
        assertEquals(2, c.second.size)    // оба worker'а вызваны
        assertTrue(result.artifact?.isNotBlank() == true)
        assertTrue(result.readyToAdvance) // план non-blank
    }

    @Test
    fun `clarify clear marker yields ready artifact and ask stays not ready`() = runTest {
        val agent = SwarmStageAgent(TaskStage.CLARIFY, SwarmSpec(SwarmStrategy.SPECIALISTS, 3))
        val result = agent.run(
            ctx.copy(requirements = null),
            routingChat(lead = { "1) scope\n2) стек" }, integrate = { "[CLEAR] стек Kotlin, операции +-*/" })
        )
        assertTrue(result.readyToAdvance)
        assertEquals("стек Kotlin, операции +-*/", result.artifact)

        val askResult = agent.run(
            ctx.copy(requirements = null),
            routingChat(integrate = { "[ASK] какой стек?" })
        )
        assertEquals(false, askResult.readyToAdvance)
        assertEquals(null, askResult.artifact)
    }
}
