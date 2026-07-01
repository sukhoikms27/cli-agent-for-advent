package com.cliagent.agent.stage

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.state.TaskComplexity

/**
 * Классификатор сложности задачи (день 21, волна W2). Определяет, окупается ли рой на данной задаче.
 *
 * Один LLM-запрос (temperature 0, паттерн [EntryStageClassifier]): TRIVIAL / MODERATE / COMPLEX.
 * - [TaskComplexity.TRIVIAL]  → весь пайплайн single-agent (рой не окупается; ~31 → ~5 LLM-вызовов).
 * - [TaskComplexity.MODERATE] → рой только на PLANNING/EXECUTION/VALIDATION (AUTO-гейт).
 * - [TaskComplexity.COMPLEX]  → полный рой (где AUTO-гейт разрешает).
 *
 * На ошибку/мусор LLM → fallback [TaskComplexity.MODERATE] (безопасный: не пере- и не недо-оцениваем).
 */
class TaskComplexityClassifier(
    private val llmClient: LlmClient,
    private val model: String
) {
    suspend fun classify(taskDescription: String): TaskComplexity {
        if (taskDescription.isBlank()) return TaskComplexity.MODERATE
        val prompt = """
            Определи сложность задачи по её описанию. Ответь строго одним словом: TRIVIAL, MODERATE или COMPLEX.

            TRIVIAL — одно атомарное действие, короткое описание. Ответить/сделать можно за один шаг без
            декомпозиции. Примеры: объяснить концепцию в двух словах, перевести фразу, дать фактологическую
            справку, переформулировать, ответить на фактический вопрос. Не требует плана и валидации.

            MODERATE — задача из 2-4 шагов в одной области. Нужен короткий план и реализация, но объём
            артефакта умеренный. Примеры: реализовать одну функцию/класс, написать короткий документ,
            решить задачу в 2-3 хода.

            COMPLEX — многошаговая задача, кросс-доменная, с длинным артефактом или несколькими модулями.
            Требует декомпозиции, координации частей, тщательной валидации. Примеры: спроектировать модульную
            систему, реализовать фичу из 5+ компонентов, собрать аналитический отчёт из нескольких источников.

            Описание задачи:
            ${taskDescription.take(MAX_DESC_CHARS)}

            Ответ — строго одно слово: TRIVIAL, MODERATE или COMPLEX.
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            temperature = 0.0
        )
        return when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> {
                val text = result.data.choices.firstOrNull()?.message?.content.orEmpty().uppercase()
                when {
                    text.contains("TRIVIAL") -> TaskComplexity.TRIVIAL
                    text.contains("COMPLEX") -> TaskComplexity.COMPLEX
                    text.contains("MODERATE") -> TaskComplexity.MODERATE
                    else -> TaskComplexity.MODERATE   // мусор → безопасный fallback
                }
            }
            is LlmResult.Error -> TaskComplexity.MODERATE
        }
    }

    private companion object {
        const val MAX_DESC_CHARS = 2000
    }
}
