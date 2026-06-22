package com.cliagent.llm

/**
 * Инфраструктурная ошибка LLM-вызова (таймаут, HTTP 4xx/5xx, обрыв соединения, мусорный ответ).
 *
 * Не «flow control»: [LlmResult] остаётся sealed-контрактом на границе [LlmClient] (успех/ошибка
 * как данные). Это исключение — сигнал о **сбое** вышележащим слоям ([com.cliagent.agent.ContextAwareAgent.chat],
 * stage-поток), которые работают со строкой и иначе не отличают ошибку от нормального ответа
 * (баг: error-строка трактовалась stage-агентом как готовый артефакт).
 *
 * Ловится на границах, возвращающих строку/StageResult:
 *  - [com.cliagent.agent.stage.TaskOrchestrator.runOneStage] → StageResult с readyToAdvance=false
 *    (артефакт не персистится, переход не предлагается);
 *  - REPL обычного чата в [com.cliagent.cli.ChatCommand] → печать сообщения об ошибке.
 *
 * [isTruncated] — обрыв ответа по `finish_reason=length` (мера B): оркестратор может сделать
 * auto-continue ([com.cliagent.agent.stage.TaskOrchestrator.chatWithContinue]); [partial] несёт
 * уже сгенерированную часть для сшивки.
 *
 * [CancellationException] этим типом не перекрывается — корутин-отмену ловить нельзя.
 */
class LlmCallException(
    val code: Int,
    override val message: String,
    /** Частичный ответ при обрыве по длине (finish_reason=length); null для прочих ошибок. */
    val partial: String? = null
) : RuntimeException(message) {
    /** true, если причина — обрыв ответа по `finish_reason=length` (серверный/наш лимит max_tokens). */
    val isTruncated: Boolean get() = code == CODE_TRUNCATED

    companion object {
        /** Семантический код: обрыв ответа по длине (finish_reason=length). */
        const val CODE_TRUNCATED = -1

        fun truncated(partialContent: String): LlmCallException = LlmCallException(
            CODE_TRUNCATED,
            "Response truncated (finish_reason=length). Partial length=${partialContent.length}",
            partial = partialContent
        )
    }
}
