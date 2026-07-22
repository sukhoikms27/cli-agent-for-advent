package com.cliagent.review.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChecklistParserTest {

    @Test
    fun `parses numbered items with line wrap`() {
        val md = """
            # Чеклист

            1. **Первый пункт.**
               Продолжение первого пункта.
            2. **Второй пункт** без точки.
            3. Третий пункт однострочный.
        """.trimIndent()
        val items = ChecklistParser.parse(md)
        assertEquals(3, items.size)
        assertEquals("Первый пункт. Продолжение первого пункта.", items[0])
        assertEquals("Второй пункт без точки.", items[1])
        assertEquals("Третий пункт однострочный.", items[2])
    }

    @Test
    fun `cleans markdown bold and inline code`() {
        val md = """
            1. Это про `isBlank` метод и **важное** требование.
        """.trimIndent()
        val items = ChecklistParser.parse(md)
        assertEquals(1, items.size)
        assertTrue(items[0].contains("важное"))
        assertTrue(items[0].contains("isBlank"))
        assertTrue(!items[0].contains("**"))
        assertTrue(!items[0].contains("`"))
    }

    @Test
    fun `returns empty for no numbered items`() {
        val md = """
            # Just a header
            Some paragraph.
        """.trimIndent()
        assertTrue(ChecklistParser.parse(md).isEmpty())
    }

    @Test
    fun `collapses whitespace`() {
        val md = """
            1.   Пункт   с    лишними     пробелами.
        """.trimIndent()
        val items = ChecklistParser.parse(md)
        assertEquals("Пункт с лишними пробелами.", items[0])
    }

    @Test
    fun `parses real embedded checklist format`() {
        val md = """
            1. **Есть меню с возможностью добавления и просмотра архивов.**
               Пользователь может создать архив и увидеть список существующих архивов.
            2. **Есть меню с возможностью добавления и просмотра заметок.**
               Внутри архива можно создать заметку и увидеть список заметок этого архива.
        """.trimIndent()
        val items = ChecklistParser.parse(md)
        assertEquals(2, items.size)
        assertTrue(items[0].startsWith("Есть меню"))
        assertTrue(items[1].contains("создать заметку"))
    }
}
