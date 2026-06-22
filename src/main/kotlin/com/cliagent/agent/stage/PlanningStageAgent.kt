package com.cliagent.agent.stage

import com.cliagent.llm.token.ArtifactLimits
import com.cliagent.llm.token.truncateToTokens
import com.cliagent.state.TaskStage

/**
 * Стадия PLANNING (день 13) — построение плана.
 *
 * User-сообщение = описание задачи + уточнённые требования (из CLARIFY) + опц. feedback
 * (уточнение плана при перегенерации). Ответ модели целиком = артефакт `approvedPlan`
 * (готов для парсинга [PlanParser] на шаги в execution).
 */
class PlanningStageAgent : StageAgent {
    override val stage: TaskStage = TaskStage.PLANNING

    override suspend fun run(ctx: StageContext, chat: suspend (String) -> String): StageResult {
        val message = buildString {
            append("Задача: ").append(ctx.taskDescription)
            ctx.requirements?.let {
                append("\n\nТребования:\n").append(truncateToTokens(it, ArtifactLimits.REQUIREMENTS_TOKENS))
            }
            ctx.feedback?.let {
                append("\n\nУточнение к плану (учти при переработке):\n")
                    .append(truncateToTokens(it, ArtifactLimits.FEEDBACK_TOKENS))
            }
            ctx.profileBlock?.let { append("\n\n").append(it) }
            append("\n\nПострой конкретный пошаговый план выполнения задачи. ")
                .append("Не пиши реализацию (код) — только шаги. ")
                .append("Формат: нумерованный список (1) ... 2) ...). ")
                .append("Каждый шаг — атомарное действие, готовое для исполнения на стадии execution.")
        }

        val plan = chat(message).trim()
        return StageResult(
            artifact = plan,
            display = "📋 План:\n\n$plan",
            readyToAdvance = !plan.isBlank()
        )
    }
}
