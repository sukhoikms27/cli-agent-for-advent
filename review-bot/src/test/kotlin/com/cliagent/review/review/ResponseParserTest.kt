package com.cliagent.review.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResponseParserTest {

    private val fallback = listOf("item A", "item B")

    @Test
    fun `parses clean JSON`() {
        val raw = """
            {"checklist":[{"text":"item A","passed":true,"evidence":"ok"}],
             "criticalRemarks":["bug"],
             "recommendations":["hint"],
             "lineComments":[{"file":"Main.kt","line":5,"side":"RIGHT","body":"fix it"}],
             "verdict":"REJECT"}
        """.trimIndent()
        val r = ResponseParser.parse(raw, studentName = "Ivan", fallbackChecklist = fallback)
        assertEquals("Ivan", r.studentName)
        assertEquals(1, r.checklist.size)
        assertEquals("item A", r.checklist[0].text)
        assertTrue(r.checklist[0].passed)
        assertEquals(VerdictProposal.REJECT, r.verdict)
        assertEquals(1, r.lineComments.size)
        assertEquals("Main.kt", r.lineComments[0].file)
        assertEquals(5, r.lineComments[0].line)
        assertEquals(CommentSide.RIGHT, r.lineComments[0].side)
    }

    @Test
    fun `strips markdown json fence`() {
        val raw = """
            Here is my review:
            ```json
            {"checklist":[],"criticalRemarks":[],"recommendations":[],"lineComments":[],"verdict":"ACCEPT"}
            ```
        """.trimIndent()
        val r = ResponseParser.parse(raw, "Ivan", fallback)
        assertEquals(VerdictProposal.ACCEPT, r.verdict)
    }

    @Test
    fun `extracts JSON surrounded by prose`() {
        val raw = """
            Analyzing the diff...
            {"checklist":[{"text":"item A","passed":false}],"criticalRemarks":["nope"],
             "recommendations":[],"lineComments":[],"verdict":"REJECT"}
            Done.
        """.trimIndent()
        val r = ResponseParser.parse(raw, "Ivan", fallback)
        assertEquals(VerdictProposal.REJECT, r.verdict)
        assertEquals(1, r.criticalRemarks.size)
    }

    @Test
    fun `handles braces inside strings`() {
        val raw = """{"checklist":[],"criticalRemarks":["brace { inside string } ok"],
                     "recommendations":[],"lineComments":[],"verdict":"REJECT"}"""
        val r = ResponseParser.parse(raw, "Ivan", fallback)
        assertEquals(1, r.criticalRemarks.size)
        assertTrue(r.criticalRemarks[0].contains("brace"))
    }

    @Test
    fun `fallback on invalid JSON sets REJECT and includes raw snippet`() {
        val raw = "this is not JSON at all"
        val r = ResponseParser.parse(raw, "Ivan", fallback)
        assertEquals(VerdictProposal.REJECT, r.verdict)
        assertEquals(2, r.checklist.size)  // from fallback
        assertFalse(r.checklist[0].passed)
        assertTrue(r.criticalRemarks.any { it.contains("Не удалось распарсить") })
    }

    @Test
    fun `missing checklist falls back to provided items`() {
        val raw = """{"criticalRemarks":[],"recommendations":[],"lineComments":[],"verdict":"REJECT"}"""
        val r = ResponseParser.parse(raw, "Ivan", fallback)
        assertEquals(2, r.checklist.size)
        assertEquals("item A", r.checklist[0].text)
        assertFalse(r.checklist[0].passed)
    }

    @Test
    fun `unknown verdict string defaults to REJECT`() {
        val raw = """{"checklist":[],"criticalRemarks":[],"recommendations":[],
                     "lineComments":[],"verdict":"MAYBE"}"""
        val r = ResponseParser.parse(raw, "Ivan", fallback)
        assertEquals(VerdictProposal.REJECT, r.verdict)
    }

    @Test
    fun `extractJson returns null when no brace`() {
        assertNull(ResponseParser.extractJson("no braces here"))
    }
}
