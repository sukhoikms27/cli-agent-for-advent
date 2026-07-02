package com.cliagent.rag

import com.cliagent.llm.LlmResult
import com.cliagent.rag.chunk.FixedSizeChunker
import com.cliagent.rag.chunk.StructuralChunker
import com.cliagent.rag.embedding.EmbeddingClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * День 21: end-to-end тест [RagIndexer] с фейковым [EmbeddingClient] (детерминированные векторы) и
 * temp-путём [JsonRagStore]. Без реальной Ollama: проверяет, что chunk → embed → save связывается.
 */
class RagIndexerTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `index builds chunks with embeddings from documents`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        val embedder = FakeEmbedder()
        val indexer = RagIndexer(FixedSizeChunker(chunkSizeTokens = 50, overlapTokens = 10), embedder, store)

        val docs = listOf(
            RagDocument("d1", "a.md", "Doc A", "alpha beta gamma delta epsilon zeta eta theta", "md"),
            RagDocument("d2", "b.md", "Doc B", "one two three four five six seven eight nine ten", "md"),
        )
        val index = indexer.index(docs)

        assertNotNull(index)
        val idx = index!!
        assertEquals("fixed", idx.strategy)
        assertEquals("fake-model", idx.embeddingModel)
        assertEquals(3, idx.dimension)
        assertEquals(2, idx.documents.size)
        assertTrue(idx.chunks.isNotEmpty())
        assertTrue(idx.chunks.all { it.embedding != null }, "all chunks must have embeddings")
        assertTrue(idx.chunks.all { it.embedding!!.size == 3 })
        assertEquals(idx.chunks.size, idx.embeddedChunks.size)

        // Проверка персистентности — сохранённый файл грузится
        val loaded = store.load()
        assertEquals(idx.chunks.size, loaded.chunks.size)
    }

    @Test
    fun `index returns null when embedder fails`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        val indexer = RagIndexer(FixedSizeChunker(), FailingEmbedder(), store)
        val docs = listOf(RagDocument("d1", "a.md", "A", "some content here", "md"))
        val index = indexer.index(docs)
        assertNull(index, "indexer must return null on embedding error")
    }

    @Test
    fun `index with structural chunker produces section metadata`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        val indexer = RagIndexer(StructuralChunker(), FakeEmbedder(), store)
        val docs = listOf(
            RagDocument("d1", "a.md", "A", "# Heading One\nbody text\n# Heading Two\nmore text", "md"),
        )
        val index = indexer.index(docs)
        assertNotNull(index)
        val sections = index!!.chunks.map { it.section }
        assertTrue(sections.contains("Heading One"))
        assertTrue(sections.contains("Heading Two"))
    }

    @Test
    fun `index without embedder saves chunks with null embeddings`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        val indexer = RagIndexer(FixedSizeChunker(), embedder = null, store)
        val docs = listOf(RagDocument("d1", "a.md", "A", "content content content", "md"))
        val index = indexer.index(docs)
        assertNotNull(index)
        assertTrue(index!!.chunks.isNotEmpty())
        assertTrue(index.chunks.all { it.embedding == null })
        assertEquals(0, index.embeddedChunks.size)
    }

    @Test
    fun `index handles empty corpus gracefully`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        val indexer = RagIndexer(FixedSizeChunker(), FakeEmbedder(), store)
        val index = indexer.index(emptyList())
        assertNotNull(index)
        assertEquals(0, index!!.chunks.size)
        assertEquals(0, index.documents.size)
    }

    @Test
    fun `index reports progress via callback`() = runTest {
        val store = JsonRagStore(tmp.resolve("index.json"))
        val indexer = RagIndexer(FixedSizeChunker(chunkSizeTokens = 20, overlapTokens = 5), FakeEmbedder(), store)
        val docs = listOf(RagDocument("d1", "a.md", "A", "word ".repeat(200), "md"))
        val progress = mutableListOf<Pair<Int, Int>>()
        indexer.index(docs) { done, total -> progress.add(done to total) }
        assertTrue(progress.isNotEmpty(), "progress callback should fire")
        assertTrue(progress.all { it.first <= it.second }, "processed <= total")
        // Последний отчёт должен достичь total
        assertEquals(progress.last().second, progress.last().first)
    }

    // ── Fake embedder: детерминированные векторы из хэша текста (без сети) ─────────

    private class FakeEmbedder : EmbeddingClient {
        override val modelName = "fake-model"
        override val dimension = 3
        override suspend fun embed(texts: List<String>): LlmResult<List<List<Float>>> {
            val vecs = texts.map { text ->
                // Простой детерминированный вектор: 3 числа из суммы кодов символов
                val h = text.hashCode()
                listOf((h and 0xFF) / 255f, ((h shr 8) and 0xFF) / 255f, ((h shr 16) and 0xFF) / 255f)
            }
            return LlmResult.Success(vecs)
        }
    }

    private class FailingEmbedder : EmbeddingClient {
        override val modelName = "fail"
        override val dimension = 3
        override suspend fun embed(texts: List<String>) = LlmResult.Error(500, "simulated failure")
    }
}
