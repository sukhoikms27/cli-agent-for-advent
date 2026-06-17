package com.cliagent.memory

import kotlinx.serialization.Serializable

/**
 * Три слоя памяти ассистента (день 11 — модель памяти).
 *
 * - [SHORT_TERM]  — краткосрочная: текущий диалог (ChatData.messages + context-стратегии).
 * - [WORKING]     — рабочая: данные текущей задачи (task, plan, notes, decisions).
 * - [LONG_TERM]   — долговременная: профиль, решения, знания (кросс-чат/кросс-сессия).
 */
enum class MemoryLayer { SHORT_TERM, WORKING, LONG_TERM }

/**
 * Рабочая память — per-chat, данные текущей задачи.
 * Хранится в [ChatData.workingMemory], сбрасывается при /reset.
 *
 * Точка расширения Day 13: `val taskState: TaskState? = null` (добавить с default).
 */
@Serializable
data class WorkingMemory(
    val currentTask: String? = null,
    val plan: String? = null,
    val scratchNotes: String? = null,
    val taskDecisions: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        currentTask == null && plan == null && scratchNotes == null && taskDecisions.isEmpty()
}

/**
 * Профиль пользователя — stub под Day 12 (персонализация).
 * Живёт внутри [LongTermMemory.profile], подключается к каждому запросу.
 */
@Serializable
data class UserProfile(
    val style: String? = null,
    val format: String? = null,
    val constraints: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        style == null && format == null && constraints.isEmpty()
}

/**
 * Долговременная память — global, кросс-чат/кросс-сессия.
 * Хранится в отдельном файле [com.cliagent.config.AppPaths.longTermFile].
 *
 * [profile] заполняется в Day 12; [knowledge]/[decisions] — с дня 11.
 */
@Serializable
data class LongTermMemory(
    val knowledge: Map<String, String> = emptyMap(),
    val decisions: Map<String, String> = emptyMap(),
    val profile: UserProfile? = null
) {
    fun isEmpty(): Boolean =
        knowledge.isEmpty() && decisions.isEmpty() && (profile == null || profile.isEmpty())
}
