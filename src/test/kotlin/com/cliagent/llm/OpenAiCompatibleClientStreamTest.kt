package com.cliagent.llm

import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.StreamChunk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 30 (streaming SSE): unit-тесты [OpenAiCompatibleClient.chatStream] через Ktor MockEngine —
 * без реальной Ollama/z.ai. Зеркалирует паттерн [com.cliagent.rag.embedding.OllamaEmbeddingClientTest]
 * (injectable HttpClient через конструктор с MockEngine — DI-шов дня 30).
 *
 * Покрывает:
 *  - happy-path: эмиссия Delta-токенов + Done с usage.
 *  - [DONE] без usage (провайдер не прислал stream_options usage).
 *  - keep-alive комментарии (`: keep-alive`) игнорируются.
 *  - HTTP 500 → StreamChunk.Error.
 *  - role-фрейм первого токена без content не порождает пустую Delta-эмиссию.
 *  - request body содержит `"stream":true` и `stream_options`.
 *  - CancellationException пробрасывается (не глотается).
 *  - Flow завершается после `data: [DONE]` (не виснет).
 *
 * SSE body — multiline string с `\n` (как реальный wire-формат Ollama /v1/chat/completions stream:true).
 *
 * Заметка о `close()`: [OpenAiCompatibleClient] не реализует AutoCloseable (HttpClient управляется
 *caller'ом через DI), поэтому тесты не зовут close — MockEngine-HttpClient собирается GC после теста.
 */
class OpenAiCompatibleClientStreamTest {

    /** Типовой SSE-stream: 2 Delta + finish_reason-фрейм + usage-фрейм + [DONE]. */
    private val fullSseBody = """
        data: {"id":"1","choices":[{"index":0,"delta":{"content":"Hello"}}]}

        data: {"id":"1","choices":[{"index":0,"delta":{"content":" world"}}]}

        data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

        data: {"id":"1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}

        data: [DONE]
    """.trimIndent()

    @Test
    fun `chatStream emits deltas then done with usage`() = runTest {
        val client = clientWith(fullSseBody)
        val chunks = client.chatStream(request()).toList()

        // Ожидаем: Delta("Hello"), Delta(" world"), Done(usage, finishReason=stop).
        // finish_reason-фрейм (delta пустой) НЕ порождает Delta-эмиссию.
        assertEquals(3, chunks.size, "expected 2 Delta + 1 Done; got $chunks")

        val first = chunks[0]
        assertTrue(first is StreamChunk.Delta, "first chunk should be Delta; got $first")
        assertEquals("Hello", (first as StreamChunk.Delta).content)

        val second = chunks[1]
        assertTrue(second is StreamChunk.Delta)
        assertEquals(" world", (second as StreamChunk.Delta).content)

        val done = chunks[2]
        assertTrue(done is StreamChunk.Done, "last chunk should be Done; got $done")
        done as StreamChunk.Done
        assertEquals("stop", done.finishReason)
        assertEquals(12, done.usage?.totalTokens)
        assertEquals(10, done.usage?.promptTokens)
        assertEquals(2, done.usage?.completionTokens)
    }

    @Test
    fun `chatStream handles DONE without usage`() = runTest {
        // Провайдер не прислал usage-фрейм (stream_options не поддержан или игнорируется).
        val body = """
            data: {"id":"1","choices":[{"index":0,"delta":{"content":"Hi"}}]}

            data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]
        """.trimIndent()
        val client = clientWith(body)
        val chunks = client.chatStream(request()).toList()

        val done = chunks.last()
        assertTrue(done is StreamChunk.Done, "expected Done at end; got $done")
        done as StreamChunk.Done
        assertEquals(null, done.usage, "usage should be null when provider omits usage-frame")
        assertEquals("stop", done.finishReason)
    }

    @Test
    fun `chatStream skips keep-alive comments`() = runTest {
        // Ollama шлёт `: keep-alive` строки для удержания соединения во время долгой генерации.
        val body = """
            : keep-alive

            data: {"id":"1","choices":[{"index":0,"delta":{"content":"A"}}]}

            : keep-alive

            data: {"id":"1","choices":[{"index":0,"delta":{"content":"B"}}]}

            data: [DONE]
        """.trimIndent()
        val client = clientWith(body)
        val chunks = client.chatStream(request()).toList()

        // keep-alive comments не должны порождать chunks; только 2 Delta + Done.
        assertEquals(3, chunks.size, "keep-alive comments should be ignored; got $chunks")
        assertTrue(chunks[0] is StreamChunk.Delta)
        assertTrue(chunks[1] is StreamChunk.Delta)
        assertTrue(chunks[2] is StreamChunk.Done)
        assertEquals("A", (chunks[0] as StreamChunk.Delta).content)
        assertEquals("B", (chunks[1] as StreamChunk.Delta).content)
    }

    @Test
    fun `chatStream emits error chunk on HTTP 500`() = runTest {
        val client = clientWith("Internal Server Error", status = HttpStatusCode.InternalServerError)
        val chunks = client.chatStream(request()).toList()

        assertEquals(1, chunks.size, "single Error chunk expected on HTTP 500; got $chunks")
        val err = chunks[0]
        assertTrue(err is StreamChunk.Error, "expected Error chunk; got $err")
        err as StreamChunk.Error
        assertEquals(500, err.code)
        assertTrue(err.message.contains("Internal Server Error"), "error body should be surfaced; got: ${err.message}")
    }

    @Test
    fun `chatStream handles content null in first delta`() = runTest {
        // Первый фрейм — role-заголовок стрима без content: {"delta":{"role":"assistant"}}.
        // executeStream НЕ должен эмитить пустую Delta (нет content). Только реальные токены.
        val body = """
            data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant"}}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"content":"Real"}}]}

            data: [DONE]
        """.trimIndent()
        val client = clientWith(body)
        val chunks = client.chatStream(request()).toList()

        // Только 1 Delta ("Real") + Done — role-фрейм пропущен.
        assertEquals(2, chunks.size, "role-only first frame must not emit Delta; got $chunks")
        assertTrue(chunks[0] is StreamChunk.Delta)
        assertEquals("Real", (chunks[0] as StreamChunk.Delta).content)
        assertTrue(chunks[1] is StreamChunk.Done)
    }

    @Test
    fun `chatStream sets stream=true and stream_options in request`() = runTest {
        // MockEngine перехватывает request body и проверяет wire-формат. chatStream форсирует
        // stream=true + stream_options.include_usage=true независимо от исходного request.
        var capturedBody: String? = null
        val mockEngine = MockEngine { requestData ->
            capturedBody = requestData.body.toByteArray().decodeToString()
            respond(fullSseBody, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }
        val client = OpenAiCompatibleClient(
            baseUrl = "http://localhost:11434/v1",
            apiKey = "",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    // encodeDefaults=true — как production Json OpenAiCompatibleClient (день 30):
                    // include_usage=true (default StreamOptions) должен сериализоваться в wire-формат.
                    json(Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    })
                }
            },
        )
        client.chatStream(request()).toList()

        val body = capturedBody
        assertTrue(body != null, "request body should have been captured")
        assertTrue(body!!.contains("\"stream\":true"), "stream:true expected in wire body; got: $body")
        assertTrue(
            body.contains("\"stream_options\"") && body.contains("\"include_usage\":true"),
            "stream_options.include_usage:true expected; got: $body",
        )
    }

    @Test
    fun `chatStream propagates CancellationException`() = runTest {
        // CancellationException НЕ глотается (AGENTS.md) — пробрасывается для отмены корутины caller'а.
        // Гарантия: если executeStream ловил бы Throwable без re-throw CancellationException, отмена
        // либо зависла бы (join висит), либо job завершился normally. Здесь handler приостанавливает
        // ответ до отмены (awaitCancellation) → collect ждёт → cancel → job должен стать cancelled.
        val mockEngine = MockEngine { _ ->
            kotlinx.coroutines.awaitCancellation()   // запрос никогда не ответит (имитация долгого thinking)
        }
        val client = OpenAiCompatibleClient(
            baseUrl = "http://localhost:11434/v1",
            apiKey = "",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
            },
        )
        val job = this.launch {
            client.chatStream(request()).toList()
        }
        delay(100)        // пусть collect стартует и уйдёт в ожидание ответа
        job.cancel()      // отменяем посередине
        job.join()        // не должно виснуть (если CancellationException глотается — join зависнет)
        assertTrue(job.isCancelled, "job should be cancelled, not completed normally (CancellationException not swallowed)")
    }

    @Test
    fun `chatStream terminates on DONE`() = runTest {
        // Flow ДОЛЖЕН завершиться после [DONE] — не виснуть, не требовать cancel() от caller'а.
        // toList() под runTest завис бы, если бы flow не завершился → тест бы упал по таймауту.
        val client = clientWith(fullSseBody)
        val chunks = client.chatStream(request()).toList()

        assertFalse(chunks.isEmpty(), "flow should have emitted chunks")
        assertTrue(chunks.last() is StreamChunk.Done, "flow should terminate with Done after [DONE]")
        // Если бы flow не завершился, toList() не вернул бы управление — тест упал бы по timeout.
    }

    @Test
    fun `chatStream emits Done gracefully when stream ends without explicit DONE`() = runTest {
        // Нестрогий провайдер закрыл соединение после последнего токена без `data: [DONE]`.
        // executeStream эмитит Done из graceful-fallback (терминальный маркер гарантирован).
        val body = """
            data: {"id":"1","choices":[{"index":0,"delta":{"content":"X"}}]}

            data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
        """.trimIndent()
        val client = clientWith(body)
        val chunks = client.chatStream(request()).toList()

        // Delta("X") + graceful Done (без явного [DONE] в wire).
        assertEquals(2, chunks.size, "expected Delta + graceful Done; got $chunks")
        assertTrue(chunks[0] is StreamChunk.Delta)
        assertTrue(chunks[1] is StreamChunk.Done, "graceful Done expected when stream ends without [DONE]")
        assertEquals("stop", (chunks[1] as StreamChunk.Done).finishReason)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Минимальный ChatRequest для тестов (model + 1 message). chatStream форсирует stream флаги. */
    private fun request(): ChatRequest = ChatRequest(
        model = "qwen3:14b",
        messages = listOf(ChatMessage(role = "user", content = "hi")),
    )

    /** OpenAiCompatibleClient с MockEngine, отвечающим [body] / [status]. DI-конструктор (день 30). */
    private fun clientWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): OpenAiCompatibleClient {
        val mockEngine = MockEngine { _ ->
            respond(body, status, headersOf("Content-Type", "text/event-stream"))
        }
        return OpenAiCompatibleClient(
            baseUrl = "http://localhost:11434/v1",
            apiKey = "",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
            },
        )
    }
}
