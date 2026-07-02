package com.cliagent.rag

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * День 21: unit-тесты [JsonRagStore] (JSON-персистентность индекса). Зеркалирует паттерн
 * JsonLongTermStore: temp-путь (@TempDir), round-trip, graceful на отсутствии/битом файле.
 */
class JsonRagStoreTest {

    @TempDir
    lateinit var tmp: Path

    private fun store() = JsonRagStore(tmp.resolve("index.json"))

    @Test
    fun `load returns empty when file absent`() = runTest {
        val idx = store().load()
        assertEquals("empty", idx.strategy)
        assertTrue(idx.chunks.isEmpty())
    }

    @Test
    fun `save then load round-trips index with chunks and embeddings`() = runTest {
        val s = store()
        val original = RagIndex(
            strategy = "fixed",
            embeddingModel = "nomic-embed-text",
            dimension = 768,
            documents = listOf(RagDocument(id = "d1", source = "a.md", title = "A", content = "x", fileType = "md")),
            chunks = listOf(
                RagChunk(
                    chunkId = "d1-0", documentId = "d1", source = "a.md", title = "A",
                    section = "fixed-0", text = "hello", index = 0, tokenCount = 2,
                    embedding = listOf(0.1f, 0.2f, 0.3f), strategy = "fixed",
                )
            ),
            createdAt = 12345L,
        )
        s.save(original)
        val loaded = s.load()
        assertEquals("fixed", loaded.strategy)
        assertEquals("nomic-embed-text", loaded.embeddingModel)
        assertEquals(768, loaded.dimension)
        assertEquals(1, loaded.documents.size)
        assertEquals(1, loaded.chunks.size)
        assertEquals(3, loaded.chunks[0].embedding!!.size)
        assertEquals(0.3f, loaded.chunks[0].embedding!![2])
        assertEquals(1, loaded.embeddedChunks.size)
    }

    @Test
    fun `load returns default when file is corrupt`() = runTest {
        val file = tmp.resolve("index.json")
        java.nio.file.Files.writeString(file, "{ this is not valid json }", Charsets.UTF_8)
        val idx = JsonRagStore(file).load()
        assertEquals("corrupt", idx.strategy)
        assertTrue(idx.chunks.isEmpty())
    }

    @Test
    fun `clear writes empty index`() = runTest {
        val s = store()
        s.save(
            RagIndex(
                strategy = "fixed",
                chunks = listOf(RagChunk("c0", "d", "s", "t", "sec", "txt", 0)),
            )
        )
        s.clear("reset")
        val idx = s.load()
        assertEquals("reset", idx.strategy)
        assertTrue(idx.chunks.isEmpty())
    }

    @Test
    fun `schema evolution - old index without new fields loads`() = runTest {
        // Поле embedding может отсутствовать в старом индексе (null default) → должно грузиться.
        val file = tmp.resolve("index.json")
        java.nio.file.Files.writeString(
            file,
            """{"strategy":"fixed","chunks":[{"chunkId":"c0","documentId":"d","source":"s","title":"t","section":"sec","text":"txt","index":0}]}""",
            Charsets.UTF_8,
        )
        val idx = JsonRagStore(file).load()
        assertEquals(1, idx.chunks.size)
        assertEquals(null, idx.chunks[0].embedding)   // absent field → default null
        assertEquals(0, idx.embeddedChunks.size)       // нет векторов
    }
}
