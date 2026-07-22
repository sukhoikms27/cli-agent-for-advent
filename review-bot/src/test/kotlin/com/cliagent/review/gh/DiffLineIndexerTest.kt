package com.cliagent.review.gh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffLineIndexerTest {

    @Test
    fun `indexes simple added lines`() {
        val patch = """
            @@ -1,3 +1,5 @@
             context line
            -old line
            +new line one
            +new line two
             another context
        """.trimIndent()
        val lines = DiffLineIndexer.indexFile("src/Main.kt", patch)
        assertEquals(2, lines.size)
        assertEquals("new line one", lines[0].content)
        assertEquals(2, lines[0].line)  // starts at +1,5 → first added is line 2
        assertEquals("new line two", lines[1].content)
        assertEquals(3, lines[1].line)
    }

    @Test
    fun `returns empty for null patch`() {
        assertTrue(DiffLineIndexer.indexFile("src/Main.kt", null).isEmpty())
        assertTrue(DiffLineIndexer.indexFile("src/Main.kt", "").isEmpty())
    }

    @Test
    fun `handles new file patch`() {
        // New file: @@ -0,0 +1,N @@
        val patch = """
            @@ -0,0 +1,3 @@
            +first line
            +second line
            +third line
        """.trimIndent()
        val lines = DiffLineIndexer.indexFile("src/New.kt", patch)
        assertEquals(3, lines.size)
        assertEquals(1, lines[0].line)
        assertEquals(2, lines[1].line)
        assertEquals(3, lines[2].line)
    }

    @Test
    fun `all lines carry correct path`() {
        val patch = """
            @@ -1,1 +1,2 @@
             ctx
            +added
        """.trimIndent()
        val lines = DiffLineIndexer.indexFile("path/to/Foo.kt", patch)
        assertEquals(1, lines.size)
        assertEquals("path/to/Foo.kt", lines[0].path)
    }

    @Test
    fun `findLine matches by content substring case-insensitive`() {
        val lines = listOf(
            DiffLine("Main.kt", 5, "val scanner = Scanner(System.`in`)"),
            DiffLine("Main.kt", 10, "val input = scanner.nextLine()"),
        )
        val found = DiffLineIndexer.findLine(lines, "Main.kt", "Scanner")
        assertEquals(5, found?.line)

        val found2 = DiffLineIndexer.findLine(lines, "Main.kt", "NEXTLINE")
        assertEquals(10, found2?.line)
    }

    @Test
    fun `findLine returns first line of file for empty needle`() {
        val lines = listOf(
            DiffLine("Main.kt", 5, "abc"),
            DiffLine("Main.kt", 10, "def"),
        )
        val found = DiffLineIndexer.findLine(lines, "Main.kt", "")
        assertEquals(5, found?.line)
    }

    @Test
    fun `findLine returns null when file not in lines`() {
        val lines = listOf(DiffLine("Main.kt", 5, "abc"))
        assertNull(DiffLineIndexer.findLine(lines, "Other.kt", "abc"))
    }

    @Test
    fun `indexFiles combines multiple files`() {
        val files = listOf(
            "A.kt" to "@@ -0,0 +1,1 @@\n+line in A",
            "B.kt" to "@@ -0,0 +1,2 @@\n+line in B\n+another in B",
        )
        val lines = DiffLineIndexer.indexFiles(files)
        assertEquals(3, lines.size)
        assertEquals("A.kt", lines[0].path)
        assertEquals("B.kt", lines[1].path)
        assertEquals("B.kt", lines[2].path)
    }
}
