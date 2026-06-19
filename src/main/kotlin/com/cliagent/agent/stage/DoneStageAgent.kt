package com.cliagent.agent.stage

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
            append("\n\nПлан:\n").append(ctx.approvedPlan?.take(MAX_CHARS) ?: "(нет)")
            append("\n\nРеализация:\n").append(ctx.implementation?.take(MAX_CHARS) ?: "(нет)")
            append("\n\nВердикт:\n").append(ctx.verdict ?: "(нет)")
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

    private companion object {
        const val MAX_CHARS = 4000
    }
}
