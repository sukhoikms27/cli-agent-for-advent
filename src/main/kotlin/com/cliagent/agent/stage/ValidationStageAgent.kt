package com.cliagent.agent.stage

import com.cliagent.llm.token.ArtifactLimits
import com.cliagent.llm.token.truncateToTokens
import com.cliagent.state.TaskStage

/**
 * Стадия VALIDATION (день 13) — проверка результата против плана и ограничений.
 *
 * Промпт обязывает модель закончить ответ вердиктом `PASS` (всё ок, можно закрывать) или
 * `REWORK` (найдены дефекты, нужна доработка). Артефакт = `verdict` (весь ответ с вердиктом).
 *
 * `readyToAdvance`: PASS → true (переходим в done); REWORK → false (пользователь увидит проблемы,
 * escape hatch — `/task set execution` для доработки, либо уточнить реализацию текстом).
 */
class ValidationStageAgent : StageAgent {
    override val stage: TaskStage = TaskStage.VALIDATION

    override suspend fun run(ctx: StageContext, chat: suspend (String) -> String): StageResult {
        val message = buildString {
            append("Задача: ").append(ctx.taskDescription)
            append("\n\nУтверждённый план:\n")
                .append(ctx.approvedPlan?.let { truncateToTokens(it, ArtifactLimits.PLAN_TOKENS) } ?: "(план не задан)")
            append("\n\nРеализация:\n")
                .append(ctx.implementation?.let { truncateToTokens(it, ArtifactLimits.IMPLEMENTATION_TOKENS) } ?: "(нет реализации)")
            ctx.feedback?.let {
                append("\n\nОтзыв при перепроверке:\n").append(truncateToTokens(it, ArtifactLimits.FEEDBACK_TOKENS))
            }
            ctx.profileBlock?.let { append("\n\n").append(it) }
            append("\n\nПроверь реализацию против плана и ограничений. Найди дефекты, ")
                .append("несоответствия, пропущенные шаги. НЕ добавляй новый функционал — ")
                .append("только проверка. Закончи ответ строкой PASS (всё ок) или REWORK (есть проблемы, ")
                .append("перечисли их).")
        }

        val response = chat(message).trim()
        val hasRework = response.contains("REWORK", ignoreCase = true)
        val hasPass = response.contains("PASS", ignoreCase = true)
        // REWORK перевешивает (безопаснее): если упомянуты оба — нужна доработка.
        // Ни одного маркера — тоже не готов (модель не дала вердикт).
        val passed = hasPass && !hasRework

        // Артефакт: текст проверки + канонизированный вердикт в конце
        val body = response
            .replace(Regex("(?i)\\bPASS\\b"), "").replace(Regex("(?i)\\bREWORK\\b"), "")
            .trim().trimEnd(':', '-', '*').trim()
        val verdict = if (body.isBlank()) {
            if (passed) "Вердикт: PASS" else "Вердикт: REWORK"
        } else {
            "$body\n\nВердикт: ${if (passed) "PASS" else "REWORK"}"
        }

        return StageResult(
            artifact = verdict,
            display = if (passed) "✅ Проверка:\n\n$verdict" else "🟡 Проверка (найдены проблемы):\n\n$verdict",
            readyToAdvance = passed
        )
    }
}
