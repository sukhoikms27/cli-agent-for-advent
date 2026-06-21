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
 * [CancellationException] этим типом не перекрывается — корутин-отмену ловить нельзя.
 */
class LlmCallException(val code: Int, override val message: String) : RuntimeException(message)
