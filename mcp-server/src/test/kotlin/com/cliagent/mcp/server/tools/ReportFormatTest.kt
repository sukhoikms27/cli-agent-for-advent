package com.cliagent.mcp.server.tools

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * День 19: чистая функция [buildReport] (process-этап пайплайна) — без IO, детерминированная.
 * Аналог aggregate() из Day 18: вынесена из handler'а для unit-тестирования без mock'ов.
 */
class ReportFormatTest {

    private val date = LocalDate.of(2026, 6, 27)

    @Test
    fun `report starts with title heading and date`() {
        val md = buildReport("Tech Digest", listOf("содержимое"), date)
        assertTrue(md.startsWith("# Tech Digest"))
        assertTrue(md.contains("_27.06.2026_"))
    }

    @Test
    fun `each section becomes a numbered heading with its content`() {
        val md = buildReport("Digest", listOf("блок А", "блок Б"), date)
        assertTrue(md.contains("## Раздел 1"))
        assertTrue(md.contains("блок А"))
        assertTrue(md.contains("## Раздел 2"))
        assertTrue(md.contains("блок Б"))
    }

    @Test
    fun `section content is trimmed`() {
        val md = buildReport("D", listOf("   с пробелами   "), date)
        assertTrue(md.contains("с пробелами"))
        assertTrue(!md.contains("с пробелами   "))
    }

    @Test
    fun `single section produces one Раздел heading`() {
        val md = buildReport("Solo", listOf("один"), date)
        assertTrue(md.contains("## Раздел 1"))
        assertTrue(!md.contains("## Раздел 2"))
    }

    @Test
    fun `empty sections throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildReport("X", emptyList(), date)
        }
    }

    @Test
    fun `output is trimmed at the end (no trailing newline)`() {
        val md = buildReport("X", listOf("a"), date)
        assertTrue(!md.endsWith("\n"))
    }
}
