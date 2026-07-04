package com.cliagent.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 25: тесты scripted-сценариев `/rag scenario`. Проверяет, что JSON-сценарии валидны, грузятся
 * из classpath, содержат 10+ связанных реплик с expectedSources/expectedKeywords. Сами data-классы
 * `EvalScenario`/`EvalScenarioTurn` — private в [RagCommands], поэтому здесь локальные копии с той
 * же схемой (`ignoreUnknownKeys=true` гарантирует совместимость).
 */
class RagScenarioTest {

    @Serializable
    private data class Turn(
        val user: String,
        val expectedKeywords: List<String> = emptyList(),
        val expectedSources: List<String> = emptyList(),
    )

    @Serializable
    private data class Scenario(
        val id: String,
        val goal: String,
        val turns: List<Turn>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadScenario(name: String): Scenario? {
        val raw = javaClass.getResourceAsStream("/rag/scenarios/$name.json")?.use { it.readBytes() }
            ?: return null
        return json.decodeFromString<Scenario>(String(raw, Charsets.UTF_8))
    }

    @Test
    fun `rag-architecture scenario loads from classpath and has 10+ turns`() {
        val scenario = loadScenario("rag-architecture")
        assertNotNull(scenario, "rag-architecture.json должен грузиться из classpath")
        scenario!!
        assertEquals("rag-architecture", scenario.id)
        assertTrue(scenario.goal.isNotBlank(), "goal (цель диалога) должна быть задана")
        assertTrue(scenario.turns.size >= 10, "сценарий должен иметь ≥10 turns (задание: 10-15), got ${scenario.turns.size}")
        assertTrue(scenario.turns.size <= 15, "сценарий должен иметь ≤15 turns (задание), got ${scenario.turns.size}")
    }

    @Test
    fun `task-fsm scenario loads from classpath and has 10+ turns`() {
        val scenario = loadScenario("task-fsm")
        assertNotNull(scenario, "task-fsm.json должен грузиться из classpath")
        scenario!!
        assertEquals("task-fsm", scenario.id)
        assertTrue(scenario.goal.isNotBlank())
        assertTrue(scenario.turns.size >= 10, "task-fsm должен иметь ≥10 turns, got ${scenario.turns.size}")
    }

    @Test
    fun `scenarios have turns with expectedSources and expectedKeywords for post-check`() {
        // Каждый turn должен иметь expectedSources и/или expectedKeywords — иначе CitationDetector
        // и keyword-coverage не смогут проверить ответ (требование «источники в каждом ответе»).
        listOf("rag-architecture", "task-fsm").forEach { name ->
            val scenario = loadScenario(name)!!
            val withChecks = scenario.turns.count { it.expectedSources.isNotEmpty() || it.expectedKeywords.isNotEmpty() }
            assertTrue(
                withChecks >= scenario.turns.size * 0.8,
                "$name: ≥80% turns должны иметь expectedSources/Keywords для post-check, got $withChecks/${scenario.turns.size}",
            )
        }
    }

    @Test
    fun `nonexistent scenario returns null gracefully`() {
        val scenario = loadScenario("does-not-exist")
        assertEquals(null, scenario, "несуществующий сценарий → null (мягкая деградация)")
    }

    @Test
    fun `both scenarios reference different source files for diversity`() {
        // Задание: разнообразные источники, не только один файл. Проверяем, что expectedSources
        // покрывают ≥3 разных basename между двумя сценариями.
        val allSources = listOf("rag-architecture", "task-fsm").flatMap { name ->
            loadScenario(name)!!.turns.flatMap { it.expectedSources }
        }.toSet()
        assertTrue(allSources.size >= 3, "сценарии должны ссылаться на ≥3 разных источника, got $allSources")
    }
}
