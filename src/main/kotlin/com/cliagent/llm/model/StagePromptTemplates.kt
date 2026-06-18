package com.cliagent.llm.model

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
 */
object StagePromptTemplates {

    fun buildSystemMessage(stage: TaskStage): ChatMessage = when (stage) {
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
            content = """
                You are a senior software assistant in the EXECUTION stage.
                Implement the task following the approved plan: write working code, make decisions,
                and record them.
                Do not re-plan unless the plan is demonstrably broken.
            """.trimIndent()
        )
        TaskStage.VALIDATION -> ChatMessage(
            role = "system",
            content = """
                You are a senior software assistant in the VALIDATION stage.
                Verify the produced result against the plan and constraints.
                Do NOT add new features or code unless fixing a found defect.
                End with a clear verdict: PASS or REWORK (with reasons).
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
}
