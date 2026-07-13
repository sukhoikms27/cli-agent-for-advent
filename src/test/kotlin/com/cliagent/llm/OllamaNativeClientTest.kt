package com.cliagent.llm

import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.StreamChunk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 31: unit-тесты [OllamaNativeClient] через Ktor MockEngine — без реальной Ollama. Зеркалирует
 * паттерн [OllamaHealthCheckerTest] (injectable HttpClient с MockEngine — DI-шов).
 *
 * Покрывает:
 *  - chat: native response (message+done+eval metrics) → ChatResponse usage корректен, content верный.
 *  - chat: error field → LlmResult.Error.
 *  - chat: ChatRequest sampling-поля (topK, maxTokens, keepAlive) маппятся в wire options/keep_alive.
 *  - chat: День 29 ModelDefaults fallback — null-поля заполняются tuned defaults (temp/top_k/num_ctx/keep_alive).
 *  - chat: День 29 explicit CLI value overrides model default (temperature=0.7 побеждает default 0.3).
 *  - chat: День 29 repeat_penalty берётся из defaults, НЕ из frequencyPenalty (semantic-mismatch fix).
 *  - chat: День 31 (регрессия) native tool_calls → ToolCall list (id/type генерируются, arguments JsonElement→String).
 *  - chat: День 31 ответ без tool_calls → null (backward-compat).
 *  - chat: День 31 content="" + tool_calls → content пустой, не null.
 *  - chatStream: NDJSON (delta, delta, done+usage) → Delta×2 + Done.
 *  - chatStream: HTTP 500 → StreamChunk.Error.
 */
class OllamaNativeClientTest {

    @Test
    fun `chat parses native response with usage from eval counts`() = runTest {
        val body = """
            {"model":"qwen3:14b","created_at":"2026-01-01T00:00:00Z",
             "message":{"role":"assistant","content":"hello world"},"done":true,
             "prompt_eval_count":12,"eval_count":8,"eval_duration":1500000000}
        """.trimIndent()
        val client = clientWith(body)
        val result = client.chat(request("hi"))
        client.close()

        assertTrue(result is LlmResult.Success, "expected Success; got $result")
        val resp = (result as LlmResult.Success).data
        assertEquals("hello world", resp.choices.first().message.content)
        assertEquals("assistant", resp.choices.first().message.role)
        assertEquals("stop", resp.choices.first().finishReason)
        // usage: prompt 12 + completion 8 = 20.
        assertEquals(12, resp.usage?.promptTokens)
        assertEquals(8, resp.usage?.completionTokens)
        assertEquals(20, resp.usage?.totalTokens)
    }

    @Test
    fun `chat surfaces error field as LlmResult Error`() = runTest {
        val body = """{"error":"model 'qwen3:14b' not found, try pulling it first"}"""
        val client = clientWith(body)
        val result = client.chat(request("hi"))
        client.close()

        assertTrue(result is LlmResult.Error, "expected Error; got $result")
        val err = result as LlmResult.Error
        assertTrue(err.message.contains("not found"), "error message should be surfaced; got: ${err.message}")
    }

    @Test
    fun `chat maps topK and maxTokens into wire options and keep_alive`() = runTest {
        // MockEngine перехватывает request body и проверяет wire-формат. sampling-поля должны
        // собираться в `options` (top_k, num_predict), а keep_alive — top-level.
        var capturedBody: String? = null
        val mockEngine = MockEngine { requestData ->
            capturedBody = requestData.body.toByteArray().decodeToString()
            respond(
                """{"model":"qwen3:14b","message":{"role":"assistant","content":"ok"},"done":true,"eval_count":1}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OllamaNativeClient(
            baseUrl = "http://localhost:11434",
            http = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
            },
        )
        val req = ChatRequest(
            model = "qwen3:14b",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            topK = 40,
            maxTokens = 100,
            keepAlive = "5m",
        )
        client.chat(req)
        client.close()

        val body = capturedBody
        assertTrue(body != null, "request body should have been captured")
        assertTrue(body!!.contains("\"options\""), "options block expected in wire; got: $body")
        assertTrue(body.contains("\"top_k\":40"), "top_k=40 expected in options; got: $body")
        assertTrue(body.contains("\"num_predict\":100"), "num_predict=100 expected in options; got: $body")
        assertTrue(body.contains("\"keep_alive\":\"5m\""), "keep_alive expected top-level; got: $body")
        assertTrue(body.contains("\"stream\":false"), "non-streaming chat must set stream=false; got: $body")
    }

    @Test
    fun `chatStream parses NDJSON deltas then done with usage`() = runTest {
        // Ollama native stream = NDJSON (по JSON-объекту на строку, без SSE data: префикса).
        val body = """
            {"model":"qwen3:14b","message":{"role":"assistant","content":"Hello"},"done":false}
            {"model":"qwen3:14b","message":{"role":"assistant","content":" world"},"done":false}
            {"model":"qwen3:14b","message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":5,"eval_count":2,"eval_duration":500000000}
        """.trimIndent()
        val client = clientWith(body)
        val chunks = client.chatStream(request("hi")).toList()
        client.close()

        // Delta("Hello"), Delta(" world"), Done(usage, finishReason=stop).
        assertEquals(3, chunks.size, "expected 2 Delta + 1 Done; got $chunks")
        assertTrue(chunks[0] is StreamChunk.Delta)
        assertEquals("Hello", (chunks[0] as StreamChunk.Delta).content)
        assertTrue(chunks[1] is StreamChunk.Delta)
        assertEquals(" world", (chunks[1] as StreamChunk.Delta).content)
        val done = chunks[2]
        assertTrue(done is StreamChunk.Done, "last chunk should be Done; got $done")
        done as StreamChunk.Done
        assertEquals("stop", done.finishReason)
        assertEquals(5, done.usage?.promptTokens)
        assertEquals(2, done.usage?.completionTokens)
        assertEquals(7, done.usage?.totalTokens)
    }

    @Test
    fun `chatStream emits Error chunk on HTTP 500`() = runTest {
        val client = clientWith("Internal Server Error", status = HttpStatusCode.InternalServerError)
        val chunks = client.chatStream(request("hi")).toList()
        client.close()

        assertEquals(1, chunks.size, "single Error chunk expected on HTTP 500; got $chunks")
        val err = chunks[0]
        assertTrue(err is StreamChunk.Error, "expected Error chunk; got $err")
        err as StreamChunk.Error
        assertEquals(500, err.code)
        assertTrue(err.message.contains("Internal Server Error"), "error body surfaced; got: ${err.message}")
    }

    @Test
    fun `chat applies ModelDefaults when ChatRequest sampling fields are null`() = runTest {
        // День 29: null sampling-поля ChatRequest заполняются tuned defaults из ModelDefaultsRegistry.
        // qwen3:14b → temperature=0.3, top_k=40, num_ctx=32768, keep_alive=30m (КРИТИЧНО: numCtx без
        // явного задания режется server-default Ollama ~4096 → RAG-context теряется).
        var capturedBody: String? = null
        val mockEngine = MockEngine { requestData ->
            capturedBody = requestData.body.toByteArray().decodeToString()
            respond(
                """{"model":"qwen3:14b","message":{"role":"assistant","content":"ok"},"done":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OllamaNativeClient(
            baseUrl = "http://localhost:11434",
            http = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
            },
        )
        // Все sampling-поля null → должны взяться из defaults.
        client.chat(request("hi"))
        client.close()

        val body = capturedBody!!
        assertTrue(body.contains("\"options\""), "options block expected from defaults; got: $body")
        assertTrue(body.contains("\"temperature\":0.3"), "default temperature=0.3; got: $body")
        assertTrue(body.contains("\"top_k\":40"), "default top_k=40; got: $body")
        assertTrue(body.contains("\"num_ctx\":32768"), "default num_ctx=32768 (RAG-critical); got: $body")
        assertTrue(body.contains("\"num_predict\":2048"), "default num_predict=2048; got: $body")
        assertTrue(body.contains("\"repeat_penalty\":1.1"), "default repeat_penalty=1.1; got: $body")
        assertTrue(body.contains("\"keep_alive\":\"30m\""), "default keep_alive=30m top-level; got: $body")
    }

    @Test
    fun `explicit CLI sampling value overrides model default`() = runTest {
        // День 29: приоритет CLI flag > ModelDefaults. temperature=0.7 (explicit) побеждает default 0.3.
        var capturedBody: String? = null
        val mockEngine = MockEngine { requestData ->
            capturedBody = requestData.body.toByteArray().decodeToString()
            respond(
                """{"model":"qwen3:14b","message":{"role":"assistant","content":"ok"},"done":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OllamaNativeClient(
            baseUrl = "http://localhost:11434",
            http = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
            },
        )
        val req = ChatRequest(
            model = "qwen3:14b",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            temperature = 0.7,   // explicit override
        )
        client.chat(req)
        client.close()

        val body = capturedBody!!
        assertTrue(body.contains("\"temperature\":0.7"), "explicit temperature=0.7 must win; got: $body")
        // Остальные поля остаются из defaults (не заданы явно).
        assertTrue(body.contains("\"top_k\":40"), "default top_k=40 untouched; got: $body")
        assertTrue(body.contains("\"num_ctx\":32768"), "default num_ctx untouched; got: $body")
    }

    @Test
    fun `repeat_penalty comes from defaults not from frequencyPenalty`() = runTest {
        // День 29 fix: ранее repeat_penalty маппился из frequencyPenalty (semantic mismatch — OpenAI
        // frequency_penalty ≠ llama.cpp repeat_penalty). Теперь repeat_penalty = defaults.repeatPenalty,
        // а frequencyPenalty НЕ маппится вовсе (Ollama не имеет прямого аналога). Передаём
        // frequencyPenalty=1.5 и проверяем, что в wire НЕ появится ни 1.5, ни repeat_penalty=1.5.
        var capturedBody: String? = null
        val mockEngine = MockEngine { requestData ->
            capturedBody = requestData.body.toByteArray().decodeToString()
            respond(
                """{"model":"qwen3:14b","message":{"role":"assistant","content":"ok"},"done":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OllamaNativeClient(
            baseUrl = "http://localhost:11434",
            http = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
            },
        )
        val req = ChatRequest(
            model = "qwen3:14b",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            frequencyPenalty = 1.5,   // НЕ должен попасть в repeat_penalty
        )
        client.chat(req)
        client.close()

        val body = capturedBody!!
        assertTrue(body.contains("\"repeat_penalty\":1.1"), "repeat_penalty from defaults=1.1; got: $body")
        assertFalse(body.contains("1.5"), "frequencyPenalty=1.5 must NOT leak into wire; got: $body")
    }

    // ── День 31 (регрессия): native tool_calls parsing ──────────────────────────

    /**
     * День 31 (🔴 главная регрессия): Ollama native response с tool_calls должна маппиться в
     * [ChatResponse.choices[0].message.toolCalls] non-null list. Раньше toChatResponse() возвращал
     * toolCalls=null → ContextAwareAgent.runToolLoop никогда не стартовал → MCP-тулы недоступны для
     * локальной модели. Проверяем ключевые отличия от OpenAI: arguments — JSON-объект (JsonElement) →
     * String, id генерируется "ollama-<name>-<i>", type="function".
     */
    @Test
    fun `chat parses native tool_calls into ToolCall list`() = runTest {
        val body = """
            {"model":"qwen3:14b","created_at":"2026-01-01T00:00:00Z","done":true,
             "message":{"role":"assistant","content":"",
                "tool_calls":[
                    {"function":{"name":"search_code","arguments":{"query":"ModelLimitsRegistry"}}},
                    {"function":{"name":"read_file","arguments":{"path":"/tmp/x.kt"}}}
                ]
             },
             "prompt_eval_count":50,"eval_count":4}
        """.trimIndent()
        val client = clientWith(body)
        val result = client.chat(request("find the registry"))
        client.close()

        assertTrue(result is LlmResult.Success, "expected Success; got $result")
        val resp = (result as LlmResult.Success).data
        val msg = resp.choices.first().message
        val calls = msg.toolCalls
        assertTrue(calls != null, "toolCalls must be non-null when native tool_calls present; got null")
        assertEquals(2, calls!!.size, "two tool_calls expected; got ${calls.size}")

        // Первый tool_call: arguments JSON-объект → String (`{"query":"ModelLimitsRegistry"}`).
        val first = calls[0]
        assertEquals("search_code", first.function.name)
        assertEquals("function", first.type, "type must default to 'function' (OpenAI-compatible)")
        assertTrue(first.id.startsWith("ollama-"), "generated id must start with 'ollama-'; got ${first.id}")
        assertEquals("ollama-search_code-0", first.id, "id format = ollama-<name>-<index>")
        // JsonElement.toString() даёт compact JSON `{"query":"ModelLimitsRegistry"}`.
        assertEquals("{\"query\":\"ModelLimitsRegistry\"}", first.function.arguments,
            "arguments JSON-object → JSON-string (agent parses this back to map)")

        // Второй tool_call: index=1 в id.
        val second = calls[1]
        assertEquals("read_file", second.function.name)
        assertEquals("ollama-read_file-1", second.id, "id increments index per call")
        assertEquals("{\"path\":\"/tmp/x.kt\"}", second.function.arguments)
    }

    /**
     * День 31: обычный ответ (без tool_calls) → [ChatMessage.toolCalls] == null. Backward-compat для
     * всех существующих ответов (free chat, RAG). Sealed Result: нет tool_calls → null, не throw.
     */
    @Test
    fun `chat with no tool_calls returns null toolCalls`() = runTest {
        val body = """
            {"model":"qwen3:14b","message":{"role":"assistant","content":"just text, no tools"},"done":true}
        """.trimIndent()
        val client = clientWith(body)
        val result = client.chat(request("hi"))
        client.close()

        assertTrue(result is LlmResult.Success, "expected Success; got $result")
        val resp = (result as LlmResult.Success).data
        assertEquals(null, resp.choices.first().message.toolCalls,
            "toolCalls must be null when response has no tool_calls field")
        assertEquals("just text, no tools", resp.choices.first().message.content)
    }

    /**
     * День 31: Ollama при tool-call шлёт content="" (пустой) — ChatMessage.content должен быть пустой
     * строкой (не null, не "null"). Агент толерантен к этому (tool-loop смотрит на toolCalls, не content).
     */
    @Test
    fun `chat with empty content and tool_calls sets content empty not null`() = runTest {
        val body = """
            {"model":"qwen3:14b","message":{"role":"assistant","content":"",
                "tool_calls":[{"function":{"name":"search","arguments":{"q":"x"}}}]
             },"done":true}
        """.trimIndent()
        val client = clientWith(body)
        val result = client.chat(request("go"))
        client.close()

        assertTrue(result is LlmResult.Success, "expected Success; got $result")
        val msg = (result as LlmResult.Success).data.choices.first().message
        assertEquals("", msg.content, "content must be empty string when Ollama sends content=\"\"")
        assertEquals(1, msg.toolCalls?.size, "single tool_call expected; got ${msg.toolCalls}")
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun request(userContent: String): ChatRequest = ChatRequest(
        model = "qwen3:14b",
        messages = listOf(ChatMessage(role = "user", content = userContent)),
    )

    private fun clientWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): OllamaNativeClient {
        val mockEngine = MockEngine { _ ->
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return OllamaNativeClient(
            baseUrl = "http://localhost:11434",
            http = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true })
                }
            },
            ownsClient = true,
        )
    }
}
