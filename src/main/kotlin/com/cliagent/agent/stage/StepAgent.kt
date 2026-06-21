package com.cliagent.agent.stage

import com.cliagent.state.TaskKind

/**
 * Исполнитель одного шага плана (гибрид execution — отдельный агент на каждый пункт плана).
 *
 * Промпт сфокусирован на одном шаге, с учётом уже сделанных шагов (продолжение контекста):
 * модель не перепланирует и не делает чужие шаги. Возвращает код/результат шага.
 *
 * День 15 (фикс #1): финальная инструкция ветвится по [taskKind] — для [TaskKind.CODE] просим код,
 * для остальных — конкретный ответ/решение/результат без кода. `null` → универсальная формулировка
 * (LLM сама решает, нужен ли код).
 *
 * Не [StageAgent] — это вложенный исполнитель внутри [ExecutionStageAgent], а не стадия FSM.
 */
class StepAgent {

    /**
     * @param chat делегат LLM-вызова (ContextAwareAgent.chat — на стадии EXECUTION)
     */
    suspend fun run(
        step: String,
        plan: String,
        doneSteps: List<String>,
        taskDescription: String,
        profileBlock: String?,
        taskKind: TaskKind? = null,
        chat: suspend (String) -> String
    ): String {
        val message = buildString {
            append("Задача: ").append(taskDescription)
            append("\n\nПолный план:\n").append(plan)
            append("\n\nТекущий шаг (реализуй ТОЛЬКО его, не делай чужие шаги):\n").append(step)
            if (doneSteps.isNotEmpty()) {
                append("\n\nУже сделанные шаги (контекст, не повторяй):\n")
                doneSteps.forEachIndexed { i, s -> append("${i + 1}) $s\n") }
            }
            profileBlock?.let { append("\n").append(it) }
            append("\n\n").append(stepInstruction(taskKind))
        }
        return chat(message).trim()
    }

    /** Финальная инструкция шага по типу задачи (фикс #1). */
    private fun stepInstruction(taskKind: TaskKind?): String = when (taskKind) {
        TaskKind.CODE -> "Дай реализацию текущего шага: код и краткое пояснение решений."
        TaskKind.REASONING ->
            "Выполни текущий шаг: дай конкретный ответ/решение/вывод с кратким пояснением. " +
                "Не пиши код, если шаг явно не требует исполняемого кода."
        TaskKind.WRITING ->
            "Выполни текущий шаг: напиши требуемый текст/фрагмент документа. Не пиши код."
        TaskKind.EXPLANATION ->
            "Выполни текущий шаг: объясни тему/концепцию ясно и конкретно. Не пиши код, " +
                "если иллюстрация кодом явно не требуется."
        null ->
            "Выполни текущий шаг: дай конкретный результат — код, если задача программная, " +
                "иначе ответ/решение/пояснение. Кратко обоснуй решения."
    }
}
