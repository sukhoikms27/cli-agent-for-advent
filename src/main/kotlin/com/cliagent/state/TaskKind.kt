package com.cliagent.state

import kotlinx.serialization.Serializable

/**
 * Тип задачи (день 15, фикс #1 — гибрид: смягчение промптов + taskKind-флаг).
 *
 * Определяется [com.cliagent.agent.stage.TaskKindClassifier] на старте задачи и хранится в
 * [TaskState.taskKind]. Кормит стадию EXECUTION: для [CODE] агент пишет код, для остальных —
 * ответ/решение/рассуждение/текст. `null` (не удалось классифицировать / старый чат) →
 * универсальный смягщённый промпт: LLM сама решает, нужен ли код.
 *
 * `@Serializable` + AppJson (`coerceInputValues`, `ignoreUnknownKeys`) → старые чаты без поля
 * грузятся как null без миграций.
 */
@Serializable
enum class TaskKind {
    /** Программная задача — на execution агент пишет рабочий код. */
    CODE,

    /** Логическая/аналитическая задача — решение, рассуждение, вывод; код не нужен. */
    REASONING,

    /** Текстовая задача — документ/текст/письмо; код не нужен. */
    WRITING,

    /** Объяснение концепции/теории; код не нужен. */
    EXPLANATION
}
