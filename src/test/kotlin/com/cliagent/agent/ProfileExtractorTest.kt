package com.cliagent.agent

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.memory.UserProfile
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileExtractorTest {

    private val profileText = """
        style: concise
        format: with code examples
        about: backend dev, Ktor
        constraints:
        - no RxJava
        - Kotlin only
    """.trimIndent()

    private fun llmReturning(content: String): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Success(
            ChatResponse(
                id = "r",
                choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = content)))
            )
        )
    }

    private val sampleHistory = listOf(
        ChatMessage(role = "user", content = "I use Kotlin and Ktor"),
        ChatMessage(role = "assistant", content = "Sure, I'll keep it short")
    )

    @Test
    fun `extract parses style format about and constraints`() = runTest {
        val extractor = ProfileExtractor(llmReturning(profileText), "m")
        val profile = extractor.extract(sampleHistory, null)
        assertEquals("concise", profile.style)
        assertEquals("with code examples", profile.format)
        assertEquals("backend dev, Ktor", profile.about)
        assertEquals(listOf("no RxJava", "Kotlin only"), profile.constraints)
    }

    @Test
    fun `extract on LLM error returns current unchanged`() = runTest {
        val llm = mockk<LlmClient> { coEvery { chat(any()) } returns LlmResult.Error(500, "boom") }
        val current = UserProfile(style = "existing")
        val result = ProfileExtractor(llm, "m").extract(sampleHistory, current)
        assertEquals(current, result)
    }

    @Test
    fun `extract with empty history returns current`() = runTest {
        val extractor = ProfileExtractor(llmReturning(profileText), "m")
        val current = UserProfile(style = "existing")
        assertEquals(current, extractor.extract(emptyList(), current))
    }

    @Test
    fun `mergeProfile preserves existing fields when inferred is empty`() {
        val merged = ProfileExtractor(mockk(), "m")
            .mergeProfile(UserProfile(style = "a", about = "b"), UserProfile())
        assertEquals("a", merged.style)
        assertEquals("b", merged.about)
        assertTrue(merged.constraints.isEmpty())
    }

    @Test
    fun `mergeProfile fills missing fields and accumulates distinct constraints`() {
        val merged = ProfileExtractor(mockk(), "m").mergeProfile(
            UserProfile(style = "a", constraints = listOf("c1")),
            UserProfile(about = "b", constraints = listOf("c1", "c2"))
        )
        assertEquals("a", merged.style)
        assertEquals("b", merged.about)
        assertEquals(listOf("c1", "c2"), merged.constraints)
    }

    @Test
    fun `mergeProfile with null current uses inferred`() {
        val merged = ProfileExtractor(mockk(), "m").mergeProfile(
            null, UserProfile(style = "x")
        )
        assertEquals("x", merged.style)
        assertNull(merged.about)
    }
}
