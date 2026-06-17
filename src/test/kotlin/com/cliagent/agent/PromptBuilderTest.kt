package com.cliagent.agent

import com.cliagent.llm.model.ChatMessage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderTest {

    private val base = ChatMessage(role = "system", content = "You are a helpful assistant.")

    @Test
    fun `empty layers produce base content unchanged`() {
        val built = PromptBuilder(base, null, null).build()
        assertEquals(base.content, built.content)
    }

    @Test
    fun `empty LongTermMemory and WorkingMemory also produce base content`() {
        val built = PromptBuilder(base, LongTermMemory(), WorkingMemory()).build()
        assertEquals(base.content, built.content)
    }

    @Test
    fun `only working layer renders working block without long-term`() {
        val working = WorkingMemory(currentTask = "auth service", plan = "1) routes")
        val built = PromptBuilder(base, null, working).build()
        assertTrue(built.content.contains("[Working memory — current task]"))
        assertTrue(built.content.contains("auth service"))
        assertFalse(built.content.contains("[Long-term memory]"))
    }

    @Test
    fun `only long-term layer renders long-term block without working`() {
        val lt = LongTermMemory(knowledge = mapOf("stack" to "Kotlin"))
        val built = PromptBuilder(base, lt, null).build()
        assertTrue(built.content.contains("[Long-term memory]"))
        assertTrue(built.content.contains("Kotlin"))
        assertFalse(built.content.contains("[Working memory"))
    }

    @Test
    fun `both layers render in order base then long-term then working`() {
        val lt = LongTermMemory(knowledge = mapOf("stack" to "Kotlin"))
        val working = WorkingMemory(currentTask = "auth service")
        val built = PromptBuilder(base, lt, working).build()
        val ltIdx = built.content.indexOf("[Long-term memory]")
        val workIdx = built.content.indexOf("[Working memory")
        assertTrue(ltIdx > 0)
        assertTrue(workIdx > ltIdx)
    }

    @Test
    fun `user profile renders inside long-term block — day 12 readiness`() {
        val lt = LongTermMemory(profile = UserProfile(style = "concise", constraints = listOf("no RxJava")))
        val built = PromptBuilder(base, lt, null).build()
        assertTrue(built.content.contains("User profile:"))
        assertTrue(built.content.contains("concise"))
        assertTrue(built.content.contains("no RxJava"))
    }
}
