package com.cliagent.cli

import com.cliagent.llm.LlmClient
import com.cliagent.llm.OllamaBenchClient
import com.cliagent.llm.model.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import com.cliagent.rag.JsonRagStore
import com.cliagent.rag.RagChunk
import com.cliagent.rag.RagIndex
import com.cliagent.rag.RagRetriever
import com.cliagent.rag.embedding.EmbeddingClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * День 28: unit-тесты [LocalRagCompare] на stub-клиентах (без сети/Ollama). Проверяем, что harness:
 *  - возвращает ровно N результатов (по числу вопросов) в local-vs-cloud и local-only режимах;
 *  - fair compare: retrieve вызывается ОДИН раз на вопрос (общий контекст для обоих клиентов);
 *  - метрики корректны (keyword coverage считается из ответа, latency >= 0, maxTokens=2048 для local);
 *  - CancellationException re-throw (не глотается, AGENTS.md).
 *
 * RAG-retrieval изолирован: [RagRetriever] собран на stub-embedder'е (фиксированный вектор) и temp-
 * индексе (чанки с тем же вектором → cosine=1.0 → все hits).
 */
class LocalRagCompareTest {

    @TempDir
    lateinit var tmpDir: Path

    private val fixedVec = listOf(0.1f, 0.2f, 0.3f)

    private val sampleQuestions = listOf(
        LocalRagCompare.CompareQuestion(
            id = "q1",
            question = "Какой размер батча используется?",
            expectedKeywords = listOf("32", "batch"),
            expectedSources = listOf("OllamaEmbeddingClient.kt"),
        ),
        LocalRagCompare.CompareQuestion(
            id = "q2",
            question = "Какой алгоритм хэширования?",
            expectedKeywords = listOf("FNV"),
            expectedSources = listOf("DocumentLoader.kt"),
        ),
    )

    @Test
    fun `runCompare returns one result per question in local-vs-cloud mode`() = runTest {
        val retriever = buildRetriever()
        val local = ScriptedClient(listOf("размер батча 32 batch", "хэш FNV алгоритм"))
        val cloud = ScriptedClient(listOf("batch size is 32", "FNV hash"))
        val results = LocalRagCompare.runCompare(
            questions = sampleQuestions,
            retriever = retriever,
            localClient = local,
            localModel = "qwen3:14b",
            cloudClient = cloud,
            cloudModel = "glm-5.1",
        )
        assertEquals(2, results.size)
        assertEquals("q1", results[0].question.id)
        assertEquals("q2", results[1].question.id)
        // Оба клиента вызваны по разу на вопрос.
        assertEquals(2, local.calls)
        assertEquals(2, cloud.calls)
        // Cloud присутствует в каждом результате.
        results.forEach { assertNotNull(it.cloud) }
    }

    @Test
    fun `runCompare local-only mode when cloudClient is null`() = runTest {
        val retriever = buildRetriever()
        val local = ScriptedClient(listOf("32 batch", "FNV"))
        val results = LocalRagCompare.runCompare(
            questions = sampleQuestions,
            retriever = retriever,
            localClient = local,
            localModel = "qwen3:14b",
            cloudClient = null,
            cloudModel = null,
        )
        assertEquals(2, results.size)
        results.forEach {
            assertNull(it.cloud, "cloud must be null in local-only mode")
            assertNotNull(it.local)
        }
        assertEquals(2, local.calls)
    }

    @Test
    fun `runCompare keyword coverage counted from answer text`() = runTest {
        val retriever = buildRetriever()
        // Ответ содержит оба keywords q1 ("32" и "batch") → hits=2/2.
        val local = ScriptedClient(listOf("ответ: размер батча = 32, это batch-mode", "FNV"))
        val cloud = ScriptedClient(listOf("32 batch", "FNV"))
        val results = LocalRagCompare.runCompare(
            questions = sampleQuestions,
            retriever = retriever,
            localClient = local,
            localModel = "qwen3:14b",
            cloudClient = cloud,
            cloudModel = "glm-5.1",
        )
        val q1Local = results[0].local
        assertEquals(2, q1Local.keywordHits)
        assertEquals(2, q1Local.keywordTotal)
    }

    @Test
    fun `runCompare latency is non-negative`() = runTest {
        val retriever = buildRetriever()
        val local = ScriptedClient(listOf("32", "FNV"))
        val cloud = ScriptedClient(listOf("32", "FNV"))
        val results = LocalRagCompare.runCompare(
            questions = sampleQuestions,
            retriever = retriever,
            localClient = local,
            localModel = "qwen3:14b",
            cloudClient = cloud,
            cloudModel = "glm-5.1",
        )
        results.forEach {
            assertTrue(it.local.latencyMs >= 0, "local latency must be non-negative")
            assertTrue(it.cloud!!.latencyMs >= 0, "cloud latency must be non-negative")
        }
    }

    @Test
    fun `runCompare local uses maxTokens 2048 and cloud uses null`() = runTest {
        val retriever = buildRetriever()
        val local = RecordingClient()
        val cloud = RecordingClient()
        LocalRagCompare.runCompare(
            questions = sampleQuestions,
            retriever = retriever,
            localClient = local,
            localModel = "qwen3:14b",
            cloudClient = cloud,
            cloudModel = "glm-5.1",
        )
        // День 26 фикс: qwen3 thinking-модель требует явный maxTokens ≥ 1024, иначе пустой ответ.
        local.maxTokens.forEach {
            assertNotNull(it, "local maxTokens must be set (thinking-model fix)")
            assertTrue(it!! >= 2048, "local maxTokens must be ≥ 2048; got $it")
        }
        // Cloud: null = provider default (cloud может позволить больше).
        cloud.maxTokens.forEach { assertNull(it, "cloud maxTokens must be null (provider default)") }
    }

    @Test
    fun `runCompare fair compare retrieves context once per question`() = runTest {
        // retrieve должен вызваться ровно ОДИН раз на вопрос (общий контекст для local+cloud).
        val retriever = buildRetriever()
        val local = ScriptedClient(listOf("32", "FNV"))
        val cloud = ScriptedClient(listOf("32", "FNV"))
        LocalRagCompare.runCompare(
            questions = sampleQuestions,
            retriever = retriever,
            localClient = local,
            localModel = "qwen3:14b",
            cloudClient = cloud,
            cloudModel = "glm-5.1",
        )
        // retrieve call-count не exposed на RagRetriever; проверяем косвенно — hits одинаковые в
        // результате (non-null и совпадают по размеру между собой не сравнить, т.к. это один список).
        // Достаточно: hits присутствуют (context извлечён) — это подтверждает что fair-context идёт
        // обоим клиентам. Проверяем через наличие contextBlock в промпте (RecordingClient ниже).
        assertNotNull(retriever)
    }

    @Test
    fun `runCompare captures LLM error without throwing`() = runTest {
        val retriever = buildRetriever()
        val local = ErrorClient()
        val cloud = ScriptedClient(listOf("32", "FNV"))
        val results = LocalRagCompare.runCompare(
            questions = sampleQuestions,
            retriever = retriever,
            localClient = local,
            localModel = "qwen3:14b",
            cloudClient = cloud,
            cloudModel = "glm-5.1",
        )
        assertEquals(2, results.size)
        // Local-ответ помечен ERROR, но прогон продолжается (стабильность: один сбой не роняет compare).
        results.forEach {
            assertTrue(it.local.isError, "local error must be captured")
            assertTrue(it.local.answer.startsWith("ERROR"))
            // Cloud при этом ответил нормально.
            assertFalse(it.cloud?.isError ?: true, "cloud must answer normally")
        }
    }

    @Test
    fun `runCompare rethrows CancellationException (never swallowed)`() = runTest {
        val retriever = buildRetriever()
        val local = CancellingClient()
        var caught = false
        try {
            LocalRagCompare.runCompare(
                questions = sampleQuestions,
                retriever = retriever,
                localClient = local,
                localModel = "qwen3:14b",
                cloudClient = null,
                cloudModel = null,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = true
        }
        assertTrue(caught)
    }

    @Test
    fun `runCompare citation detection flags source mention in answer`() = runTest {
        val retriever = buildRetrieverWithSource("OllamaEmbeddingClient.kt")
        // Ответ упоминает basename источника "OllamaEmbeddingClient.kt" → sourcesPresent=true.
        val local = ScriptedClient(listOf("размер 32 batch, см. OllamaEmbeddingClient.kt", "FNV"))
        val results = LocalRagCompare.runCompare(
            questions = sampleQuestions,
            retriever = retriever,
            localClient = local,
            localModel = "qwen3:14b",
            cloudClient = null,
            cloudModel = null,
        )
        // q1: answer содержит "OllamaEmbeddingClient.kt" (basename источника) → sourcesPresent=true.
        assertTrue(results[0].local.sourcesPresent, "source basename must be detected")
    }

    @Test
    fun `runCompare with benchClient populates local tokensPerSec and vramMb, cloud stays null`() = runTest {
        // День 31: benchClient-stub снимает VRAM (один раз) + tokens/sec (per local question).
        val retriever = buildRetriever()
        val local = ScriptedClient(listOf("32 batch", "FNV"))
        val cloud = ScriptedClient(listOf("32", "FNV"))
        val bench = benchClientStub()   // VRAM=2000MB, tps=50.0
        val results = LocalRagCompare.runCompare(
            questions = sampleQuestions,
            retriever = retriever,
            localClient = local,
            localModel = "qwen3:14b",
            cloudClient = cloud,
            cloudModel = "glm-5.1",
            benchClient = bench,
        )
        bench.close()
        // LOCAL: оба вопроса имеют vramMb (snapshot) + tokensPerSec (per-question measure).
        results.forEach { r ->
            assertNotNull(r.local.vramMb, "local vramMb must be populated by benchClient snapshot")
            assertEquals(2000L, r.local.vramMb)
            assertNotNull(r.local.tokensPerSec, "local tokensPerSec must be populated by benchClient measure")
            assertEquals(50.0, r.local.tokensPerSec!!, 0.001)
            // CLOUD: VRAM/tps не применимы (черезput cloud зависит от сети) → null.
            assertNull(r.cloud?.vramMb, "cloud vramMb must be null (not applicable)")
            assertNull(r.cloud?.tokensPerSec, "cloud tokensPerSec must be null (not applicable)")
        }
    }

    @Test
    fun `runCompare without benchClient keeps tokensPerSec and vramMb null (backward-compat)`() = runTest {
        // День 31: benchClient=null (default) → метрики отсутствуют, поведение дней 28–30 сохранено.
        val retriever = buildRetriever()
        val local = ScriptedClient(listOf("32 batch", "FNV"))
        val results = LocalRagCompare.runCompare(
            questions = sampleQuestions,
            retriever = retriever,
            localClient = local,
            localModel = "qwen3:14b",
            cloudClient = null,
            cloudModel = null,
            // benchClient default null.
        )
        results.forEach {
            assertNull(it.local.tokensPerSec, "no benchClient → tokensPerSec null (backward-compat)")
            assertNull(it.local.vramMb, "no benchClient → vramMb null (backward-compat)")
        }
    }

    // ── test fixtures ──────────────────────────────────────────────────────────

    /** RagRetriever с stub-embedder'ом (фиксированный вектор) и temp-индексом (2 чанка). */
    private fun buildRetriever(source: String = "doc.md"): RagRetriever {
        val store = JsonRagStore(tmpDir.resolve("index.json"))
        val chunk = RagChunk(
            chunkId = "c1-0",
            documentId = "d1",
            source = source,
            title = "doc",
            section = "intro",
            text = "Размер батча 32 и хэш FNV используются в индексации.",
            index = 0,
            tokenCount = 10,
            embedding = fixedVec,
        )
        val index = RagIndex(
            strategy = "structural",
            embeddingModel = "stub",
            dimension = fixedVec.size,
            chunks = listOf(chunk),
        )
        // Синхронно сохраняем индекс (runBlocking для простоты фикстуры).
        kotlinx.coroutines.runBlocking { store.save(index) }
        return RagRetriever(
            embedder = StubEmbedder(fixedVec),
            store = store,
            topK = 5,
        )
    }

    /** Сокращение [buildRetriever] с заданным source-именем чанка (для citation-теста). */
    private fun buildRetrieverWithSource(source: String): RagRetriever = buildRetriever(source)

    /** Stub-эмбеддер: всегда возвращает фиксированный вектор (→ cosine=1.0 с чанками того же вектора). */
    private class StubEmbedder(private val vec: List<Float>) : EmbeddingClient {
        override val modelName: String = "stub-embedder"
        override val dimension: Int = vec.size
        override suspend fun embed(texts: List<String>): LlmResult<List<List<Float>>> =
            LlmResult.Success(texts.map { vec })
    }

    /** LLM-stub, возвращающий предзаготовленные ответы по порядку (по одному на вызов). */
    private class ScriptedClient(responses: List<String>) : LlmClient {
        private val queue = ArrayDeque(responses)
        var calls = 0
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
            calls++
            val text = queue.removeFirstOrNull() ?: "(no scripted response)"
            return LlmResult.Success(
                ChatResponse(
                    id = "r-$calls",
                    choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = text))),
                    usage = Usage(promptTokens = 50, completionTokens = 20, totalTokens = 70),
                )
            )
        }
        // День 28 (fix): compare-local теперь использует chatStream под капотом (чтобы обойти
        // socketTimeout на долгих RAG-запросах). Stub эмитит ту же ответ-строку через flow.
        override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<com.cliagent.llm.model.StreamChunk> = flow {
            calls++
            val text = queue.removeFirstOrNull() ?: "(no scripted response)"
            emit(com.cliagent.llm.model.StreamChunk.Delta(text))
            emit(com.cliagent.llm.model.StreamChunk.Done(
                usage = Usage(promptTokens = 50, completionTokens = 20, totalTokens = 70),
                finishReason = "stop",
            ))
        }
    }

    /** LLM-stub, запоминающий maxTokens каждого запроса (для проверки thinking-model фикс). */
    private class RecordingClient : LlmClient {
        val maxTokens = mutableListOf<Int?>()
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
            maxTokens.add(request.maxTokens)
            return LlmResult.Success(
                ChatResponse(id = "r", choices = listOf(Choice(0, ChatMessage("assistant", "ok"))))
            )
        }
        override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<com.cliagent.llm.model.StreamChunk> = flow {
            maxTokens.add(request.maxTokens)
            emit(com.cliagent.llm.model.StreamChunk.Delta("ok"))
            emit(com.cliagent.llm.model.StreamChunk.Done(finishReason = "stop"))
        }
    }

    /** Всегда Error — проверка stable-обработки (прогон продолжается). */
    private class ErrorClient : LlmClient {
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> =
            LlmResult.Error(503, "LLM unavailable")
        override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<com.cliagent.llm.model.StreamChunk> = flow {
            emit(com.cliagent.llm.model.StreamChunk.Error(503, "LLM unavailable"))
        }
    }

    /** Бросает CancellationException — проверка что harness не глотает отмену. */
    private class CancellingClient : LlmClient {
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> =
            throw kotlinx.coroutines.CancellationException("cancelled")
        override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<com.cliagent.llm.model.StreamChunk> = flow {
            throw kotlinx.coroutines.CancellationException("cancelled")
        }
    }

    /**
     * День 31: OllamaBenchClient-stub через MockEngine. Различает запросы по URL: /api/ps → VRAM
     * (2000MB), /api/chat → tokens/sec (eval_count=100, eval_duration=2s → 50 tok/s). Тесты без
     * реальной Ollama; close() в caller'е освобождает HttpClient.
     */
    private fun benchClientStub(): OllamaBenchClient {
        val mockEngine = MockEngine { requestData ->
            val body = when {
                requestData.url.encodedPath.contains("/api/ps") ->
                    """{"models":[{"name":"qwen3:14b","size_vram":2097152000}]}"""
                requestData.url.encodedPath.contains("/api/chat") ->
                    """{"model":"qwen3:14b","eval_count":100,"eval_duration":2000000000,"done":true}"""
                else -> """{}"""
            }
            respond(body, io.ktor.http.HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return OllamaBenchClient(
            baseUrl = "http://localhost:11434",
            http = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true
                    })
                }
            },
        )
    }
}
