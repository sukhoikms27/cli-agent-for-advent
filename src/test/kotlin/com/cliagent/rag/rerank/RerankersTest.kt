package com.cliagent.rag.rerank

import com.cliagent.rag.RagChunk
import com.cliagent.rag.ScoredChunk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 23: тесты [ThresholdReranker] и [HeuristicReranker]. Чистые функции — детерминированные
 * проверки без моков. Пустые списки, пороги, term-overlap пересортировка, edge-кейсы alpha.
 */
class RerankersTest {

    private fun chunk(id: String, text: String, score: Float) =
        ScoredChunk(
            RagChunk(
                chunkId = id, documentId = "d1", source = "a.md", title = "A",
                section = "s", text = text, index = 0, tokenCount = 5,
            ),
            score,
        )

    // ── ThresholdReranker ─────────────────────────────────────────────────────

    @Test
    fun `threshold filters out low-score chunks`() = runTest {
        val candidates = listOf(
            chunk("c0", "high", 0.8f),
            chunk("c1", "low", 0.3f),
            chunk("c2", "mid", 0.6f),
        )
        val out = ThresholdReranker(0.5f).rerank("q", candidates)
        assertEquals(listOf("c0", "c2"), out.map { it.chunk.chunkId })
    }

    @Test
    fun `threshold zero keeps all chunks - day 22 behavior`() = runTest {
        val candidates = listOf(chunk("c0", "a", 0.1f), chunk("c1", "b", 0.5f))
        val out = ThresholdReranker(0.0f).rerank("q", candidates)
        assertEquals(2, out.size)
    }

    @Test
    fun `threshold on empty returns empty`() = runTest {
        val out = ThresholdReranker(0.5f).rerank("q", emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `threshold preserves descending order`() = runTest {
        val candidates = listOf(
            chunk("c0", "a", 0.9f),
            chunk("c1", "b", 0.7f),
            chunk("c2", "c", 0.5f),
        )
        val out = ThresholdReranker(0.4f).rerank("q", candidates)
        assertEquals(listOf(0.9f, 0.7f, 0.5f), out.map { it.score })
    }

    @Test
    fun `threshold name property`() {
        assertEquals("threshold", ThresholdReranker().name)
    }

    // ── HeuristicReranker ─────────────────────────────────────────────────────

    @Test
    fun `heuristic promotes chunk sharing terms with query over higher-cosine`() = runTest {
        // c0: высокий cosine, но нет общих терминов с запросом
        // c1: низкий cosine, но разделяет термины "sliding"/"window" с запросом.
        // alpha=0.3 → overlap доминирует над cosine (finalScore(c1) > finalScore(c0)).
        val candidates = listOf(
            chunk("c0", "completely unrelated text", 0.9f),
            chunk("c1", "sliding window strategy", 0.2f),
        )
        val out = HeuristicReranker(alpha = 0.3f).rerank("sliding window", candidates)
        // c1 должен подняться наверх за счёт overlap (jaccard≈0.67 vs 0)
        assertEquals("c1", out.first().chunk.chunkId)
    }

    @Test
    fun `heuristic alpha 1 equals cosine order - day 22 behavior`() = runTest {
        val candidates = listOf(
            chunk("c0", "unrelated", 0.4f),
            chunk("c1", "also unrelated", 0.8f),
        )
        val out = HeuristicReranker(alpha = 1.0f).rerank("query", candidates)
        // alpha=1 → только cosine → c1 (0.8) выше c0 (0.4)
        assertEquals(listOf("c1", "c0"), out.map { it.chunk.chunkId })
    }

    @Test
    fun `heuristic on empty returns empty`() = runTest {
        val out = HeuristicReranker().rerank("q", emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `heuristic empty query uses cosine only via zero overlap`() = runTest {
        val candidates = listOf(chunk("c0", "a", 0.3f), chunk("c1", "b", 0.7f))
        val out = HeuristicReranker(alpha = 0.5f).rerank("", candidates)
        // пустой запрос → jaccard=0 → finalScore=0.5*cosine → порядок по cosine сохраняется
        assertEquals(listOf("c1", "c0"), out.map { it.chunk.chunkId })
    }

    @Test
    fun `heuristic name property`() {
        assertEquals("heuristic", HeuristicReranker().name)
    }

    // ── RerankerType enum ─────────────────────────────────────────────────────

    @Test
    fun `enum fromString resolves aliases and defaults to NONE`() {
        assertEquals(RerankerType.NONE, RerankerType.fromString(null))
        assertEquals(RerankerType.NONE, RerankerType.fromString(""))
        assertEquals(RerankerType.NONE, RerankerType.fromString("none"))
        assertEquals(RerankerType.NONE, RerankerType.fromString("off"))
        assertEquals(RerankerType.THRESHOLD, RerankerType.fromString("threshold"))
        assertEquals(RerankerType.THRESHOLD, RerankerType.fromString("cutoff"))
        assertEquals(RerankerType.HEURISTIC, RerankerType.fromString("heuristic"))
        assertEquals(RerankerType.HEURISTIC, RerankerType.fromString("overlap"))
        assertEquals(RerankerType.LLM, RerankerType.fromString("llm"))
        assertEquals(RerankerType.LLM, RerankerType.fromString("judge"))
        assertEquals(RerankerType.LLM, RerankerType.fromString("crossencoder"))
        assertEquals(RerankerType.NONE, RerankerType.fromString("unknown"))
    }
}
