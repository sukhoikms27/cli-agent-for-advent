package com.cliagent.agent.stage

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest

/**
 * Результат интерпретации ответа пользователя в точке подтверждения перехода между стадиями
 * (день 15, доп. п.2 — гибридное подтверждение).
 *
 * - [CONFIRM]   — чистое согласие перейти дальше: «да», «давай», «поехали», «супер».
 * - [REFINE]    — пользователь хочет доработать/изменить артефакт текущей стадии, включая
 *                 оговорочные ответы («да, но без тестов», «ок, только короче») — они НЕ подтверждение.
 * - [AMBIGUOUS] — ответ непонятен или смешан; трактуется безопасно, как [REFINE] (без перехода).
 */
enum class ConfirmationIntent { CONFIRM, REFINE, AMBIGUOUS }

/**
 * LLM-интерпретация ответа пользователя в точке подтверждения перехода (день 15, доп. п.2).
 *
 * Гибридная схема: [TaskOrchestrator.isYes] ловит очевидные короткие подтверждения детерминированно
 * (мгновенно, бесплатно); этот классификатор догадывается о смысле **остального** текста —
 * естественные фразы («угу, поехали», «супер, продолжаем»), опечатки, условности.
 *
 * Один LLM-запрос (temperature 0, паттерн [IntentClassifier]/[TaskKindClassifier]). Ответ — строго
 * `CONFIRM`, `REFINE` или `AMBIGUOUS`.
 *
 * **Safe-bias**: на ошибку/мусор LLM → [REFINE] (как и [AMBIGUOUS] в решении оркестратора это
 * «не подтверждение»). Это сознательно: ложноположительное подтверждение (переход с потерей
 * уточнения) хуже ложноотрицательного (лишний шаг доработки). Особенно важно в MANUAL, где
 * переход стадии без явного согласия нарушает «полный контроль пользователя».
 *
 * Используется в [TaskOrchestrator.handleUserInput] при `awaitingAdvance=true` и режиме ≠ AUTO,
 * только если текст не пойман быстрым путём [TaskOrchestrator.isYes].
 */
class ConfirmationClassifier(
    private val llmClient: LlmClient,
    private val model: String
) {
    suspend fun classify(userReply: String): ConfirmationIntent {
        if (userReply.isBlank()) return ConfirmationIntent.REFINE
        val prompt = """
            Контекст: агент-оркестратор только что показал артефакт текущей стадии (план / реализация
            / вердикт) и спросил: «Перейти к следующей стадии? Ответьте да — продолжить, либо
            напишите уточнение для доработки.» Пользователь ответил фразой ниже. Определи его намерение.

            CONFIRM — чистое согласие перейти к следующей стадии, без оговорок и правок.
              Примеры: «да», «давай», «поехали», «супер», «угу», «ок», «готово», «подтверждаю».
            REFINE — пользователь хочет изменить/доработать артефакт текущей стадии, дать feedback,
              отказаться от перехода или ставит условия. Любая оговорка («да, но…», «ок, только
              без …», «перепиши короче», «нет, подожди», «измени шаг 2») — это REFINE, не CONFIRM.
            AMBIGUOUS — ответ непонятен, не относится к выбору, или смешан так, что намерение неясно.

            Ответ пользователя:
            ${userReply.take(MAX_REPLY_CHARS)}

            Ответь строго одним словом: CONFIRM, REFINE или AMBIGUOUS.
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
                    text.contains("CONFIRM") -> ConfirmationIntent.CONFIRM
                    text.contains("AMBIGUOUS") -> ConfirmationIntent.AMBIGUOUS
                    text.contains("REFINE") -> ConfirmationIntent.REFINE
                    else -> ConfirmationIntent.REFINE   // мусор → безопасный fallback
                }
            }
            is LlmResult.Error -> ConfirmationIntent.REFINE
        }
    }

    private companion object {
        const val MAX_REPLY_CHARS = 1000
    }
}
