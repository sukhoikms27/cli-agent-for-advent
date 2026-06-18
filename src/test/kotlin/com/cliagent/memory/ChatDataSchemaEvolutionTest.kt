package com.cliagent.memory

import com.cliagent.state.TaskStage
import com.cliagent.state.TaskState
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

    @Test
    fun `pre-Day-13 working memory JSON without taskState loads with null`() {
        // WorkingMemory, записанная до дня 13 (нет поля taskState)
        val legacyJson = """
            {
              "currentTask": "old task",
              "plan": null,
              "scratchNotes": null,
              "taskDecisions": []
            }
        """.trimIndent()

        val wm = json.decodeFromString<WorkingMemory>(legacyJson)
        assertEquals("old task", wm.currentTask)
        assertNull(wm.taskState)   // ключевое: новое поле отсутствует → null
    }

    @Test
    fun `working memory with taskState round-trips through the same schema`() {
        val wm = WorkingMemory(
            currentTask = "impl FSM",
            taskState = TaskState(stage = TaskStage.EXECUTION, currentStep = "wire accessors")
        )
        val encoded = json.encodeToString(WorkingMemory.serializer(), wm)
        val decoded = json.decodeFromString<WorkingMemory>(encoded)
        assertEquals("impl FSM", decoded.currentTask)
        assertEquals(TaskStage.EXECUTION, decoded.taskState?.stage)
        assertEquals("wire accessors", decoded.taskState?.currentStep)
    }

    @Test
    fun `pre-dorabotka TaskState JSON without implementation and verdict loads with null`() {
        // TaskState, записанная до доработки Day 13 (нет полей implementation/verdict)
        val legacyJson = """
            {
              "stage": "EXECUTION",
              "currentStep": "wire",
              "expectedAction": null,
              "approvedPlan": "1) ...",
              "stageHistory": []
            }
        """.trimIndent()

        val ts = json.decodeFromString<TaskState>(legacyJson)
        assertEquals(TaskStage.EXECUTION, ts.stage)
        assertEquals("1) ...", ts.approvedPlan)
        assertNull(ts.implementation)   // новое поле отсутствует → null
        assertNull(ts.verdict)          // новое поле отсутствует → null
    }

    @Test
    fun `TaskState with implementation and verdict round-trips`() {
        val ts = TaskState(
            stage = TaskStage.VALIDATION,
            approvedPlan = "1) View 2) Reducer",
            implementation = "Reducer + ViewModel готовы",
            verdict = "тесты зелёные"
        )
        val encoded = json.encodeToString(TaskState.serializer(), ts)
        val decoded = json.decodeFromString<TaskState>(encoded)
        assertEquals("Reducer + ViewModel готовы", decoded.implementation)
        assertEquals("тесты зелёные", decoded.verdict)
        assertEquals(TaskStage.VALIDATION, decoded.stage)
    }
}
