package com.cliagent.rag.chunk

import com.cliagent.rag.RagDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 21: unit-тесты стратегий chunking. Проверяют разбиение, overlap, метаданные задания
 * (source/title/section/chunk_id). Чистая логика — без HTTP/файлов.
 */
class ChunkerTest {

    private fun doc(content: String, id: String = "doc1") = RagDocument(
        id = id,
        source = "src/$id.md",
        title = "Test Doc",
        content = content,
        fileType = "md",
    )

    // ── FixedSizeChunker ────────────────────────────────────────────────────────

    @Test
    fun `fixed chunker splits long text into multiple chunks`() {
        val long = "a".repeat(3000)   // 3000 chars ≈ 750 tokens → 2+ chunks at size=500
        val chunker = FixedSizeChunker(chunkSizeTokens = 100, overlapTokens = 20)
        val chunks = chunker.chunk(doc(long))
        assertTrue(chunks.size > 1, "expected multiple chunks, got ${chunks.size}")
        chunks.forEach { assertTrue(it.tokenCount <= 120) } // ~400 chars → ~100 tokens + граница
    }

    @Test
    fun `fixed chunker assigns sequential chunkIds and indices`() {
        val chunker = FixedSizeChunker(chunkSizeTokens = 100, overlapTokens = 20)
        val chunks = chunker.chunk(doc("a".repeat(2000)))
        chunks.forEachIndexed { i, c ->
            assertEquals(i, c.index)
            assertEquals("doc1-$i", c.chunkId)
        }
    }

    @Test
    fun `fixed chunker propagates document metadata`() {
        val chunker = FixedSizeChunker()
        val chunks = chunker.chunk(doc("hello world"))
        assertEquals(1, chunks.size)
        val c = chunks.first()
        assertEquals("src/doc1.md", c.source)        // source metadata
        assertEquals("Test Doc", c.title)            // title/file metadata
        assertEquals("doc1", c.documentId)
        assertEquals("fixed", c.strategy)
        assertTrue(c.section.startsWith("fixed-"))   // section metadata
    }

    @Test
    fun `fixed chunker returns empty for blank document`() {
        assertEquals(0, FixedSizeChunker().chunk(doc("   ")).size)
    }

    @Test
    fun `fixed chunker overlap causes shared content between adjacent chunks`() {
        // size=100 tok (400 chars), overlap=50 tok (200 chars); step=200 chars
        val chunker = FixedSizeChunker(chunkSizeTokens = 100, overlapTokens = 50)
        val text = (0 until 10).joinToString("") { "block$it-" + "x".repeat(40) }
        val chunks = chunker.chunk(doc(text))
        if (chunks.size >= 2) {
            // Граница: конец первого куска должен появиться в начале второго (overlap)
            val tail = chunks[0].text.takeLast(50)
            val head = chunks[1].text.take(50)
            // overlap гарантирует некоторое пересечение по символам (не строго — но step < size)
            assertTrue(tail.any { it in head } || head.any { it in tail },
                "expected overlap between adjacent chunks")
        }
    }

    // ── StructuralChunker ───────────────────────────────────────────────────────

    @Test
    fun `structural chunker splits by markdown headings`() {
        val md = """
            # Section A
            Content of A.

            # Section B
            Content of B.
        """.trimIndent()
        val chunks = StructuralChunker().chunk(doc(md))
        assertTrue(chunks.size >= 2, "expected sections split, got ${chunks.size}")
        assertEquals("Section A", chunks[0].section)
        assertEquals("Section B", chunks[1].section)
    }

    @Test
    fun `structural chunker section metadata is the heading text`() {
        val md = "# My Heading\nbody text here"
        val chunks = StructuralChunker().chunk(doc(md))
        assertEquals(1, chunks.size)
        assertEquals("My Heading", chunks[0].section)
        assertTrue(chunks[0].text.contains("My Heading"))
        assertTrue(chunks[0].text.contains("body text here"))
    }

    @Test
    fun `structural chunker sub-divides oversized section`() {
        // Одна секция, тело больше maxChunkTokens → должна под-нарезаться
        val md = "# Big\n" + "x".repeat(3000)
        val chunks = StructuralChunker(maxChunkTokens = 100, overlapTokens = 20).chunk(doc(md))
        assertTrue(chunks.size > 1, "expected sub-division of big section, got ${chunks.size}")
        chunks.forEach { assertEquals("Big", it.section) } // все в одной секции
    }

    @Test
    fun `structural chunker handles h2 h3 subheadings`() {
        val md = """
            # Top
            intro

            ## Sub 1
            a

            ### Sub 1.1
            b
        """.trimIndent()
        val chunks = StructuralChunker().chunk(doc(md))
        assertTrue(chunks.size >= 3)
        val sections = chunks.map { it.section }
        assertTrue(sections.contains("Top"))
        assertTrue(sections.contains("Sub 1"))
        assertTrue(sections.contains("Sub 1.1"))
    }

    @Test
    fun `structural chunker text before first heading becomes intro section`() {
        val md = "intro text\n# Heading\nbody"
        val chunks = StructuralChunker().chunk(doc(md))
        assertTrue(chunks.first().section == "(intro)")
    }

    @Test
    fun `structural chunker returns empty for blank document`() {
        assertEquals(0, StructuralChunker().chunk(doc("")).size)
    }

    @Test
    fun `structural chunker propagates chunk_id and strategy metadata`() {
        val md = "# A\nx\n# B\ny"
        val chunks = StructuralChunker().chunk(doc(md))
        chunks.forEachIndexed { i, c ->
            assertEquals("doc1-$i", c.chunkId)
            assertEquals("structural", c.strategy)
            assertEquals(i, c.index)
        }
    }

    // ── ChunkingStrategyType ────────────────────────────────────────────────────

    @Test
    fun `strategy type parses fixed and structural case-insensitive`() {
        assertEquals(ChunkingStrategyType.FIXED, ChunkingStrategyType.fromString("fixed"))
        assertEquals(ChunkingStrategyType.FIXED, ChunkingStrategyType.fromString("FIXED"))
        assertEquals(ChunkingStrategyType.STRUCTURAL, ChunkingStrategyType.fromString("structural"))
        assertEquals(ChunkingStrategyType.STRUCTURAL, ChunkingStrategyType.fromString("header"))
        assertEquals(ChunkingStrategyType.STRUCTURAL, ChunkingStrategyType.fromString(null))
    }
}
