package com.cliagent.rag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 21: unit-тесты векторной арифметики (косинусное сходство + topK). Чистая логика — без
 * HTTP/файлов; эталонные векторы проверяют формулу косинуса.
 */
class VectorMathTest {

    private fun approx(actual: Float, expected: Float, eps: Float = 1e-5f): Boolean =
        kotlin.math.abs(actual - expected) < eps

    @Test
    fun `cosine of identical vectors is 1`() {
        val v = listOf(0.1f, 0.2f, 0.3f, 0.4f)
        assertTrue(approx(cosine(v, v), 1.0f))
    }

    @Test
    fun `cosine of orthogonal vectors is 0`() {
        // (1,0) · (0,1) = 0; углы 90°
        assertTrue(approx(cosine(listOf(1f, 0f), listOf(0f, 1f)), 0.0f))
    }

    @Test
    fun `cosine of opposite vectors is -1`() {
        assertTrue(approx(cosine(listOf(1f, 1f), listOf(-1f, -1f)), -1.0f))
    }

    @Test
    fun `cosine of known vectors matches hand calculation`() {
        // a=(1,2,3), b=(4,5,6): dot=4+10+18=32; |a|=√14, |b|=√77; cos=32/(√14·√77)≈0.9746
        val a = listOf(1f, 2f, 3f)
        val b = listOf(4f, 5f, 6f)
        assertTrue(approx(cosine(a, b), 0.974632f, 1e-4f))
    }

    @Test
    fun `cosine of empty vector is 0`() {
        assertEquals(0f, cosine(emptyList(), listOf(1f)))
        assertEquals(0f, cosine(listOf(1f), emptyList()))
    }

    @Test
    fun `cosine of zero vector is 0 (no division by zero)`() {
        assertEquals(0f, cosine(listOf(0f, 0f, 0f), listOf(1f, 2f, 3f)))
    }

    @Test
    fun `topK returns chunks sorted by descending similarity`() {
        val q = listOf(1f, 0f)
        val chunks = listOf(
            chunk("c0", listOf(1f, 0f)),    // cos=1.0 (identical)
            chunk("c1", listOf(0f, 1f)),    // cos=0.0 (orthogonal)
            chunk("c2", listOf(0.9f, 0.1f)),// cos≈0.99
        )
        val top = topK(q, chunks, k = 2)
        assertEquals(2, top.size)
        assertEquals("c0", top[0].chunk.chunkId)   // наивысший cos
        assertEquals("c2", top[1].chunk.chunkId)
        assertTrue(top[0].score >= top[1].score)
    }

    @Test
    fun `topK skips chunks without embedding`() {
        val q = listOf(1f, 0f)
        val chunks = listOf(
            chunk("emb", listOf(1f, 0f)),
            chunk("noemb", null),
        )
        val top = topK(q, chunks, k = 5)
        assertEquals(1, top.size)
        assertEquals("emb", top[0].chunk.chunkId)
    }

    @Test
    fun `topK returns fewer than k when not enough chunks`() {
        val top = topK(listOf(1f), listOf(chunk("only", listOf(1f))), k = 10)
        assertEquals(1, top.size)
    }

    @Test
    fun `topK with k=0 returns empty`() {
        assertEquals(0, topK(listOf(1f), listOf(chunk("c", listOf(1f))), k = 0).size)
    }

    private fun chunk(id: String, embedding: List<Float>?): RagChunk = RagChunk(
        chunkId = id,
        documentId = "doc",
        source = "src",
        title = "title",
        section = "sec",
        text = "text",
        index = 0,
        tokenCount = 5,
        embedding = embedding,
        strategy = "test",
    )
}
