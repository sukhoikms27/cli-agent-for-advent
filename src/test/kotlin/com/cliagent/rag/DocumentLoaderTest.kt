package com.cliagent.rag

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * День 21: unit-тесты [DocumentLoader] (обход корпуса .md/.kt → RagDocument). Temp-каталог,
 * проверка стабильных id, title из H1, skip мусорных директорий (build/).
 */
class DocumentLoaderTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `loads markdown and kotlin files`() = runTest {
        Files.writeString(tmp.resolve("a.md"), "# Title A\n\nbody", Charsets.UTF_8)
        Files.writeString(tmp.resolve("b.kt"), "package x\nclass B", Charsets.UTF_8)
        Files.writeString(tmp.resolve("c.txt"), "ignored", Charsets.UTF_8) // не поддерживается
        val docs = DocumentLoader(listOf(tmp.toString())).load()
        assertEquals(2, docs.size)
        val titles = docs.map { it.title }
        assertTrue(titles.contains("Title A"))
        assertTrue(titles.contains("b.kt"))
    }

    @Test
    fun `title falls back to filename when no H1`() = runTest {
        Files.writeString(tmp.resolve("notes.md"), "just some text without heading", Charsets.UTF_8)
        val docs = DocumentLoader(listOf(tmp.toString())).load()
        assertEquals(1, docs.size)
        assertEquals("notes.md", docs[0].title)
    }

    @Test
    fun `document id is stable across runs (idempotent reindex)`() = runTest {
        Files.writeString(tmp.resolve("stable.md"), "# Hello\ncontent", Charsets.UTF_8)
        val first = DocumentLoader(listOf(tmp.toString())).load()
        val second = DocumentLoader(listOf(tmp.toString())).load()
        assertEquals(first[0].id, second[0].id)
        assertTrue(first[0].id.startsWith("doc-"))
    }

    @Test
    fun `skips build directories`() = runTest {
        val buildDir = tmp.resolve("build")
        Files.createDirectories(buildDir)
        Files.writeString(buildDir.resolve("generated.md"), "# Generated\nx", Charsets.UTF_8)
        Files.writeString(tmp.resolve("real.md"), "# Real\ny", Charsets.UTF_8)
        val docs = DocumentLoader(listOf(tmp.toString())).load()
        assertEquals(1, docs.size)
        assertEquals("Real", docs[0].title)
    }

    @Test
    fun `ignores non-existent roots`() = runTest {
        Files.writeString(tmp.resolve("a.md"), "# A\nx", Charsets.UTF_8)
        val docs = DocumentLoader(listOf(tmp.toString(), "/nonexistent/path")).load()
        assertEquals(1, docs.size)
    }

    @Test
    fun `skips blank files`() = runTest {
        Files.writeString(tmp.resolve("empty.md"), "   ", Charsets.UTF_8)
        Files.writeString(tmp.resolve("real.md"), "# Real\nbody", Charsets.UTF_8)
        val docs = DocumentLoader(listOf(tmp.toString())).load()
        assertEquals(1, docs.size)
    }

    @Test
    fun `walks nested directories`() = runTest {
        val sub = Files.createDirectories(tmp.resolve("sub"))
        Files.writeString(tmp.resolve("root.md"), "# Root\nx", Charsets.UTF_8)
        Files.writeString(sub.resolve("nested.md"), "# Nested\ny", Charsets.UTF_8)
        val docs = DocumentLoader(listOf(tmp.toString())).load()
        assertEquals(2, docs.size)
    }

    @Test
    fun `single file root is loaded directly`() = runTest {
        val file = tmp.resolve("single.md")
        Files.writeString(file, "# Single\nbody", Charsets.UTF_8)
        val docs = DocumentLoader(listOf(file.toString())).load()
        assertEquals(1, docs.size)
    }
}
