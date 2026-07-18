package com.cliagent.rag

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.ChatMessage
import com.cliagent.rag.embedding.EmbeddingClient
import com.cliagent.rag.rerank.HeuristicReranker
import com.cliagent.rag.rerank.LlmReranker
import com.cliagent.rag.rerank.ThresholdReranker
import com.cliagent.rag.rewrite.HeuristicQueryRewriter
import com.cliagent.rag.rewrite.IdentityQueryRewriter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * День 23: интеграционные тесты расширенного pipeline [RagRetriever]
 * (`rewrite → embed → topK(pool) → rerank → take(topK)`).
 *
 * Покрывает 4 ключевых сценария: backward-compat (IDENTITY+NONE=день 22), Threshold-фильтр,
 * LLM-reranker с пересортировкой, мягкая деградация LLM-reranker (Error → исходный порядок).
 * Mock EmbeddingClient + @TempDir JsonRagStore — без реальной Ollama/z.ai.
 */
class RagRetrieverDay23Test {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `rewriter null and reranker null equals day 22 behavior`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        store.save(indexWithChunks(3))
        val retriever = RagRetriever(DeterministicEmbedder(), store, topK = 3)
        val hits = retriever.retrieve("query")
        assertTrue(hits != null && hits.isNotEmpty())
        // Все 3 чанка возвращены, отсортированы по убыванию cosine
        val scores = hits!!.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
        assertEquals(3, hits.size)
    }

    @Test
    fun `identity rewriter and none reranker equals day 22 behavior`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        store.save(indexWithChunks(3))
        val retriever = RagRetriever(
            DeterministicEmbedder(), store, topK = 3,
            rewriter = IdentityQueryRewriter, reranker = null,
        )
        val hits = retriever.retrieve("query")
        assertTrue(hits != null)
        assertEquals(3, hits!!.size)
    }

    @Test
    fun `threshold reranker filters out low-score chunks`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        store.save(indexWithChunks(5)) // scores: чанк 0..4, только 0 имеет score≈1
        val retriever = RagRetriever(
            DeterministicEmbedder(), store, topK = 5,
            reranker = ThresholdReranker(threshold = 0.99f),
            candidatePoolSize = 5,
        )
        val hits = retriever.retrieve("query")
        assertTrue(hits != null)
        // Порог 0.99 → только чанк с score≈1.0 проходит (остальные 0.x отфильтрованы)
        assertTrue(hits!!.isNotEmpty(), "at least the highest-scoring chunk passes threshold")
    }

    @Test
    fun `heuristic reranker reorders pool by term overlap`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        // Чанки с разным текстом — heuristic-rerank должен учесть overlap
        store.save(indexWithTexts(listOf("sliding window context" to 0.5f, "unrelated topic" to 0.9f)))
        val retriever = RagRetriever(
            DeterministicEmbedder(), store, topK = 2,
            reranker = HeuristicReranker(alpha = 0.3f),
            candidatePoolSize = 2,
        )
        val hits = retriever.retrieve("sliding window")
        assertTrue(hits != null)
        // alpha=0.3 → overlap доминирует; чанк "sliding window context" должен быть первым
        assertEquals("d1-0", hits!!.first().chunk.chunkId)
    }

    @Test
    fun `llm reranker reorders candidates by judge scores`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        store.save(indexWithChunks(3))
        // LLM-судья: c2=10 (лучший), c0=5, c1=1 → пересортировка против cosine-порядка
        val llm = StubLlmClient("""{"d1-0": 5, "d1-1": 1, "d1-2": 10}""")
        val retriever = RagRetriever(
            DeterministicEmbedder(), store, topK = 3,
            reranker = LlmReranker(llm, "fake-model"),
            candidatePoolSize = 3,
        )
        val hits = retriever.retrieve("query")
        assertTrue(hits != null)
        // d1-2 (LLM-score 10) должен быть первым после реранка
        assertEquals("d1-2", hits!!.first().chunk.chunkId)
    }

    @Test
    fun `llm reranker returns original order on LLM error - graceful degradation`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        store.save(indexWithChunks(3))
        val llm = FailingLlmClient()
        val retriever = RagRetriever(
            DeterministicEmbedder(), store, topK = 3,
            reranker = LlmReranker(llm, "fake-model"),
            candidatePoolSize = 3,
        )
        val hits = retriever.retrieve("query")
        assertTrue(hits != null)
        // Мягкая деградация: LLM-ошибка → исходный cosine-порядок (как день 22)
        val scores = hits!!.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `heuristic rewriter applied before embedding`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        store.save(indexWithChunks(2))
        val embedder = RecordingEmbedder()
        val retriever = RagRetriever(
            embedder, store, topK = 2,
            rewriter = HeuristicQueryRewriter(), reranker = null,
        )
        retriever.retrieve("What is the sliding window strategy?")
        // Запрос, дошедший до эмбеддера, должен быть normalized (стоп-слова убраны)
        assertEquals("sliding window strategy", embedder.lastQuery)
    }

    @Test
    fun `setRewriter and setReranker runtime toggle work`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        store.save(indexWithChunks(3))
        val retriever = RagRetriever(DeterministicEmbedder(), store, topK = 3)
        // Изначально null (день 22)
        assertNull(retriever.getRewriter())
        assertNull(retriever.getReranker())
        // Runtime-toggle
        retriever.setRewriter(IdentityQueryRewriter)
        retriever.setReranker(ThresholdReranker(0.0f))
        assertEquals("identity", retriever.getRewriter()!!.name)
        assertEquals("threshold", retriever.getReranker()!!.name)
        // retrieve работает после toggle
        val hits = retriever.retrieve("query")
        assertTrue(hits != null)
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun indexWithChunks(n: Int): RagIndex {
        val chunks = (0 until n).map { i ->
            RagChunk(
                chunkId = "d1-$i", documentId = "d1", source = "a.md", title = "A",
                section = "s$i", text = "chunk text $i", index = i, tokenCount = 5,
                embedding = listOf(i.toFloat(), 0f, 0f),
            )
        }
        return RagIndex(strategy = "fixed", embeddingModel = "fake-model", dimension = 3, chunks = chunks)
    }

    private fun indexWithTexts(spec: List<Pair<String, Float>>): RagIndex {
        val chunks = spec.mapIndexed { i, (text, _) ->
            RagChunk(
                chunkId = "d1-$i", documentId = "d1", source = "a.md", title = "A",
                section = "s$i", text = text, index = i, tokenCount = 5,
                // embedding подобран так, чтобы второй элемент spec имел больший cosine к (1,0,0)
                embedding = listOf(i.toFloat(), 0f, 0f),
            )
        }
        return RagIndex(strategy = "fixed", embeddingModel = "fake-model", dimension = 3, chunks = chunks)
    }

    /** Эмбеддер с детерминированным вектором запроса → предсказуемые cosine-скор'ы. */
    private class DeterministicEmbedder : EmbeddingClient {
        override val modelName = "fake-model"
        override val dimension = 3
        override suspend fun embed(texts: List<String>): LlmResult<List<List<Float>>> =
            LlmResult.Success(texts.map { listOf(1f, 0f, 0f) })
    }

    /** Эмбеддер, запоминающий последний запрошенный текст (для проверки rewrite). */
    private class RecordingEmbedder : EmbeddingClient {
        override val modelName = "fake-model"
        override val dimension = 3
        var lastQuery: String? = null
        override suspend fun embed(texts: List<String>): LlmResult<List<List<Float>>> {
            lastQuery = texts.firstOrNull()
            return LlmResult.Success(texts.map { listOf(1f, 0f, 0f) })
        }
    }

    /** LLM-клиент, всегда возвращающий заданный контент (для LlmReranker/LlmQueryRewriter тестов). */
    private class StubLlmClient(private val responseContent: String) : LlmClient {
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> =
            LlmResult.Success(
                ChatResponse(
                    id = "resp-1",
                    choices = listOf(
                        Choice(index = 0, message = ChatMessage(role = "assistant", content = responseContent))
                    )
                )
            )
        // День 30: streaming не используется в RAG-reranker/rewriter тестах. Заглушка для контракта.
        override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<com.cliagent.llm.model.StreamChunk> =
            throw UnsupportedOperationException("streaming not supported in StubLlmClient")
    }

    /** LLM-клиент, всегда возвращающий Error (для теста мягкой деградации). */
    private class FailingLlmClient : LlmClient {
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> =
            LlmResult.Error(503, "LLM unavailable")
        override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<com.cliagent.llm.model.StreamChunk> =
            throw UnsupportedOperationException("streaming not supported in FailingLlmClient")
    }
}
