package com.cliagent.agent.stage

import com.cliagent.agent.swarm.SwarmMode
import com.cliagent.agent.swarm.SwarmStageAgent
import com.cliagent.state.TaskComplexity
import com.cliagent.state.TaskStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Волна W1 (день 21): стадийный гейт + [SwarmMode]. Покрывает [TaskOrchestrator.defaultAgents]:
 * - [SwarmMode.OFF] → простые агенты на ВСЕХ стадиях.
 * - [SwarmMode.ON]  → рой на ВСЕХ стадиях.
 * - [SwarmMode.AUTO] → рой только на PLANNING/EXECUTION/VALIDATION; CLARIFY/DONE — простые
 *   (W1.1 DONE не swarm'ится; W1.2 CLARIFY не swarm'ится).
 * - AUTO + [TaskComplexity.TRIVIAL] → простые на ВСЕХ стадиях (волна W2: рой не окупается).
 */
class SwarmModeGateTest {

    private fun isSwarm(agent: StageAgent): Boolean = agent is SwarmStageAgent

    @Test
    fun `OFF - simple agents on all stages`() {
        val agents = TaskOrchestrator.defaultAgents(SwarmMode.OFF)
        TaskStage.entries.forEach { stage ->
            assertFalse(isSwarm(agents[stage]!!), "$stage should be simple under OFF, got ${agents[stage]!!::class.simpleName}")
        }
    }

    @Test
    fun `ON - swarm agents on all stages`() {
        val agents = TaskOrchestrator.defaultAgents(SwarmMode.ON)
        TaskStage.entries.forEach { stage ->
            assertTrue(isSwarm(agents[stage]!!), "$stage should be swarm under ON")
        }
    }

    @Test
    fun `AUTO - swarm only on PLANNING, EXECUTION, VALIDATION`() {
        val agents = TaskOrchestrator.defaultAgents(SwarmMode.AUTO)
        // W1.1 + W1.2: CLARIFY и DONE — простые (фан-аут неоправдан)
        assertFalse(isSwarm(agents[TaskStage.CLARIFY]!!), "CLARIFY must be simple under AUTO (W1.2)")
        assertFalse(isSwarm(agents[TaskStage.DONE]!!), "DONE must be simple under AUTO (W1.1)")
        // Рой окупается на декомпозиции / исполнении / проверке
        assertTrue(isSwarm(agents[TaskStage.PLANNING]!!), "PLANNING must be swarm under AUTO")
        assertTrue(isSwarm(agents[TaskStage.EXECUTION]!!), "EXECUTION must be swarm under AUTO")
        assertTrue(isSwarm(agents[TaskStage.VALIDATION]!!), "VALIDATION must be swarm under AUTO")
    }

    @Test
    fun `AUTO plus TRIVIAL complexity - all simple (swarm not worth it)`() {
        val agents = TaskOrchestrator.defaultAgents(SwarmMode.AUTO, complexity = TaskComplexity.TRIVIAL)
        TaskStage.entries.forEach { stage ->
            assertFalse(isSwarm(agents[stage]!!), "$stage should be simple for TRIVIAL even under AUTO")
        }
    }

    @Test
    fun `AUTO plus COMPLEX complexity - same as plain AUTO`() {
        val agents = TaskOrchestrator.defaultAgents(SwarmMode.AUTO, complexity = TaskComplexity.COMPLEX)
        assertTrue(isSwarm(agents[TaskStage.PLANNING]!!))
        assertTrue(isSwarm(agents[TaskStage.EXECUTION]!!))
        assertTrue(isSwarm(agents[TaskStage.VALIDATION]!!))
        assertFalse(isSwarm(agents[TaskStage.DONE]!!))
    }

    @Test
    fun `legacy boolean overload - true equals ON, false equals OFF`() {
        // backward-compat: старая сигнатура defaultAgents(swarm: Boolean) работает
        val onAgents = TaskOrchestrator.defaultAgents(swarm = true)
        val offAgents = TaskOrchestrator.defaultAgents(swarm = false)
        assertTrue(isSwarm(onAgents[TaskStage.PLANNING]!!))
        assertFalse(isSwarm(offAgents[TaskStage.PLANNING]!!))
    }

    @Test
    fun `SwarmMode fromString parses labels, unknown defaults to AUTO`() {
        assertEquals(SwarmMode.OFF, SwarmMode.fromString("off"))
        assertEquals(SwarmMode.ON, SwarmMode.fromString("ON"))
        assertEquals(SwarmMode.AUTO, SwarmMode.fromString("auto"))
        assertEquals(SwarmMode.AUTO, SwarmMode.fromString("garbage"))
        assertEquals(SwarmMode.AUTO, SwarmMode.fromString(null))
    }
}
