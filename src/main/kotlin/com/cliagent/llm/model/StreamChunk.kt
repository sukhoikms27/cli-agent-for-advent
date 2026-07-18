package com.cliagent.llm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * День 30 (streaming SSE): один элемент потока ответа LLM. Sealed-контракт симметричный [LlmResult],
 * но для стриминга — эмиссия идёт по мере генерации (токен-за-токеном), без материализации всего
 * ответа в памяти до завершения.
 *
 * Три состояния:
 * - [Delta] — инкрементальная часть контента (токен/слог/кусок текста). Колбэк caller'а печатает
 *   сразу (progressive render) — пользователь видит ответ, как только первый токен пришёл, а не
 *   через 40-90с «пустоты» (qwen3:14b thinking-модель).
 * - [Done] — терминальный маркер. usage опционален (Ollama присылает только при stream_options.
 *   include_usage=true; cloud-провайдер может не прислать вовсе). finishReason опционален по той же
 *   причине. Эмиссия гарантирована: при отсутствии явного `data: [DONE]` executeStream эмитит Done
 *   в finally (graceful degradation для провайдеров, не придерживающихся спецификации OpenAI-SSE).
 * - [Error] — HTTP/сетевой сбой ДО первого токена. Эмиссия как chunk (не throw), чтобы caller мог
 *   решить реакцию. SocketTimeout в стриминге измеряет время МЕЖДУ байтами → пока токены идут, он
 *   не срабатывает (главная польза streaming для thinking-моделей).
 */
sealed class StreamChunk {
    /** Инкрементальный кусок контента (delta.content). Может быть пустым — caller фильтрует. */
    data class Delta(val content: String) : StreamChunk()

    /**
     * Инкрементальный кусок reasoning/thinking-контента (delta.reasoning). Qwen3 thinking-модели
     * отдают внутренний reasoning отдельно от content — пользователь видит progressive «размышления»
     * ДО финального ответа. Caller может показать reasoning приглушённым цветом/курсивом.
     */
    data class Reasoning(val content: String) : StreamChunk()

    /**
     * Терминальный маркер потока. [usage] и [finishReason] опциональны — провайдер не обязан
     * присылать их (Ollama: только при include_usage=true; некоторые реализации игнорируют).
     */
    data class Done(val usage: Usage? = null, val finishReason: String? = null) : StreamChunk()

    /** Сетевой/HTTP сбой ДО начала потока. [code]=0 — обрыв сети/таймаут; 4xx/5xx — HTTP статус. */
    data class Error(val code: Int, val message: String) : StreamChunk()
}

/**
 * День 30: опции стриминга (`stream_options` в OpenAI-compatible wire-формате). `include_usage=true`
 * заставляет провайдера прислать финальный фрейм с usage (prompt/completion/total tokens) ДО
 * `data: [DONE]` — нужно для [com.cliagent.llm.token.TokenCounter.recordUsage] (учёт стоимости).
 *
 * Без этого поля Ollama завершает стрим без usage → recordUsage получает null (метрики неточны,
 * но функциональность сохранена). Сериализуется как `{"include_usage":true}` только когда
 * [ChatRequest.stream]==true (см. OpenAiCompatibleClient.executeStream).
 */
@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

// ── SSE-модели парсинга (для декодинга фреймов `data: {...}`) ──────────────────

/**
 * Один фрейм SSE-стрима (OpenAI-compatible wire-формат). Декодируется из payload строки после
 * префикса `data:`. `ignoreUnknownKeys=true` (через клиентский Json) → лишние поля провайдера
 * (logprobs, system_fingerprint, …) игнорируются без ошибки.
 *
 * Структура ответа при `stream:true`:
 * ```
 * data: {"id":"...","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}
 * data: {"id":"...","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
 * data: {"id":"...","choices":[],"usage":{"prompt_tokens":10,...}}   // только при include_usage
 * data: [DONE]
 * ```
 */
@Serializable
data class StreamChatChunk(
    val id: String = "",
    val choices: List<StreamChoice> = emptyList(),
    val usage: Usage? = null,
)

/**
 * Один choice из SSE-фрейма. [delta] несёт инкрементальный контент (role только в первом фрейме,
 * content — в последующих). [finishReason] не-null только в последнем «контентовом» фрейме перед
 * termination (stop/length/tool_calls).
 */
@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: StreamDelta = StreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * Инкрементальная дельта. [role] присутствует только в первом фрейме (`{"role":"assistant"}`) —
 * это «заголовок» стрима без контента, executeStream пропускает её (нет content → нет Delta-эмиссии).
 * [content] — собственно кусок текста; null/absent в role-фрейме и в финальном finish_reason-фрейме.
 * [reasoning] — thinking-контент qwen3-моделей (Ollama отдаёт отдельно от content). null для
 * не-thinking моделей.
 */
@Serializable
data class StreamDelta(
    val role: String? = null,
    val content: String? = null,
    val reasoning: String? = null,
)
