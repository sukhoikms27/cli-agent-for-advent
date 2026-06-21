package com.cliagent.agent.stage

import com.cliagent.state.TaskKind
import com.cliagent.state.TaskStage

/**
 * Стадия EXECUTION (день 13) — реализация по утверждённому плану.
 *
 * Гибрид-режим (выбор пользователя «отдельный агент на каждый пункт плана»): план разбирается
 * [PlanParser]'ом на шаги, и на каждый шаг запускается [StepAgent]. Результаты агрегируются
 * в артефакт `implementation` (готовый код/решения по шагам).
 *
 * Если план не распался на шаги (prose) — выполняется целиком одним шагом ([PlanParser.parseOrWhole]).
 * [StageContext.feedback] — уточнение реализации при перегенерации (добавляется в общий промпт).
 *
 * День 15 (фикс #1): [StageContext.taskKind] пробрасывается в [StepAgent] и в прямые промпты —
 * для некодовых задач агент даёт ответ/решение, а не код.
 */
class ExecutionStageAgent(
    private val stepAgent: StepAgent = StepAgent()
) : StageAgent {
    override val stage: TaskStage = TaskStage.EXECUTION

    override suspend fun run(ctx: StageContext, chat: suspend (String) -> String): StageResult {
        val plan = ctx.approvedPlan ?: ctx.taskDescription
        val steps = PlanParser.parseOrWhole(plan)

        if (steps.isEmpty()) {
            // План пуст/blanc — нечего выполнять
            val msg = chat(noPlanMessage(ctx))?.trim().orEmpty()
            return StageResult(
                artifact = msg.ifBlank { null },
                display = "⚠️ План пуст. Реализация:\n\n${msg.ifBlank { "(нет вывода)" }}",
                readyToAdvance = msg.isNotBlank()
            )
        }

        val done = mutableListOf<String>()
        val parts = StringBuilder()
        steps.forEachIndexed { index, step ->
            parts.append("### Шаг ${index + 1}/${steps.size}: $step\n\n")
            // На перегенерации (feedback) — перезапускаем все шаги с чистого листа;
            // feedback добавляется в первый шаг как уточнение.
            val stepResult = stepAgent.run(
                step = step,
                plan = plan,
                doneSteps = done,
                taskDescription = ctx.taskDescription,
                profileBlock = ctx.profileBlock,
                taskKind = ctx.taskKind,
                chat = chat
            )
            parts.append(stepResult).append("\n\n")
            done.add(step)
        }

        // feedback-уточнение: прогон завершён, но пользователь просил поправить —
        // докручиваем финальное сообщение-уточнение поверх агрегата.
        val implementation = if (ctx.feedback != null) {
            val refine = chat(buildRefinePrompt(ctx.feedback, parts.toString(), ctx.profileBlock)).trim()
            "$parts\n---\n[Уточнение по отзыву: ${ctx.feedback}]\n\n$refine".trim()
        } else {
            parts.toString().trim()
        }

        return StageResult(
            artifact = implementation,
            display = "⚙️ Реализация:\n\n$implementation",
            readyToAdvance = implementation.isNotBlank()
        )
    }

    private fun noPlanMessage(ctx: StageContext): String = buildString {
        append("Задача: ").append(ctx.taskDescription)
        append("\n\nПлан не задан. ").append(directInstruction(ctx.taskKind))
        ctx.profileBlock?.let { append("\n\n").append(it) }
    }

    private fun buildRefinePrompt(feedback: String, currentImpl: String, profileBlock: String?): String = buildString {
        append("Текущая реализация:\n").append(currentImpl.take(MAX_IMPL_CHARS))
        append("\n\nОтзыв/уточнение пользователя:\n").append(feedback)
        profileBlock?.let { append("\n\n").append(it) }
        append("\n\nДай уточнённую реализацию с учётом отзыва.")
    }

    /** Инструкция «напрямую без плана» по типу задачи (фикс #1). */
    private fun directInstruction(taskKind: TaskKind?): String = when (taskKind) {
        TaskKind.CODE -> "Реализуй задачу напрямую, шаг за шагом, с кодом."
        TaskKind.REASONING -> "Реши задачу напрямую, шаг за шагом, с рассуждением и итоговым ответом. Не пиши код, если он не требуется."
        TaskKind.WRITING -> "Напиши требуемый текст напрямую, шаг за шагом. Не пиши код."
        TaskKind.EXPLANATION -> "Объясни тему напрямую, шаг за шагом. Не пиши код, если он не требуется."
        null -> "Выполни задачу напрямую, шаг за шагом: код, если задача программная, иначе ответ/решение/пояснение."
    }

    private companion object {
        const val MAX_IMPL_CHARS = 6000
    }
}
