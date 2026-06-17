package com.cliagent.memory

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChatDataSchemaEvolutionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `pre-Day-11 chat JSON without workingMemory loads with null`() {
        // JSON чата, записанный до дня 11 (нет поля workingMemory)
        val legacyJson = """
            {
              "id": "abc-123",
              "title": "Old Chat",
              "messages": [],
              "summary": null,
              "facts": {},
              "branches": [],
              "createdAt": "2026-06-01T00:00:00Z",
              "updatedAt": "2026-06-01T00:00:00Z"
            }
        """.trimIndent()

        val chat = json.decodeFromString<ChatData>(legacyJson)
        assertEquals("abc-123", chat.id)
        assertEquals("Old Chat", chat.title)
        assertNull(chat.summary)
        assertNull(chat.workingMemory)   // ключевое: новое поле отсутствует → null
        assertEquals(0, chat.messages.size)
    }

    @Test
    fun `new chat with workingMemory round-trips through the same schema`() {
        val chat = ChatData(
            id = "x",
            title = "New",
            workingMemory = WorkingMemory(currentTask = "task"),
            createdAt = "2026-06-17T00:00:00Z",
            updatedAt = "2026-06-17T00:00:00Z"
        )
        val encoded = json.encodeToString(ChatData.serializer(), chat)
        val decoded = json.decodeFromString<ChatData>(encoded)
        assertEquals("task", decoded.workingMemory?.currentTask)
    }
}
