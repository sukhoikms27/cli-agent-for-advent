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
    EXPLANATION,

    /**
     * День 34 (stage/swarm интеграция): операции с файлами проекта.
     *
     * Агент работает через file-tools (read_file/find_in_files/list_project_files/write_file)
     * на стадии EXECUTION. На swarm'ится (lead→workers→integrate): lead декомпозирует цель на
     * file-операции, workers параллельно читают/ищут, integrate собирает отчёт/предложения.
     * Routing: taskKindClassifier распознаёт по формулировкам «обнови README», «найди все TODO»,
     * «проверь соответствие файлов», и т.п.
     */
    FILE_OP,

    /**
     * День 34 (stage/swarm интеграция): ревью изменений (diff/git).
     *
     * На VALIDATION запускается [com.cliagent.agent.stage.ReviewValidationAgent] — single-pass
     * анализ diff'а против стандартов проекта (RAG + SystemPrompts.codeReviewer). Артефакт verdict
     * = markdown-отчёт. Routing: «проанализируй diff», «отревьюй PR», «проверь изменения».
     */
    PR_REVIEW,
}
