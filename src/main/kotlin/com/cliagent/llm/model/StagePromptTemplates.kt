package com.cliagent.llm.model

import com.cliagent.state.TaskKind
import com.cliagent.state.TaskStage

/**
 * Stage-enforcing system prompts (доработка Day 13 по комментариям автора курса, Вариант 2).
 *
 * Базовый system prompt зависит от текущей стадии задачи → single-session агент ведёт себя
 * per stage (clarify уточняет / planning планирует / execution пишет код / validation проверяет /
 * done подводит итоги). Зеркало [PromptTemplates.buildSystemMessage] по [TaskStage] вместо
 * [ReasoningStrategy].
 *
 * Подключается в [com.cliagent.agent.ContextAwareAgent.buildMessagesToSend] когда есть активная
 * задача (`taskState != null`); иначе — прежнее поведение (`reasoningStrategy` → `systemPrompt`).
 *
 * День 15 (фикс #1): EXECUTION-промпт ветвится по [TaskKind] — код только для программных задач;
 * для логических/текстовых/объяснительных — ответ/решение/рассуждение без кода. `null` (тип неизвестен)
 * → универсальный промпт: LLM сама решает, нужен ли код.
 */
object StagePromptTemplates {

    /** Безопасное делегирование для callers без taskKind (старый контракт). */
    fun buildSystemMessage(stage: TaskStage): ChatMessage = buildSystemMessage(stage, null)

    fun buildSystemMessage(stage: TaskStage, taskKind: TaskKind?): ChatMessage = when (stage) {
        TaskStage.CLARIFY -> ChatMessage(
            role = "system",
            content = """
                You are a senior software assistant in the CLARIFY stage.
                Your ONLY job is to clarify requirements: ask focused questions about ambiguities,
                scope, constraints, and success criteria.
                Do NOT propose a solution and do NOT write code.
                If requirements are clear enough, say so briefly and stop.
            """.trimIndent()
        )
        TaskStage.PLANNING -> ChatMessage(
            role = "system",
            content = """
                You are a senior software assistant in the PLANNING stage.
                Produce a concrete, step-by-step plan for the task.
                Do NOT write implementation code yet.
                Reuse the approved plan if one is present; otherwise draft one ready for execution.
            """.trimIndent()
        )
        TaskStage.EXECUTION -> ChatMessage(
            role = "system",
            content = executionPrompt(taskKind)
        )
        TaskStage.VALIDATION -> ChatMessage(
            role = "system",
            content = """
                You are a senior software assistant in the VALIDATION stage.
                Verify the produced result against the plan and constraints.
                Do NOT add new features or code unless fixing a found defect.
                End with a clear verdict: PASS or REWORK (with reasons).
                If file-reading tools are available, read the actual artifact file to verify it
                rather than relying on memory of what was written.
            """.trimIndent()
        )
        TaskStage.DONE -> ChatMessage(
            role = "system",
            content = """
                You are a senior software assistant. The task is DONE.
                Summarize what was accomplished (plan, implementation, verdict).
                Do not start new work unless the user begins a new task.
            """.trimIndent()
        )
    }

    /**
     * EXECUTION-промпт по [TaskKind] (день 15, фикс #1). Код — только для [TaskKind.CODE];
     * для остальных — ответ/решение/рассуждение/текст. `null` → универсальный промпт, где LLM
     * сама решает, нужен ли код (защита от сбоя классификации — не форсируем код).
     *
     * День 21 (волна W3.3): к каждой ветке добавляется [toolAwareHint] — поощрение использовать
     * доступные tools (search/read) для актуальных данных вместо опоры на память. Агент
     * универсальный — намёк обобщённый (категории tools), без хардкода домена.
     */
    private fun executionPrompt(taskKind: TaskKind?): String {
        val base = when (taskKind) {
            TaskKind.CODE -> """
                You are a senior software assistant in the EXECUTION stage.
                Implement the task following the approved plan: write working code, make decisions,
                and record them.
                Do not re-plan unless the plan is demonstrably broken.
            """.trimIndent()
            TaskKind.REASONING -> """
                You are a senior assistant in the EXECUTION stage of a reasoning/analytical task.
                Work through the task following the approved plan and produce the concrete answer,
                solution or conclusion for each step. Reason explicitly and record key decisions.
                Do NOT write code unless a step explicitly requires runnable code.
                Do not re-plan unless the plan is demonstrably broken.
            """.trimIndent()
            TaskKind.WRITING -> """
                You are a senior assistant in the EXECUTION stage of a writing task.
                Produce the requested text/document following the approved plan, step by step.
                Do NOT write code. Do not re-plan unless the plan is demonstrably broken.
            """.trimIndent()
            TaskKind.EXPLANATION -> """
                You are a senior assistant in the EXECUTION stage of an explanation task.
                Explain the topic clearly and concretely, following the approved plan.
                Do NOT write code unless illustrating a concept is explicitly required.
                Do not re-plan unless the plan is demonstrably broken.
            """.trimIndent()
            null -> """
                You are a senior software assistant in the EXECUTION stage.
                Execute the task following the approved plan and produce the concrete result:
                working code if the task is programming, otherwise the answer, solution or reasoning.
                Record key decisions.
                Do not re-plan unless the plan is demonstrably broken.
            """.trimIndent()
        }
        return "$base\n\n$toolAwareHint"
    }

    /**
     * W3.3: намёк использовать доступные tools (search/read/write) для актуальных данных/фактов.
     * Обобщённый — без хардкода доменных tools по именам (агент универсальный).
     */
    private const val toolAwareHint =
        "If the task needs up-to-date data or facts, prefer available tools (search, file read/write) " +
            "over relying on your memory."
}
