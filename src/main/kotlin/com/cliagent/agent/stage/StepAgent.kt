package com.cliagent.agent.stage

/**
 * Исполнитель одного шага плана (гибрид execution — отдельный агент на каждый пункт плана).
 *
 * Промпт сфокусирован на одном шаге, с учётом уже сделанных шагов (продолжение контекста):
 * модель не перепланирует и не делает чужие шаги. Возвращает код/румент шага.
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
            append("\n\nДай реализацию текущего шага: код и краткое пояснение решений.")
        }
        return chat(message).trim()
    }
}
