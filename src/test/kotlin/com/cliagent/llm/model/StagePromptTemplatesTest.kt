package com.cliagent.llm.model

import com.cliagent.state.TaskKind
import com.cliagent.state.TaskStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StagePromptTemplatesTest {

    @Test
    fun `buildSystemMessage returns non-empty system message for every stage`() {
        TaskStage.entries.forEach { stage ->
            val msg = StagePromptTemplates.buildSystemMessage(stage)
            assertEquals("system", msg.role)
            assertTrue(msg.content.isNotBlank(), "content blank for $stage")
        }
    }

    @Test
    fun `stage prompts are distinct per stage`() {
        val contents = TaskStage.entries.associateWith { StagePromptTemplates.buildSystemMessage(it).content }
        // все 5 — попарно различны
        val distinct = contents.values.toSet()
        assertEquals(TaskStage.entries.size, distinct.size)
    }

    @Test
    fun `clarify prompt asks questions and forbids code`() {
        val content = StagePromptTemplates.buildSystemMessage(TaskStage.CLARIFY).content.lowercase()
        assertTrue(content.contains("question") || content.contains("clarif"))
        assertTrue(content.contains("do not") && content.contains("code"))
    }

    @Test
    fun `execution prompt asks to implement`() {
        val content = StagePromptTemplates.buildSystemMessage(TaskStage.EXECUTION).content.lowercase()
        assertTrue(content.contains("implement") || content.contains("code"))
        assertFalse(content.contains("clarify"))
    }

    @Test
    fun `validation prompt asks for a verdict`() {
        val content = StagePromptTemplates.buildSystemMessage(TaskStage.VALIDATION).content.lowercase()
        assertTrue(content.contains("verdict") || content.contains("pass") || content.contains("rework"))
    }

    // ── День 15 фикс #1: taskKind-ветвление EXECUTION-промпта ──

    @Test
    fun `execution CODE prompt asks to write code`() {
        val content = StagePromptTemplates.buildSystemMessage(TaskStage.EXECUTION, TaskKind.CODE).content.lowercase()
        assertTrue(content.contains("write working code"))
    }

    @Test
    fun `execution REASONING prompt forbids code`() {
        val content = StagePromptTemplates.buildSystemMessage(TaskStage.EXECUTION, TaskKind.REASONING).content.lowercase()
        assertTrue(content.contains("do not write code"))
        assertFalse(content.contains("write working code"))
    }

    @Test
    fun `execution WRITING and EXPLANATION prompts forbid code`() {
        val writing = StagePromptTemplates.buildSystemMessage(TaskStage.EXECUTION, TaskKind.WRITING).content.lowercase()
        val explanation = StagePromptTemplates.buildSystemMessage(TaskStage.EXECUTION, TaskKind.EXPLANATION).content.lowercase()
        assertTrue(writing.contains("do not write code"))
        assertTrue(explanation.contains("do not write code"))
    }

    @Test
    fun `execution null prompt is universal and lets LLM decide on code`() {
        // null (тип неизвестен / сбой классификации) → универсальный промпт: код если программная
        val content = StagePromptTemplates.buildSystemMessage(TaskStage.EXECUTION, null).content.lowercase()
        assertTrue(content.contains("if the task is programming"))
        assertFalse(content.contains("do not write code"))
    }

    @Test
    fun `taskKind is ignored for non-execution stages`() {
        // Для остальных стадий taskKind не меняет промпт
        TaskStage.entries.filter { it != TaskStage.EXECUTION }.forEach { stage ->
            val byKind = StagePromptTemplates.buildSystemMessage(stage, TaskKind.CODE).content
            val without = StagePromptTemplates.buildSystemMessage(stage, null).content
            assertEquals(without, byKind, "taskKind leaked into $stage prompt")
        }
    }
}
