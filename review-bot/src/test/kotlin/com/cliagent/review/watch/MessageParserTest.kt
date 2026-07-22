package com.cliagent.review.watch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageParserTest {

    @Test
    fun `parses complete event from userscript`() {
        val raw = """
            {"source":"dom","messageId":"1784693458493054",
             "trackerUrl":"https://st.yandex-team.ru/PCR-1989840",
             "sprint":5,"studentName":"Искандар Хамитов",
             "rawText":"Новое задание: [5] Искандар Хамитов (hamitoffiskandar@yandex.ru)",
             "ts":1784693458493}
        """.trimIndent()
        val event = MessageParser.parse(raw)
        assertNotNull(event)
        assertEquals("dom", event!!.source)
        assertEquals("1784693458493054", event.messageId)
        assertEquals("https://st.yandex-team.ru/PCR-1989840", event.trackerUrl)
        assertEquals("PCR-1989840", event.trackerId)  // дозаполнено парсером из trackerUrl
        assertEquals(5, event.sprint)
        assertEquals("Искандар Хамитов", event.studentName)
    }

    @Test
    fun `fills trackerId from trackerUrl when not provided`() {
        val raw = """{"source":"dom","trackerUrl":"https://st.yandex-team.ru/PCR-1","sprint":3}"""
        val event = MessageParser.parse(raw)!!
        assertEquals("PCR-1", event.trackerId)
    }

    @Test
    fun `returns null for invalid JSON`() {
        assertNull(MessageParser.parse("not a json at all"))
        assertNull(MessageParser.parse(""))
        assertNull(MessageParser.parse("{"))
    }

    @Test
    fun `returns null for JSON with wrong structure`() {
        assertNull(MessageParser.parse("[]"))
        assertNull(MessageParser.parse("\"string\""))
        assertNull(MessageParser.parse("42"))
    }

    @Test
    fun `validity VALID when trackerUrl present`() {
        val event = WorkEvent(trackerUrl = "https://st.yandex-team.ru/PCR-1", sprint = 5)
        assertEquals(WorkEventValidity.VALID, MessageParser.validity(event))
    }

    @Test
    fun `validity NO_TRACKER_URL when rawText present but no trackerUrl`() {
        val event = WorkEvent(rawText = "Новое задание: [5] ...")
        assertEquals(WorkEventValidity.NO_TRACKER_URL, MessageParser.validity(event))
    }

    @Test
    fun `validity NO_USEFUL_DATA when empty event`() {
        val event = WorkEvent()
        assertEquals(WorkEventValidity.NO_USEFUL_DATA, MessageParser.validity(event))
    }

    @Test
    fun `tolerates extra fields from userscript`() {
        val raw = """{"source":"dom","trackerUrl":"https://st.yandex-team.ru/X-1",
                     "extraField":"ignored","sprint":2,"studentName":"Test"}"""
        val event = MessageParser.parse(raw)
        assertNotNull(event)
        assertEquals("X-1", event!!.trackerId)
    }

    @Test
    fun `handles different tracker prefixes`() {
        val cases = listOf(
            "https://st.yandex-team.ru/PCR-1989840" to "PCR-1989840",
            "https://st.yandex-team.ru/ANDROID-42" to "ANDROID-42",
            "https://st.yandex-team.ru/MOBILE-7" to "MOBILE-7",
        )
        cases.forEach { (url, expectedId) ->
            val event = MessageParser.parse("""{"trackerUrl":"$url"}""")
            assertEquals(expectedId, event?.trackerId, "for $url")
        }
    }
}
