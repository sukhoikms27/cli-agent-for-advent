package com.cliagent.agent

import com.cliagent.llm.model.ChatMessage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory

/**
 * Сборка слоёного system prompt (день 11 — prompt builder из лекции недели 3).
 *
 * Компонует базовый system-промпт с блоками долговременной и рабочей памяти.
 * Пустые слои элизируются → при отсутствии памяти контент system-сообщения
 * байт-идентичен дням 1–10 (поведение не меняется).
 *
 * Порядок блоков: base → long-term → working
 * (долговременный контекст «весомее», рабочая задача — ближе к запросу).
 *
 * Точки расширения:
 *  - Day 12: [LongTermMemory.profile] рендерится автоматически (см. [UserProfile.renderBlock]).
 *  - Day 13: [WorkingMemory] получит поле `taskState`, рендерится в [WorkingMemory.renderBlock].
 */
class PromptBuilder(
    private val baseSystem: ChatMessage,
    private val longTerm: LongTermMemory?,
    private val working: WorkingMemory?
) {
    fun build(): ChatMessage {
        val parts = mutableListOf(baseSystem.content)
        longTerm?.takeIf { !it.isEmpty() }?.let { parts.add(it.renderBlock()) }
        working?.takeIf { !it.isEmpty() }?.let { parts.add(it.renderBlock()) }
        // Все слои пусты → parts == [baseSystem.content] → контент неизменен
        return baseSystem.copy(content = parts.joinToString("\n\n"))
    }
}

/** Секция долговременной памяти: knowledge, decisions, profile. */
internal fun LongTermMemory.renderBlock(): String {
    val lines = mutableListOf<String>()
    lines.add("[Long-term memory]")
    if (knowledge.isNotEmpty()) {
        lines.add("Knowledge:")
        knowledge.forEach { (k, v) -> lines.add("  - $k: $v") }
    }
    if (decisions.isNotEmpty()) {
        lines.add("Decisions:")
        decisions.forEach { (k, v) -> lines.add("  - $k: $v") }
    }
    profile?.takeIf { !it.isEmpty() }?.let { lines.add(it.renderBlock()) }
    return lines.joinToString("\n")
}

/** Секция рабочей памяти: данные текущей задачи. */
internal fun WorkingMemory.renderBlock(): String {
    val lines = mutableListOf<String>()
    lines.add("[Working memory — current task]")
    currentTask?.let { lines.add("Task: $it") }
    plan?.let { lines.add("Plan: $it") }
    scratchNotes?.let { lines.add("Notes: $it") }
    if (taskDecisions.isNotEmpty()) {
        lines.add("Decisions:")
        taskDecisions.forEach { lines.add("  - $it") }
    }
    return lines.joinToString("\n")
}

/** Секция профиля пользователя (Day 12 наполняет, Day 11 уже рендерит). */
internal fun UserProfile.renderBlock(): String {
    val lines = mutableListOf<String>()
    lines.add("User profile:")
    style?.let { lines.add("  Style: $it") }
    format?.let { lines.add("  Format: $it") }
    if (constraints.isNotEmpty()) {
        lines.add("  Constraints:")
        constraints.forEach { lines.add("    - $it") }
    }
    return lines.joinToString("\n")
}
