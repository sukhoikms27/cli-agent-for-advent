package com.cliagent.agent.stage

import com.cliagent.state.TaskStage

/**
 * Стадия CLARIFY (день 13) — уточнение требований.
 *
 * Задаёт сфокусированные вопросы о двусмысленностях, scope, ограничениях и критериях успеха.
 * Промпт обязывает модель начинать ответ с маркера решения:
 *  - `[CLEAR]` — требования достаточно ясны, можно планировать. Артефакт = сводка уточнённых
 *    требований (requirements), `readyToAdvance = true`.
 *  - `[ASK]` — есть неоднозначности. `readyToAdvance = false`, display = вопросы пользователю.
 *
 * Цикл: оркестратор повторно запускает агент с ответами пользователя в [StageContext.feedback],
 * пока не получит `[CLEAR]`.
 */
class ClarifyStageAgent : StageAgent {
    override val stage: TaskStage = TaskStage.CLARIFY

    override suspend fun run(ctx: StageContext, chat: suspend (String) -> String): StageResult {
        val message = buildString {
            append("Задача: ").append(ctx.taskDescription)
            ctx.requirements?.let { append("\n\nУже уточнено:\n").append(it) }
            ctx.feedback?.let { append("\n\nОтветы пользователя:\n").append(it) }
            ctx.profileBlock?.let { append("\n\n").append(it) }
            append("\n\nПроанализируй задачу. Если требований достаточно для построения плана, ")
                .append("начни ответ со слова [CLEAR] и затем дай краткую сводку уточнённых требований ")
                .append("(стек, scope, критерии, ограничения). ")
                .append("Если есть неоднозначности, начни со слова [ASK] и задай 1-3 сфокусированных вопроса.")
        }

        val response = chat(message)
        val trimmed = response.trim()
        return when {
            trimmed.startsWith(CLEAR_MARKER, ignoreCase = true) -> {
                val summary = trimmed.removePrefixIgnoreCase(CLEAR_MARKER).trim()
                    .ifBlank { ctx.taskDescription }
                StageResult(
                    artifact = summary,                 // requirements — кормит PLANNING
                    display = "✓ Требования собраны:\n\n$summary",
                    readyToAdvance = true
                )
            }
            trimmed.startsWith(ASK_MARKER, ignoreCase = true) -> {
                val questions = trimmed.removePrefixIgnoreCase(ASK_MARKER).trim().ifBlank { trimmed }
                StageResult(
                    artifact = null,
                    display = "❓ Вопросы для уточнения:\n\n$questions",
                    readyToAdvance = false
                )
            }
            else -> {
                // Модель не поставила маркер — трактуем как вопросы (безопасно: уточняем ещё раз)
                StageResult(
                    artifact = null,
                    display = "❓ Уточни, пожалуйста:\n\n$trimmed",
                    readyToAdvance = false
                )
            }
        }
    }

    private companion object {
        const val CLEAR_MARKER = "[CLEAR]"
        const val ASK_MARKER = "[ASK]"
    }
}

/** Case-insensitive removePrefix (Kotlin's removePrefix is case-sensitive). */
private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (this.length >= prefix.length &&
        this.substring(0, prefix.length).equals(prefix, ignoreCase = true)
    ) this.substring(prefix.length) else this
