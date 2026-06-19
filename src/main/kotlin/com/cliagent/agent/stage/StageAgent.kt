package com.cliagent.agent.stage

import com.cliagent.memory.UserProfile
import com.cliagent.state.TaskStage

/**
 * Стадийный агент (доработка Day 13 — «отдельный агент на каждую стадию», лекция недели 3 §9.5).
 *
 * Один `StageAgent` = одна стадия FSM (clarify / planning / execution / validation / done).
 * Сам в LLM не ходит — готовит user-сообщение, делегирует LLM-вызов через [chat] (это
 * `ContextAwareAgent.chat()`, который уже подставляет stage system-промпт через
 * [com.cliagent.llm.model.StagePromptTemplates], ведёт token tracking, историю, профиль и
 * context-стратегию). Затем интерпретирует ответ в [StageResult].
 *
 * Оркестрируется [TaskOrchestrator]: он создаёт [StageContext] из текущего [com.cliagent.state.TaskState]
 * и кладёт артефакт обратно в нужное поле состояния.
 */
interface StageAgent {
    val stage: TaskStage

    /**
     * @param ctx   вход стадии: описание задачи, артефакты предыдущих стадий, опц. feedback
     *              (текст-уточнение от пользователя при перегенерации артефакта)
     * @param chat  делегат LLM-вызова: user-сообщение → ассистент-ответ (через ContextAwareAgent)
     * @return артефакт стадии + отображаемый пользователю текст + готовность к переходу
     */
    suspend fun run(ctx: StageContext, chat: suspend (String) -> String): StageResult
}

/**
 * Входные данные для запуска [StageAgent]. Собирается [TaskOrchestrator] из TaskState.
 *
 * @param taskDescription  исходное описание задачи (из `/task start` / currentStep)
 * @param requirements     артефакт CLARIFY (сводка уточнённых требований) — кормит PLANNING
 * @param approvedPlan     артефакт PLANNING — кормит EXECUTION/VALIDATION
 * @param implementation   артефакт EXECUTION — кормит VALIDATION/DONE
 * @param verdict          артефакт VALIDATION — кормит DONE
 * @param profile          профиль пользователя (style/constraints) — контекст для всех стадий
 * @param feedback         текст-уточнение от пользователя (перегенерация артефакта); null при первом запуске
 */
data class StageContext(
    val taskDescription: String,
    val requirements: String? = null,
    val approvedPlan: String? = null,
    val implementation: String? = null,
    val verdict: String? = null,
    val profile: UserProfile? = null,
    val feedback: String? = null
) {
    /** Профиль в виде блока для промпта (constraints/style), если задан и не пуст. */
    val profileBlock: String?
        get() = profile?.takeIf { !it.isEmpty() }?.renderPromptBlock()
}

/**
 * Результат работы [StageAgent].
 *
 * @param artifact        артефакт стадии (план/реализация/вердикт/требования); null — нет артефакта
 * @param display         текст, показываемый пользователю (обычно == артефакту, но clarify выводит вопросы)
 * @param readyToAdvance  готов ли артефакт к переходу на следующую стадию (false — ждём доработки/ответов)
 */
data class StageResult(
    val artifact: String?,
    val display: String,
    val readyToAdvance: Boolean = true
)

/** Рендер профиля в секцию промпта (constraints акцентированы — это инварианты). */
private fun UserProfile.renderPromptBlock(): String = buildString {
    append("[User profile]")
    style?.let { append("\nStyle: $it") }
    about?.let { append("\nAbout: $it") }
    if (constraints.isNotEmpty()) {
        append("\nConstraints (invariants — must respect):")
        constraints.forEach { append("\n  - $it") }
    }
}.trim()
