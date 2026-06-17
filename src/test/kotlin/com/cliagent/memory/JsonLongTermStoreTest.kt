package com.cliagent.memory

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class JsonLongTermStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun file() = dir.resolve("longterm/memory.json")

    @Test
    fun `load returns empty when file absent`() = runTest {
        val store = JsonLongTermStore(file())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `save and load round-trip`() = runTest {
        val store = JsonLongTermStore(file())
        val ltm = LongTermMemory(
            knowledge = mapOf("stack" to "Kotlin"),
            decisions = mapOf("arch" to "MVI")
        )
        store.save(ltm)
        val loaded = store.load()
        assertEquals("Kotlin", loaded.knowledge["stack"])
        assertEquals("MVI", loaded.decisions["arch"])
    }

    @Test
    fun `data survives new store instance — cross-session`() = runTest {
        JsonLongTermStore(file()).save(LongTermMemory(knowledge = mapOf("stack" to "Kotlin")))

        // Новый процесс/инстанс читает тот же файл
        val reloaded = JsonLongTermStore(file()).load()
        assertEquals("Kotlin", reloaded.knowledge["stack"])
    }

    @Test
    fun `clear empties long-term memory`() = runTest {
        val store = JsonLongTermStore(file())
        store.save(LongTermMemory(knowledge = mapOf("stack" to "Kotlin")))
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `forwarded via JsonChatStore matches direct store`() = runTest {
        val longTermFile = file()
        val chatStore = JsonChatStore(dir.resolve("chats"), JsonLongTermStore(longTermFile))
        chatStore.saveLongTermMemory(LongTermMemory(knowledge = mapOf("k" to "v")))

        val viaDirect = JsonLongTermStore(longTermFile).load()
        assertEquals("v", viaDirect.knowledge["k"])
        assertEquals("v", chatStore.loadLongTermMemory().knowledge["k"])
    }
}
