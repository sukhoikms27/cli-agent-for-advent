package com.cliagent.llm

/**
 * День 33 (refinement): shared retry-хелперы для LLM-клиентов.
 *
 * Раньше [OpenAiCompatibleClient] и [OllamaNativeClient] дублировали retry-логику (MAX_ATTEMPTS,
 * isRetryable, backoffDelay), а сетевые ошибки теряли тип исключения (общий catch → "Request failed: "
 * + невнятный e.message). Это делало логи бесполезными: `code=0` без контекста не позволял отличить
 * «сервер не запущен» от «DNS-опечатка» от «модель долго думает».
 *
 * Теперь сетевые исключения классифицируются через [classifyNetworkError] — лог содержит явный
 * диагноз (connection refused / unknown host / socket timeout / SSL error), который помогает
 * пользователю понять, на чьей стороне проблема.
 */

/**
 * Классификация сетевого исключения в человекочитаемое сообщение.
 *
 * `code=0` в [LlmResult.Error] означает сетевую ошибку (HTTP-код недоступен — запрос даже не
 * дошёл до сервера). Без классификации это бесполезно: проблема может быть на нашей стороне
 * (опечатка в baseUrl), на стороне сети (DNS/обрыв), на стороне провайдера (сервер упал),
 * или быть таймаутом. Эта функция формирует явный диагноз.
 *
 * @return строка вида "Request failed: <классификация> [<raw message>]" для логов.
 */
fun classifyNetworkError(e: Throwable): String {
    // Порядок веток важен: Ktor's ConnectTimeoutException наследует java.net.ConnectException,
    // поэтому специфичные ktor-исключения идут ДО общих java.net. Аналогично SocketTimeoutException
    // в Ktor — это typealias к java.net.SocketTimeoutException (одно и то же).
    val type = when (e) {
        is io.ktor.client.network.sockets.ConnectTimeoutException ->
            "connect timeout (сервер не отвечает за connectTimeout)"
        is java.net.ConnectException ->
            "connection refused (сервер не запущен или не отвечает на этом порту)"
        is java.net.UnknownHostException ->
            "unknown host (DNS/опечатка в URL, проверьте baseUrl)"
        is java.net.SocketTimeoutException ->
            "socket timeout (сервер долго отвечает — slow model/network)"
        is java.net.SocketException ->
            "socket error: ${e.message ?: "обрыв соединения"}"
        is javax.net.ssl.SSLException ->
            "SSL/TLS error: ${e.message ?: "невалидный сертификат / протокол"}"
        else -> null
    }
    val rawMessage = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    return if (type != null) "Request failed: $type [$rawMessage]" else "Request failed: $rawMessage"
}
