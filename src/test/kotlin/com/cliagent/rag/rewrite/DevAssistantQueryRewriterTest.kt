package com.cliagent.rag.rewrite

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 31 (fix multilingual RAG): тесты [DevAssistantQueryRewriter].
 *
 * Покрывает: перевод запроса через LLM, мягкую деградацию при ошибке/пустом ответе LLM,
 * identity на пустом вводе. LLM мочится через mockk (как ContextDuplicationTest).
 */
class DevAssistantQueryRewriterTest {

    private fun fakeLlm(responseContent: String): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Success(
            ChatResponse(
                id = "resp",
                choices = listOf(
                    Choice(index = 0, message = ChatMessage(role = "assistant", content = responseContent))
                ),
                usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2),
            )
        )
    }

    private fun failingLlm(): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Error(code = 500, message = "LLM down")
    }

    @Test
    fun `name is dev-assistant-llm`() {
        val r = DevAssistantQueryRewriter(fakeLlm("x"), "glm-5.1")
        assertEquals("dev-assistant-llm", r.name)
    }

    @Test
    fun `rewrite returns LLM-translated query`() = runTest {
        // LLM «перевела» русский запрос в английский (симуляция).
        val r = DevAssistantQueryRewriter(fakeLlm("tech stack frameworks languages build tools"), "glm-5.1")
        val out = r.rewrite("Какой технический стек использует этот проект?")
        assertEquals("tech stack frameworks languages build tools", out)
    }

    @Test
    fun `rewrite preserves English query`() = runTest {
        val r = DevAssistantQueryRewriter(fakeLlm("tech stack frameworks"), "glm-5.1")
        val out = r.rewrite("What tech stack does this project use?")
        assertEquals("tech stack frameworks", out)
    }

    @Test
    fun `rewrite returns original query on LLM error (soft degradation)`() = runTest {
        val original = "технический стек проекта"
        val r = DevAssistantQueryRewriter(failingLlm(), "glm-5.1")
        val out = r.rewrite(original)
        assertEquals(original, out, "при ошибке LLM должен вернуть исходный запрос")
    }

    @Test
    fun `rewrite returns original query on empty LLM response`() = runTest {
        val original = "tech stack"
        val r = DevAssistantQueryRewriter(fakeLlm("   "), "glm-5.1")  // пустой ответ
        val out = r.rewrite(original)
        assertEquals(original, out, "пустой ответ LLM → исходный запрос")
    }

    @Test
    fun `rewrite returns blank input unchanged`() = runTest {
        val r = DevAssistantQueryRewriter(fakeLlm("x"), "glm-5.1")
        assertEquals("", r.rewrite(""))
        assertEquals("   ", r.rewrite("   "))
    }

    @Test
    fun `rewrite uses low temperature for stable output`() = runTest {
        // Проверяем, что в запросе температура = 0.2 (устойчивый перевод без креатива).
        val slot = mutableListOf<com.cliagent.llm.model.ChatRequest>()
        val llm = mockk<LlmClient> {
            coEvery { chat(capture(slot)) } returns LlmResult.Success(
                ChatResponse(
                    id = "r", choices = listOf(Choice(0, ChatMessage("assistant", "translated"))),
                    usage = Usage(1, 1, 2),
                )
            )
        }
        DevAssistantQueryRewriter(llm, "glm-5.1").rewrite("технический стек")
        assertEquals(0.2, slot.first().temperature, "temperature должна быть 0.2 для устойчивости")
    }

    @Test
    fun `rewrite sends system prompt instructing English translation`() = runTest {
        val slot = mutableListOf<com.cliagent.llm.model.ChatRequest>()
        val llm = mockk<LlmClient> {
            coEvery { chat(capture(slot)) } returns LlmResult.Success(
                ChatResponse(
                    id = "r", choices = listOf(Choice(0, ChatMessage("assistant", "en"))),
                    usage = Usage(1, 1, 2),
                )
            )
        }
        DevAssistantQueryRewriter(llm, "glm-5.1").rewrite("стек")
        val systemContent = slot.first().messages.first().content
        // System-prompt должен требовать перевод на английский.
        assertTrue(
            systemContent.contains("English", ignoreCase = true) || systemContent.contains("translate", ignoreCase = true),
            "system prompt должен инструктировать перевод на английский"
        )
    }
}
