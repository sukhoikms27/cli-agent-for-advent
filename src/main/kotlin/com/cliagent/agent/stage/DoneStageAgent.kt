package com.cliagent.agent.stage

import com.cliagent.llm.token.ArtifactLimits
import com.cliagent.llm.token.truncateToTokens
import com.cliagent.state.TaskStage

/**
 * Стадия DONE (день 13) — итог задачи.
 *
 * Собирает summary из артефактов (план/реализация/вердикт). `readyToAdvance = false` —
 * дальше идти некуда (DONE — терминальная стадия FSM).
 */
class DoneStageAgent : StageAgent {
    override val stage: TaskStage = TaskStage.DONE

    override suspend fun run(ctx: StageContext, chat: suspend (String) -> String): StageResult {
        val message = buildString {
            append("Задача: ").append(ctx.taskDescription)
            append("\n\nПлан:\n")
                .append(ctx.approvedPlan?.let { truncateToTokens(it, ArtifactLimits.DONE_SUMMARY_INPUT) } ?: "(нет)")
            append("\n\nРеализация:\n")
                .append(ctx.implementation?.let { truncateToTokens(it, ArtifactLimits.DONE_SUMMARY_INPUT) } ?: "(нет)")
            append("\n\nВердикт:\n")
                .append(ctx.verdict?.let { truncateToTokens(it, ArtifactLimits.VERDICT_TOKENS) } ?: "(нет)")
            append("\n\nПодведи итог: что сделано, ключевые решения, результат. ")
                .append("Не начинай новую работу.")
        }

        val summary = chat(message).trim()
        return StageResult(
            artifact = summary,
            display = "🏁 Задача завершена:\n\n${summary.ifBlank { "Итог недоступен." }}",
            readyToAdvance = false   // терминальная стадия
        )
    }
}
