package com.cliagent.memory

import com.cliagent.state.TaskStage
import com.cliagent.state.TaskState
import com.cliagent.state.invariant.Invariant
import com.cliagent.state.invariant.InvariantCategory
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

    @Test
    fun `pre-auto-flow TaskState JSON without awaitingAdvance and requirements loads with defaults`() {
        // TaskState, записанный до авто-потока (нет полей awaitingAdvance/requirements)
        val legacyJson = """
            {
              "stage": "PLANNING",
              "currentStep": "wire",
              "approvedPlan": "1) ...",
              "implementation": null,
              "verdict": null,
              "stageHistory": []
            }
        """.trimIndent()

        val ts = json.decodeFromString<TaskState>(legacyJson)
        assertEquals(TaskStage.PLANNING, ts.stage)
        assertEquals(false, ts.awaitingAdvance)   // новое поле отсутствует → default
        assertNull(ts.requirements)               // новое поле отсутствует → null
    }

    @Test
    fun `TaskState with awaitingAdvance and requirements round-trips`() {
        val ts = TaskState(
            stage = TaskStage.CLARIFY,
            requirements = "stack=Kotlin, ops=+-*",
            awaitingAdvance = true
        )
        val encoded = json.encodeToString(TaskState.serializer(), ts)
        val decoded = json.decodeFromString<TaskState>(encoded)
        assertEquals("stack=Kotlin, ops=+-*", decoded.requirements)
        assertEquals(true, decoded.awaitingAdvance)
    }

    @Test
    fun `pre-Day-14 LongTermMemory JSON without invariants loads with empty list`() {
        // LongTermMemory, записанный до дня 14 (нет поля invariants)
        val legacyJson = """
            {
              "knowledge": {"stack": "Kotlin"},
              "decisions": {"arch": "MVI"},
              "profile": null
            }
        """.trimIndent()
        val ltm = json.decodeFromString<LongTermMemory>(legacyJson)
        assertEquals("Kotlin", ltm.knowledge["stack"])
        assertEquals(emptyList<Invariant>(), ltm.invariants)   // новое поле отсутствует → default
    }

    @Test
    fun `LongTermMemory with invariants round-trips`() {
        val ltm = LongTermMemory(
            invariants = listOf(
                Invariant("no-compose", "UI только View-based", InvariantCategory.BAN)
            )
        )
        val encoded = json.encodeToString(LongTermMemory.serializer(), ltm)
        val decoded = json.decodeFromString<LongTermMemory>(encoded)
        assertEquals(1, decoded.invariants.size)
        assertEquals("no-compose", decoded.invariants.first().id)
        assertEquals(InvariantCategory.BAN, decoded.invariants.first().category)
    }

    @Test
    fun `LongTermMemory with invariant is not empty even without profile`() {
        val ltm = LongTermMemory(invariants = listOf(Invariant("x", "rule")))
        assertEquals(false, ltm.isEmpty())
    }
}
