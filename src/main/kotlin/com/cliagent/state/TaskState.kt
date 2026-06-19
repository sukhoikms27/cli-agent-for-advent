package com.cliagent.state

import kotlinx.serialization.Serializable

/**
 * Запись о переходе между стадиями (день 13).
 * Хранится в [TaskState.stageHistory] — лента переходов, по которой `/task back` откатывает стадию.
 *
 * @param from исходная стадия
 * @param to   целевая стадия
 * @param at   ISO-метка момента перехода
 * @param note опц. пометка (например, "forced" для принудительного `/task set`)
 */
@Serializable
data class StageTransition(
    val from: TaskStage,
    val to: TaskStage,
    val at: String,
    val note: String? = null
)

/**
 * Состояние текущей задачи (день 13 — task state machine).
 *
 * Три элемента задания курса: этап ([stage]), текущий шаг ([currentStep]),
 * ожидаемое действие ([expectedAction]). Дополнительно: артефакты стадий ([approvedPlan] —
 * planning, [implementation] — execution, [verdict] — validation; доработка по Варианту 2 автора
 * курса: переход вперёд разрешён только когда артефакт стадии готов) и история переходов
 * ([stageHistory]).
 *
 * Автоматизация стадийного потока (доработка Day 13): [awaitingAdvance] — артефакт стадии
 * готов, агент ждёт подтверждения пользователя («да» → переход, иной текст → уточнение артефакта
 * через feedback). [requirements] — артефакт CLARIFY (сводка уточнённых требований), кормит
 * PLANNING. Управляется [com.cliagent.agent.stage.TaskOrchestrator].
 *
 * Живёт внутри [com.cliagent.memory.WorkingMemory.taskState] (per-chat, персистится в JSON чата,
 * очищается при `/reset`). «Нет активной задачи» = `taskState == null` в WorkingMemory
 * (не отдельный флаг). Default `stage = PLANNING` — канонический старт `/task start`.
 *
 * `@Serializable` + AppJson (`ignoreUnknownKeys`, `explicitNulls=false`) → старые чаты без
 * `taskState` (или без `implementation`/`verdict`/`awaitingAdvance`/`requirements`) грузятся
 * как null/false, без миграций.
 */
@Serializable
data class TaskState(
    val stage: TaskStage = TaskStage.PLANNING,
    val currentStep: String? = null,
    val expectedAction: String? = null,
    val approvedPlan: String? = null,       // артефакт planning
    val implementation: String? = null,     // артефакт execution (доработка Day 13)
    val verdict: String? = null,            // артефакт validation (доработка Day 13)
    val stageHistory: List<StageTransition> = emptyList(),
    val awaitingAdvance: Boolean = false,   // ждёт подтверждения перехода (авто-поток, Day 13)
    val requirements: String? = null        // артефакт clarify (сводка требований, Day 13)
)
