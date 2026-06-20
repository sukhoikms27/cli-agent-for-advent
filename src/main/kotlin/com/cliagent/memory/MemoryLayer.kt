package com.cliagent.memory

import com.cliagent.state.TaskState
import com.cliagent.state.invariant.Invariant
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
 * [taskState] — состояние задачи как конечный автомат (день 13): этап/шаг/ожидаемое действие.
 * Живёт здесь (per-chat), персистится и очищается вместе с рабочей памятью.
 */
@Serializable
data class WorkingMemory(
    val currentTask: String? = null,
    val plan: String? = null,
    val scratchNotes: String? = null,
    val taskDecisions: List<String> = emptyList(),
    val taskState: TaskState? = null   // день 13: состояние задачи (FSM)
) {
    fun isEmpty(): Boolean =
        currentTask == null && plan == null && scratchNotes == null && taskDecisions.isEmpty() &&
            taskState == null
}

/**
 * Профиль пользователя — персонализация (день 12).
 * Живёт внутри [LongTermMemory.profile], рендерится в system prompt каждого запроса
 * через [com.cliagent.agent.PromptBuilder]. Три группы данных (лекция недели 3):
 * стиль, ограничения (constraints), контекст (about — кто пользователь, цель).
 */
@Serializable
data class UserProfile(
    val style: String? = null,
    val format: String? = null,
    val about: String? = null,        // контекст: кто пользователь, цель
    val constraints: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        style == null && format == null && about == null && constraints.isEmpty()
}

/**
 * Долговременная память - global, кросс-чат/кросс-сессия.
 * Хранится в отдельном файле [com.cliagent.config.AppPaths.longTermFile].
 *
 * [profile] заполняется в Day 12; [knowledge]/[decisions] - с дня 11; [invariants] - с дня 14
 * (жёсткие правила проекта, отдельные от диалога и от профиля; переживают restart).
 */
@Serializable
data class LongTermMemory(
    val knowledge: Map<String, String> = emptyMap(),
    val decisions: Map<String, String> = emptyMap(),
    val profile: UserProfile? = null,
    val invariants: List<Invariant> = emptyList()   // день 14: инварианты проекта (hard rules)
) {
    fun isEmpty(): Boolean =
        knowledge.isEmpty() && decisions.isEmpty() &&
            (profile == null || profile.isEmpty()) && invariants.isEmpty()
}
