package com.cliagent.support

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 33 — тесты [TicketFieldExtractor].
 *
 * Покрывает: извлечение ticketId/email из free-form текста, soft-degradation при LLM-ошибке,
 * tolerant-парсинг (markdown-fence, garbage, partial fields), fast-path на blank input,
 * параметры ChatRequest (temperature=0.0, maxTokens=1024 — фикс thinking-моделей).
 *
 * LLM мочится через mockk (как [com.cliagent.rag.rewrite.DevAssistantQueryRewriterTest]).
 */
class TicketFieldExtractorTest {

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

    // ── Happy path: успешное извлечение полей ────────────────────────────────────

    @Test
    fun `extracts ticketId only`() = runTest {
        val e = TicketFieldExtractor(fakeLlm("""{"ticketId": 5, "customerEmail": null}"""), "glm-5.1")
        val r = e.extract("что с моим тикетом 5?")
        assertEquals(5, r.ticketId)
        assertNull(r.customerEmail)
    }

    @Test
    fun `extracts customerEmail only`() = runTest {
        val e = TicketFieldExtractor(fakeLlm("""{"ticketId": null, "customerEmail": "alice@example.com"}"""), "glm-5.1")
        val r = e.extract("почта alice@example.com, когда решат?")
        assertNull(r.ticketId)
        assertEquals("alice@example.com", r.customerEmail)
    }

    @Test
    fun `extracts both fields`() = runTest {
        val e = TicketFieldExtractor(fakeLlm("""{"ticketId": 42, "customerEmail": "bob@mail.ru"}"""), "glm-5.1")
        val r = e.extract("обращался по тикету 42, почта bob@mail.ru")
        assertEquals(42, r.ticketId)
        assertEquals("bob@mail.ru", r.customerEmail)
    }

    @Test
    fun `extracts nothing for plain question`() = runTest {
        val e = TicketFieldExtractor(fakeLlm("""{"ticketId": null, "customerEmail": null}"""), "glm-5.1")
        val r = e.extract("просто вопрос о продукте")
        assertTrue(r.isEmpty, "ничего не должно быть извлечено")
    }

    // ── Soft degradation ─────────────────────────────────────────────────────────

    @Test
    fun `returns empty on LLM error`() = runTest {
        val e = TicketFieldExtractor(failingLlm(), "glm-5.1")
        val r = e.extract("что с моим тикетом 5?")
        assertTrue(r.isEmpty, "LLM-ошибка → ExtractedFields.empty, без exception")
    }

    @Test
    fun `returns empty on garbage non-JSON LLM output`() = runTest {
        val e = TicketFieldExtractor(fakeLlm("Sorry, I cannot help with that."), "glm-5.1")
        val r = e.extract("тикет 5")
        assertTrue(r.isEmpty, "garbage-ответ LLM → empty")
    }

    @Test
    fun `returns empty on empty LLM response`() = runTest {
        val e = TicketFieldExtractor(fakeLlm("   "), "glm-5.1")
        val r = e.extract("тикет 5")
        assertTrue(r.isEmpty)
    }

    // ── Tolerant parsing ─────────────────────────────────────────────────────────

    @Test
    fun `parses markdown-wrapped JSON`() = runTest {
        val response = """
            ```json
            {"ticketId": 17, "customerEmail": null}
            ```
        """.trimIndent()
        val e = TicketFieldExtractor(fakeLlm(response), "glm-5.1")
        val r = e.extract("что с заявкой 17?")
        assertEquals(17, r.ticketId)
    }

    @Test
    fun `parses JSON embedded in explanatory text via regex fallback`() = runTest {
        // LLM добавила преамбулу, но JSON-объект — валидный.
        val response = "Извлекаю поля: {\"ticketId\": 8, \"customerEmail\": \"c@d.com\"} как просили."
        val e = TicketFieldExtractor(fakeLlm(response), "glm-5.1")
        val r = e.extract("тикет 8, c@d.com")
        assertEquals(8, r.ticketId)
        assertEquals("c@d.com", r.customerEmail)
    }

    @Test
    fun `handles string null values in JSON`() = runTest {
        // LLM иногда возвращает "null" строкой вместо JSON-null.
        val e = TicketFieldExtractor(fakeLlm("""{"ticketId": "null", "customerEmail": "null"}"""), "glm-5.1")
        val r = e.extract("тикет")
        assertTrue(r.isEmpty, "строка \"null\" → null для обоих полей")
    }

    // ── Fast path ────────────────────────────────────────────────────────────────

    @Test
    fun `blank input returns empty without LLM call`() = runTest {
        val llm = mockk<LlmClient>(relaxed = true)
        val e = TicketFieldExtractor(llm, "glm-5.1")
        assertTrue(e.extract("").isEmpty)
        assertTrue(e.extract("   ").isEmpty)
        coVerify(exactly = 0) { llm.chat(any()) }
    }

    // ── Параметры ChatRequest ────────────────────────────────────────────────────

    @Test
    fun `uses temperature 0 for deterministic output`() = runTest {
        val slot = mutableListOf<ChatRequest>()
        val llm = mockk<LlmClient> {
            coEvery { chat(capture(slot)) } returns LlmResult.Success(
                ChatResponse(
                    id = "r", choices = listOf(Choice(0, ChatMessage("assistant", """{"ticketId": null, "customerEmail": null}"""))),
                    usage = Usage(1, 1, 2),
                )
            )
        }
        TicketFieldExtractor(llm, "glm-5.1").extract("тикет 5")
        assertEquals(0.0, slot.first().temperature, "temperature должна быть 0.0 для детерминированности")
    }

    @Test
    fun `uses maxTokens 1024 to handle thinking-models budget`() = runTest {
        // Критичный фикс: без явного maxTokens thinking-модели (qwen3, GLM-5.1) тратят токены
        // на reasoning и возвращают пустой content. 1024 даёт reasoning'у закончиться.
        val slot = mutableListOf<ChatRequest>()
        val llm = mockk<LlmClient> {
            coEvery { chat(capture(slot)) } returns LlmResult.Success(
                ChatResponse(
                    id = "r", choices = listOf(Choice(0, ChatMessage("assistant", """{"ticketId": 5, "customerEmail": null}"""))),
                    usage = Usage(1, 1, 2),
                )
            )
        }
        TicketFieldExtractor(llm, "qwen3:14b").extract("тикет 5")
        assertEquals(1024, slot.first().maxTokens, "maxTokens=1024 — фикс thinking-моделей")
    }

    @Test
    fun `prompt contains JSON template and few-shot examples`() = runTest {
        val slot = mutableListOf<ChatRequest>()
        val llm = mockk<LlmClient> {
            coEvery { chat(capture(slot)) } returns LlmResult.Success(
                ChatResponse(
                    id = "r", choices = listOf(Choice(0, ChatMessage("assistant", """{"ticketId": null, "customerEmail": null}"""))),
                    usage = Usage(1, 1, 2),
                )
            )
        }
        TicketFieldExtractor(llm, "glm-5.1").extract("тикет 5")
        val prompt = slot.first().messages.first().content
        assertTrue(prompt.contains("ticketId"), "промпт должен описать поле ticketId")
        assertTrue(prompt.contains("customerEmail"), "промпт должен описать поле customerEmail")
        assertTrue(prompt.contains("JSON"), "промпт должен требовать JSON-формат")
        assertTrue(prompt.contains("тикет 5"), "промпт должен включать сообщение пользователя")
    }
}
