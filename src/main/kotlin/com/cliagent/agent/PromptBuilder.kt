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
 *  - Day 13: [WorkingMemory.taskState] рендерится в [WorkingMemory.renderBlock] (блок Task state).
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
        longTerm?.renderInvariantsBlock()?.let { parts.add(it) }   // день 14: блок инвариантов
        // Все слои пусты  parts == [baseSystem.content]  контент неизменен
        return baseSystem.copy(content = parts.joinToString("\n\n"))
    }
}

/** Секция долговременной памяти: knowledge, decisions, profile, invariants. */
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

/**
 * Секция инвариантов проекта (день 14): жёсткие правила, которые ассистент не имеет права
 * нарушать (defense-in-depth слой 1 — в промпте; слой 2 — программная проверка через
 * [com.cliagent.agent.InvariantGuard]). Рендерится отдельно от секции long-term memory, чтобы
 * акцентировать внимание модели (заголовок «MUST NOT violate»).
 *
 * Порядок блоков в system prompt: base → long-term → working → project invariants
 * (инварианты последними — recency для модели).
 */
internal fun LongTermMemory.renderInvariantsBlock(): String? =
    invariants.takeIf { it.isNotEmpty() }?.let { list ->
        buildString {
            append("[Project invariants — you MUST NOT propose solutions that violate these]")
            list.forEach { iv ->
                append("\n  - [${iv.id}] ${iv.rule}  (${iv.category.name.lowercase()})")
            }
        }
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
    taskState?.let { ts ->
        lines.add("Task state:")
        lines.add("  Stage: ${ts.stage.name.lowercase()}")
        ts.currentStep?.let { lines.add("  Current step: $it") }
        ts.expectedAction?.let { lines.add("  Expected action: $it") }
        ts.approvedPlan?.let { lines.add("  Approved plan: $it") }
        ts.implementation?.let { lines.add("  Implementation: $it") }
        ts.verdict?.let { lines.add("  Verdict: $it") }
    }
    return lines.joinToString("\n")
}

/** Секция профиля пользователя (Day 12 наполняет, Day 11 уже рендерит). */
internal fun UserProfile.renderBlock(): String {
    val lines = mutableListOf<String>()
    lines.add("User profile:")
    style?.let { lines.add("  Style: $it") }
    format?.let { lines.add("  Format: $it") }
    about?.let { lines.add("  About: $it") }
    if (constraints.isNotEmpty()) {
        lines.add("  Constraints:")
        constraints.forEach { lines.add("    - $it") }
    }
    return lines.joinToString("\n")
}
