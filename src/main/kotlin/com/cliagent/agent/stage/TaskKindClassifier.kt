package com.cliagent.agent.stage

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.state.TaskKind

/**
 * Классификатор типа задачи (день 15, фикс #1 — гибрид; день 34 — расширение FILE_OP/PR_REVIEW).
 *
 * Один LLM-запрос (temperature 0, паттерн [EntryStageClassifier]/[IntentClassifier]):
 * определяет [TaskKind] по описанию задачи. Возвращает **null** на ошибку/мусор/пустой ввод —
 * это сознательный выбор: `null` = «тип неизвестен» → смягчённый универсальный EXECUTION-промпт,
 * в котором LLM сама решает, нужен ли код. НЕ fallback на [TaskKind.CODE], чтобы не плодить
 * баг #1 (код для логических задач) при сбое классификации.
 *
 * Результат хранится в [com.cliagent.state.TaskState.taskKind] и ветвит промпты EXECUTION
 * ([com.cliagent.llm.model.StagePromptTemplates], [StepAgent], [ExecutionStageAgent]),
 * а также routing stage-executor'ов в [TaskOrchestrator.defaultAgents]
 * (FILE_OP → file-swarm, PR_REVIEW → ReviewValidationAgent).
 */
class TaskKindClassifier(
    private val llmClient: LlmClient,
    private val model: String
) {
    suspend fun classify(taskDescription: String): TaskKind? {
        if (taskDescription.isBlank()) return null
        val prompt = """
            Определи тип задачи по её описанию. Ответь строго одним словом из списка:
            CODE, REASONING, WRITING, EXPLANATION, FILE_OP, PR_REVIEW.

            CODE — программная задача: реализовать функцию/класс/скрипт, починить баг, написать код,
            спроектировать программу. На стадии реализации нужен рабочий код.
            REASONING — логическая/аналитическая задача: решить задачу/головоломку, сделать вывод,
            проанализировать, выбрать вариант. Код не нужен — нужно решение/рассуждение.
            WRITING — текстовая задача: написать документ/текст/письмо/статью. Код не нужен.
            EXPLANATION — объяснить концепцию/теорию/как что-то работает. Код не нужен.
            FILE_OP — задача на операции с файлами проекта: обновить документацию по коду, найти
            все TODO/FIXME, сгенерировать README/CHANGELOG/ADR, проверить соответствие файлов
            правилам, проанализировать структуру нескольких файлов, подготовить список изменений.
            Агент читает/ищет/изменяет файлы через инструменты — это НЕ написание кода с нуля.
            PR_REVIEW — ревью изменений: проанализировать git-diff/PR, проверить качество кода
            в изменениях, отревьюить коммит, найти проблемы во внесённых правках.

            Описание задачи:
            ${taskDescription.take(MAX_DESC_CHARS)}

            Ответ — строго одно слово: CODE, REASONING, WRITING, EXPLANATION, FILE_OP или PR_REVIEW.
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            temperature = 0.0
        )
        return when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> {
                val text = result.data.choices.firstOrNull()?.message?.content.orEmpty().uppercase()
                TaskKind.entries.firstOrNull { text.contains(it.name) }
            }
            is LlmResult.Error -> null
        }
    }

    private companion object {
        const val MAX_DESC_CHARS = 2000
    }
}
