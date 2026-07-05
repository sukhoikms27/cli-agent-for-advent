package com.cliagent.rag

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 25: RAG включён по умолчанию. К концу недели 5 (с источниками, цитатами, анти-галлюцинациями,
 * сценариями) RAG — основная фича мини-чата; дефолт `enabled` изменён с `false` (дни 22-24) на `true`.
 *
 * Проверка: `RagConfig()` (без аргументов) даёт `enabled=true`. Это backward-compat-безопасно —
 * тесты ContextAwareAgentRagTest явно передают `ragEnabled` в конструктор, не зависят от дефолта.
 */
class RagConfigDefaultTest {

    @Test
    fun `default RagConfig has RAG enabled — день 25`() {
        val config = RagConfig()
        assertTrue(config.enabled, "RAG должен быть ON по умолчанию (день 25: production-like мини-чат)")
    }

    @Test
    fun `default RagConfig has conversationalQuery disabled — backward compat day 24`() {
        // conversationalQuery=false (default) → retrieve(userMessage) без обогащения (день 24 поведение).
        val config = RagConfig()
        assertFalse(config.conversationalQuery, "conversationalQuery default false (backward-compat день 24)")
    }

    @Test
    fun `RagConfig preserves dontKnowThreshold and corpusRoots from day 24`() {
        // Демонстрирует schema evolution: все поля дней 21-24 сохраняют дефолты.
        val config = RagConfig()
        assertTrue(config.corpusRoots.contains("AGENTS.md"), "corpusRoots из дня 24 сохранён")
        assertTrue(config.corpusRoots.contains("src/main/kotlin"), ".kt-исходники из дня 24 сохранены")
        assertFalse(config.dontKnowThreshold > 0.0f, "dontKnowThreshold default 0.0 (выключено, день 24)")
    }
}
