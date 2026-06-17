package com.cliagent.memory

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class JsonChatStoreWorkingMemoryTest {

    @TempDir
    lateinit var chatsDir: Path

    private fun store() = JsonChatStore(chatsDir)

    @Test
    fun `save and load working memory round-trip`() = runTest {
        val store = store()
        val chat = store.createChat()
        val wm = WorkingMemory(
            currentTask = "auth service",
            plan = "1) routes 2) tokens",
            scratchNotes = "use JWT",
            taskDecisions = listOf("Ktor", "no RxJava")
        )
        store.saveWorkingMemory(chat.id, wm)

        val loaded = store.loadWorkingMemory(chat.id)
        assertNotNull(loaded)
        assertEquals(wm, loaded)
    }

    @Test
    fun `clear working memory sets it to null`() = runTest {
        val store = store()
        val chat = store.createChat()
        store.saveWorkingMemory(chat.id, WorkingMemory(currentTask = "task"))
        assertNotNull(store.loadWorkingMemory(chat.id))

        store.clearWorkingMemory(chat.id)
        assertNull(store.loadWorkingMemory(chat.id))
    }

    @Test
    fun `working memory is per-chat and isolated`() = runTest {
        val store = store()
        val a = store.createChat()
        val b = store.createChat()
        store.saveWorkingMemory(a.id, WorkingMemory(currentTask = "task A"))

        assertNull(store.loadWorkingMemory(b.id))
        assertEquals("task A", store.loadWorkingMemory(a.id)?.currentTask)
    }

    @Test
    fun `chat without working memory loads as null`() = runTest {
        val store = store()
        val chat = store.createChat()
        assertNull(store.loadWorkingMemory(chat.id))
    }
}
