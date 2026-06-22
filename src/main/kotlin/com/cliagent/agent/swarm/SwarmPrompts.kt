package com.cliagent.agent.swarm

import com.cliagent.agent.stage.StageContext
import com.cliagent.llm.token.ArtifactLimits
import com.cliagent.llm.token.truncateToTokens
import com.cliagent.state.TaskStage

/**
 * Промпт-билдеры роя (V4: lead → workers → integrate) per стадия + стратегия ([SwarmStrategy]).
 * Входные артефакты усекаются через [truncateToTokens]/[ArtifactLimits] (мера C) → bounded-контекст
 * на каждом worker-вызове. Промпты инструктируют integrator давать финальный артефакт в том же
 * формате, что и простые stage-агенты (маркеры `[CLEAR]`/`[ASK]`, `PASS`/`REWORK`), чтобы
 * [readiness]/[display] были консистентны с простыми агентами.
 */
object SwarmPrompts {

    // ── Контекст задачи для worker'ов (общая часть, bounded) ──

    private fun StageContext.boundedContext(stage: TaskStage): String = buildString {
        append("Задача: ").append(taskDescription)
        when (stage) {
            TaskStage.CLARIFY -> requirements?.let { append("\nУже уточнено: ").append(truncateToTokens(it, ArtifactLimits.REQUIREMENTS_TOKENS)) }
            TaskStage.PLANNING -> requirements?.let { append("\nТребования: ").append(truncateToTokens(it, ArtifactLimits.REQUIREMENTS_TOKENS)) }
            TaskStage.EXECUTION -> approvedPlan?.let { append("\nПлан: ").append(truncateToTokens(it, ArtifactLimits.PLAN_TOKENS)) }
            TaskStage.VALIDATION -> {
                approvedPlan?.let { append("\nПлан: ").append(truncateToTokens(it, ArtifactLimits.PLAN_TOKENS)) }
                implementation?.let { append("\nРеализация: ").append(truncateToTokens(it, ArtifactLimits.IMPLEMENTATION_TOKENS)) }
            }
            TaskStage.DONE -> {
                approvedPlan?.let { append("\nПлан: ").append(truncateToTokens(it, ArtifactLimits.DONE_SUMMARY_INPUT)) }
                implementation?.let { append("\nРеализация: ").append(truncateToTokens(it, ArtifactLimits.DONE_SUMMARY_INPUT)) }
                verdict?.let { append("\nВердикт: ").append(truncateToTokens(it, ArtifactLimits.VERDICT_TOKENS)) }
            }
        }
        feedback?.let { append("\nОтзыв: ").append(truncateToTokens(it, ArtifactLimits.FEEDBACK_TOKENS)) }
        profileBlock?.let { append("\n").append(it) }
    }

    /** Предмет декомпозиции lead-агента (что дробится) по стадии. */
    private fun decompositionSubject(stage: TaskStage): String = when (stage) {
        TaskStage.CLARIFY -> "грани неоднозначности задачи (scope, стек, ограничения, критерии успеха, краевые случаи)"
        TaskStage.PLANNING -> "модули/направления задачи для построения плана"
        TaskStage.EXECUTION -> "группы шагов утверждённого плана для реализации"
        TaskStage.VALIDATION -> "слайсы реализации для независимой проверки"
        TaskStage.DONE -> "аспекты итога (что сделано, ключевые решения, результат, риски, что дальше)"
    }

    /** Финальный артефакт стадии (для формулировок промптов). */
    private fun artifactName(stage: TaskStage): String = when (stage) {
        TaskStage.CLARIFY -> "ответ (начни со слова [CLEAR] если требований достаточно, иначе [ASK] и 1-3 вопроса)"
        TaskStage.PLANNING -> "пошаговый план (нумерованный список, без реализации)"
        TaskStage.EXECUTION -> "реализация (код/решение по шагам)"
        TaskStage.VALIDATION -> "проверка, закончи строкой PASS (всё ок) или REWORK (есть проблемы)"
        TaskStage.DONE -> "краткий итог"
    }

    // ── Lead ──

    fun leadPrompt(stage: TaskStage, spec: SwarmSpec, ctx: StageContext): String = buildString {
        append("Ты — lead-агент. ")
        when (spec.strategy) {
            SwarmStrategy.PARTITION -> {
                append("Разбей работу на ≤${spec.maxWorkers} независимых частей: ${decompositionSubject(stage)}. ")
                append("Каждая часть должна быть самодостаточной для исполнения отдельным worker'ом. ")
            }
            SwarmStrategy.SPECIALISTS -> {
                append("Назначь ≤${spec.maxWorkers} ролей-специалистов по граням: ${decompositionSubject(stage)}. ")
                append("Каждая роль — отдельная точка зрения для анализа. ")
            }
            SwarmStrategy.REDUNDANCY -> {
                append("Сформулируй единую полную задачу для независимой генерации ${spec.maxWorkers} кандидатов. ")
            }
        }
        append("Верни ТОЛЬКО нумерованный список (1) ... 2) ...), без пояснений.\n\n")
        append(ctx.boundedContext(stage))
    }

    // ── Worker ──

    fun workerPrompt(stage: TaskStage, spec: SwarmStrategy, ctx: StageContext, subtask: String, index: Int): String =
        buildString {
            append("Ты — worker $index роя. ")
            when (spec) {
                SwarmStrategy.PARTITION -> append("Выполни ТОЛЬКО свою часть, не делай чужую: ")
                    .append(truncateToTokens(subtask, ArtifactLimits.PLAN_IN_STEP_TOKENS))
                SwarmStrategy.SPECIALISTS -> append("Ты — специалист по грани: ")
                    .append(truncateToTokens(subtask, ArtifactLimits.PLAN_IN_STEP_TOKENS))
                    .append(". Проанализируй задачу с этой стороны и дай находки по своей грани. ")
                SwarmStrategy.REDUNDANCY -> append("Независимо сгенерируй полный ${artifactName(stage)} (вариант $index). ")
            }
            append("\n\n").append(ctx.boundedContext(stage))
            append("\n\nДай результат своей части.")
        }

    // ── Integrate ──

    fun integratePrompt(stage: TaskStage, spec: SwarmStrategy, ctx: StageContext, workerOutputs: List<String>): String =
        buildString {
            append("Ты — integrator роя. Собери из результатов worker'ов единый финальный артефакт: ")
                .append(artifactName(stage)).append(". ")
            when (spec) {
                SwarmStrategy.PARTITION -> append("Слей части в связное целое, без дублирований и противоречий. ")
                SwarmStrategy.SPECIALISTS -> append("Синтезируй находки специалистов в единый результат. ")
                SwarmStrategy.REDUNDANCY -> append("Выбери лучший из кандидатов и при необходимости слей сильные стороны. ")
            }
            append("\n\nКонтекст задачи:\n").append(ctx.boundedContext(stage))
            val joined = workerOutputs.mapIndexed { i, o -> "=== Worker ${i + 1} ===\n$o" }.joinToString("\n\n")
            append("\n\nРезультаты worker'ов:\n").append(truncateToTokens(joined, ArtifactLimits.IMPLEMENTATION_TOKENS * 2))
            append("\n\nДай финальный артефакт.")
        }

    // ── Готовность к переходу (консистентно с простыми stage-агентами) ──

    fun readiness(stage: TaskStage, artifact: String): Boolean {
        val t = artifact.trim()
        return when (stage) {
            TaskStage.CLARIFY -> t.startsWith("[CLEAR]", ignoreCase = true)
            TaskStage.PLANNING, TaskStage.EXECUTION -> t.isNotBlank()
            TaskStage.VALIDATION -> t.contains("PASS", ignoreCase = true) && !t.contains("REWORK", ignoreCase = true)
            TaskStage.DONE -> false
        }
    }

    /** Что положить в TaskState как артефакт (CLARIFY: снять маркер [CLEAR]; [ASK] → null). */
    fun artifactFor(stage: TaskStage, integrated: String): String? {
        val t = integrated.trim()
        return when (stage) {
            TaskStage.CLARIFY -> when {
                t.startsWith("[CLEAR]", ignoreCase = true) ->
                    t.removePrefixIgnoreCase("[CLEAR]").trim().ifBlank { null }
                else -> null   // [ASK] или без маркера — requirements не обновляем
            }
            else -> integrated
        }
    }

    fun display(stage: TaskStage, integrated: String): String {
        val t = integrated.trim()
        return when (stage) {
            TaskStage.CLARIFY -> when {
                t.startsWith("[CLEAR]", ignoreCase = true) ->
                    "✓ Требования собраны:\n\n${t.removePrefixIgnoreCase("[CLEAR]").trim()}"
                t.startsWith("[ASK]", ignoreCase = true) ->
                    "❓ Вопросы для уточнения:\n\n${t.removePrefixIgnoreCase("[ASK]").trim().ifBlank { t }}"
                else -> "❓ Уточни, пожалуйста:\n\n$t"
            }
            TaskStage.PLANNING -> "📋 План:\n\n$t"
            TaskStage.EXECUTION -> "⚙️ Реализация:\n\n$t"
            TaskStage.VALIDATION ->
                if (readiness(stage, t)) "✅ Проверка:\n\n$t" else "🟡 Проверка (найдены проблемы):\n\n$t"
            TaskStage.DONE -> "🏁 Задача завершена:\n\n${t.ifBlank { "Итог недоступен." }}"
        }
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (length >= prefix.length && substring(0, prefix.length).equals(prefix, ignoreCase = true))
            substring(prefix.length) else this
}
