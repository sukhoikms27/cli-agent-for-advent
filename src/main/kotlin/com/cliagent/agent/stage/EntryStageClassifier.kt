package com.cliagent.agent.stage

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.state.TaskStage

/**
 * Авто-выбор стартовой стадии после `/task start` (выбор пользователя «агент сам решает»).
 *
 * Один LLM-запрос (temperature 0, паттерн [com.cliagent.agent.ProfileExtractor]):
 * достаточно ли деталей в описании задачи для планирования, или нужно уточнить требования?
 * Ответ — строго `CLARIFY` или `PLANNING`. На ошибку/мусор LLM → fallback `PLANNING`
 * (нельзя застрять на классификации; planning-агент сам доуточнит по ходу).
 */
class EntryStageClassifier(
    private val llmClient: LlmClient,
    private val model: String
) {
    suspend fun classify(taskDescription: String): TaskStage {
        if (taskDescription.isBlank()) return TaskStage.PLANNING
        val prompt = """
            Определи, достаточно ли деталей в описании задачи, чтобы сразу строить план (PLANNING),
            или требований мало/они размыты и нужно сначала уточнить (CLARIFY).

            Признаки CLARIFY: описание короче 1-2 фраз, нет конкретики по стеку/объёму/критериям,
            есть явная неоднозначность. Признаки PLANNING: есть конкретика, можно сразу декомпозировать.

            Описание задачи:
            ${taskDescription.take(MAX_DESC_CHARS)}

            Ответь строго одним словом: CLARIFY или PLANNING.
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
                    text.contains("CLARIFY") -> TaskStage.CLARIFY
                    text.contains("PLANNING") -> TaskStage.PLANNING
                    else -> TaskStage.PLANNING   // мусор → безопасный fallback
                }
            }
            is LlmResult.Error -> TaskStage.PLANNING
        }
    }

    private companion object {
        const val MAX_DESC_CHARS = 2000
    }
}
