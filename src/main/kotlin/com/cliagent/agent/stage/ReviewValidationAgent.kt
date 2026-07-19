package com.cliagent.agent.stage

import com.cliagent.llm.token.ArtifactLimits
import com.cliagent.llm.token.truncateToTokens
import com.cliagent.state.TaskStage

/**
 * День 34 (stage/swarm интеграция): StageAgent для задачи [com.cliagent.state.TaskKind.PR_REVIEW]
 * на стадии VALIDATION.
 *
 * В отличие от [ValidationStageAgent] (универсальная проверка против плана), этот агент
 * специализируется на **ревью изменений**: он принимает diff/implementation из EXECUTION-стадии
 * и выдаёт структурированный markdown-отчёт по образцу [com.cliagent.llm.model.SystemPrompts.codeReviewer]
 * (баги / архитектура / рекомендации). Системный промпт `codeReviewer` подставляется
 * автоматически в [com.cliagent.agent.ContextAwareAgent.buildMessagesToSend] через
 * [com.cliagent.llm.model.StagePromptTemplates.buildSystemMessage].
 *
 * ## Single-pass
 * Review — это единый аналитический акт, не требует распараллеливания (в отличие от file-задач,
 * которые swarm'ятся). Поэтому не [com.cliagent.agent.swarm.SwarmStageAgent], а прямая реализация.
 *
 * ## Артефакт
 * Полный review-отчёт сохраняется в `TaskState.verdict` (как и у универсального ValidationStageAgent).
 * Маркеры PASS/REWORK не требуются — review-отчёт сам по себе артефакт, всегда `readyToAdvance=true`
 * (пользователь решает, accept PR или нет, на основе отчёта).
 *
 * ## Источник diff'а
 * - В /task-флоу: EXECUTION-стадия (для PR_REVIEW) собирает diff через git-tools и кладёт его в
 *   `ctx.implementation`.
 * - В CLI ReviewPrCommand (CI): diff загружается напрямую и подставляется в `ctx.implementation`
 *   через [com.cliagent.agent.stage.TaskOrchestrator.runOneStage] (как обычный EXECUTION-артефакт).
 */
class ReviewValidationAgent : StageAgent {
    override val stage: TaskStage = TaskStage.VALIDATION

    override suspend fun run(ctx: StageContext, chat: suspend (String) -> String): StageResult {
        val diff = ctx.implementation?.takeIf { it.isNotBlank() }
            ?: ctx.taskDescription.takeIf { it.isNotBlank() }
            ?: return StageResult(
                artifact = null,
                display = "⚠️ Нет diff/implementation для ревью. EXECUTION-стадия должна собрать изменения.",
                readyToAdvance = false,
            )

        val message = buildString {
            appendLine("Проанализируй следующие изменения и дай структурированное ревью.")
            appendLine()
            if (!ctx.approvedPlan.isNullOrBlank()) {
                appendLine("Контекст (что планировалось):")
                appendLine(truncateToTokens(ctx.approvedPlan, ArtifactLimits.PLAN_TOKENS))
                appendLine()
            }
            appendLine("Изменения для ревью:")
            appendLine("```diff")
            appendLine(truncateToTokens(diff, ArtifactLimits.IMPLEMENTATION_TOKENS))
            appendLine("```")
            ctx.feedback?.let {
                appendLine()
                appendLine("Дополнительный фокус ревьюера:")
                appendLine(truncateToTokens(it, ArtifactLimits.FEEDBACK_TOKENS))
            }
            ctx.profileBlock?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine("Дай отчёт по образцу: ⚠️ Потенциальные баги / 🏗 Архитектура / 💡 Рекомендации. " +
                "Сошлись на конкретные строки diff'а. Не добавляй ничего вне анализа изменений.")
        }

        val report = chat(message).trim()
        return StageResult(
            artifact = report,
            display = "📋 Review-отчёт:\n\n$report",
            readyToAdvance = report.isNotBlank(),
        )
    }
}
