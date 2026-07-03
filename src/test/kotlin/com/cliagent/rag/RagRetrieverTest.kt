package com.cliagent.rag

import com.cliagent.llm.LlmResult
import com.cliagent.rag.embedding.EmbeddingClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * День 22: тесты [RagRetriever]. Фейковый [EmbeddingClient] (детерминированные векторы) и temp-путь
 * [JsonRagStore] — без реальной Ollama. Покрывает три сценария: пустой индекс, ошибка эмбеддинга,
 * нормальный retrieval с сортировкой по убыванию сходства.
 */
class RagRetrieverTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `retrieve returns null when index has no embedded chunks`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        val retriever = RagRetriever(FakeEmbedder(), store, topK = 3)
        assertNull(retriever.retrieve("anything"))
    }

    @Test
    fun `retrieve returns null on embedding error — graceful degradation`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        store.save(indexWithChunks(2))
        val retriever = RagRetriever(FailingEmbedder(), store, topK = 3)
        assertNull(retriever.retrieve("query"))
    }

    @Test
    fun `retrieve returns top-K chunks sorted by descending score`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        store.save(indexWithChunks(3))
        val retriever = RagRetriever(FakeEmbedder(), store, topK = 3)
        val hits = retriever.retrieve("query")
        assertTrue(hits != null && hits.isNotEmpty())
        // Отсортированы по убыванию score
        val scores = hits!!.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
        assertTrue(hits.size <= 3)
    }

    @Test
    fun `retrieve respects topK limit`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        store.save(indexWithChunks(5))
        val retriever = RagRetriever(FakeEmbedder(), store, topK = 2)
        val hits = retriever.retrieve("query")
        assertTrue(hits != null)
        assertEquals(2, hits!!.size)
    }

    @Test
    fun `retrieve falls back to fallbackStore when primary is empty — day 21 bug-fix`() = runTest {
        // Симуляция бага дня 21: основной index.json пуст, индекс лежит в per-strategy файле.
        val emptyPrimary = JsonRagStore(tmp.resolve("index.json"))   // не сохраняем → пустой
        val fallback = JsonRagStore(tmp.resolve("index-structural.json"))
        fallback.save(indexWithChunks(3))
        val retriever = RagRetriever(FakeEmbedder(), emptyPrimary, topK = 3, fallbackStore = fallback)
        val hits = retriever.retrieve("query")
        assertTrue(hits != null, "fallback should provide the index when primary is empty")
        assertEquals(3, hits!!.size)
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Сохраняет индекс с N чанками, у каждого — embedding. */
    private suspend fun indexWithChunks(n: Int): RagIndex {
        val chunks = (0 until n).map { i ->
            RagChunk(
                chunkId = "d1-$i", documentId = "d1", source = "a.md", title = "A",
                section = "s$i", text = "chunk text $i", index = i, tokenCount = 5,
                embedding = listOf(i.toFloat(), 0f, 0f),
            )
        }
        return RagIndex(strategy = "fixed", embeddingModel = "fake-model", dimension = 3, chunks = chunks)
    }

    private class FakeEmbedder : EmbeddingClient {
        override val modelName = "fake-model"
        override val dimension = 3
        override suspend fun embed(texts: List<String>): LlmResult<List<List<Float>>> {
            // Вектор запроса = (0, 0, 0)→0 сходства; используем (1,0,0) чтобы все чанки имели положительный score.
            val h = texts.joinToString().hashCode()
            return LlmResult.Success(texts.map { listOf(1f, (h and 0xFF) / 255f, 0f) })
        }
    }

    private class FailingEmbedder : EmbeddingClient {
        override val modelName = "fail"
        override val dimension = 3
        override suspend fun embed(texts: List<String>) = LlmResult.Error(500, "simulated failure")
    }
}
