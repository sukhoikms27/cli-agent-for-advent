package com.cliagent.cli

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.llm.LlmClient
import com.cliagent.rag.RagConfig
import com.cliagent.rag.RagRetriever
import com.cliagent.rag.embedding.OllamaEmbeddingClient
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 25 (TDD-починка): RED→GREEN тесты чистого ядра [RagCommands.runScenario].
 *
 * Эти тесты доказывают корректность прогона сценария ИЗОЛИРОВАННО от реальной LLM/Ollama/диска:
 * `chat` — mock-лямбда (контролирует ответы), `runScenario` — internal (виден из тестов модуля).
 * Без этого покрытия баг «ответы не печатаются, метрики все 0» прошёл незамеченным (старый
 * `RagScenarioTest` проверял только JSON-структуру, не прогон).
 *
 * Тестируемое: последовательность ходов, сохранение ответов, подсчёт метрик из реального ответа,
 * мягкая деградация при ошибке chat.
 */
class RagScenarioRunTest {

    /** Минимальный RagCommands: runScenario не использует constructor-параметры (только scenario+chat). */
    private fun ragCommands(): RagCommands = RagCommands(
        config = RagConfig(),
        sharedEmbedder = mockk<OllamaEmbeddingClient>(relaxed = true),
        agent = mockk<ContextAwareAgent>(relaxed = true),
        chat = { "" },   // не используется — runScenario принимает chat как параметр
        ragRetriever = mockk<RagRetriever>(relaxed = true),
        llmClient = null,
        model = "test",
    )

    private fun scenario(turns: List<EvalScenarioTurn>): EvalScenario =
        EvalScenario(id = "test", goal = "разобраться в RAG архитектуре", turns = turns)

    private fun turn(user: String, kw: List<String> = emptyList(), src: List<String> = emptyList()) =
        EvalScenarioTurn(user = user, expectedKeywords = kw, expectedSources = src)

    @Test
    fun `runScenario calls chat once per turn with the turn user message in order`() = runTest {
        val cmds = ragCommands()
        val chatCalls = mutableListOf<String>()
        val chat: suspend (String) -> String = { msg -> chatCalls.add(msg); "ok" }
        val sc = scenario(listOf(turn("q1"), turn("q2"), turn("q3")))

        cmds.runScenario(sc, chat)

        // Строгая последовательность, ровно по разу на turn — доказывает отсутствие гонки/пропуска.
        assertEquals(listOf("q1", "q2", "q3"), chatCalls)
    }

    @Test
    fun `runScenario captures each turn answer into ScenarioTurnRow`() = runTest {
        val cmds = ragCommands()
        // mock-chat возвращает уникальный ответ на каждую реплику.
        val chat: suspend (String) -> String = { msg -> "ОТВЕТ-НА-$msg" }
        val sc = scenario(listOf(turn("q1"), turn("q2")))

        val rows = cmds.runScenario(sc, chat)

        assertEquals(2, rows.size)
        assertEquals("ОТВЕТ-НА-q1", rows[0].answer, "ответ первого хода не теряется")
        assertEquals("ОТВЕТ-НА-q2", rows[1].answer, "ответ второго хода не теряется")
        assertEquals("q1", rows[0].user)
        assertEquals("q2", rows[1].user)
    }

    @Test
    fun `runScenario computes keyword hits from real answer not empty string`() = runTest {
        val cmds = ragCommands()
        val sc = scenario(listOf(
            turn("как работает retrieval?", kw = listOf("retriev", "topK", "chunk", "embed")),
        ))
        // Ответ содержит 3 из 4 keywords (retriev, topK, embed через "embedding") → keywordHits=3.
        val chat: suspend (String) -> String = {
            "retriev работает через topK поиск по embedding векторам"
        }

        val rows = cmds.runScenario(sc, chat)

        assertEquals(3, rows[0].keywordHits, "keywordHits считаются из ответа, не из пустой строки")
        assertEquals(4, rows[0].keywordTotal)
    }

    @Test
    fun `runScenario detects sources and citations from real answer`() = runTest {
        val cmds = ragCommands()
        val sc = scenario(listOf(
            turn(
                "какие стратегии чанкинга?",
                kw = listOf("chunking"),
                src = listOf("04-chunking.md"),
            ),
        ))
        // Ответ упоминает источник (basename) + содержит цитату в кавычках ≥15 символов.
        val chat: suspend (String) -> String = {
            "Согласно 04-chunking.md, стратегии: fixed и structural. «дословная цитата длиннее пятнадцати символов»"
        }

        val rows = cmds.runScenario(sc, chat)

        assertTrue(rows[0].sourcesPresent, "источник 04-chunking.md должен детектироваться в ответе")
        assertTrue(rows[0].citationsPresent, "цитата в кавычках ≥15 символов должна детектироваться")
    }

    @Test
    fun `runScenario handles chat error gracefully without crash`() = runTest {
        val cmds = ragCommands()
        val sc = scenario(listOf(turn("q1"), turn("q2"), turn("q3")))
        // chat бросает на второй реплике — прогон не должен падать, ответ = "(error: ...)".
        val chat: suspend (String) -> String = { msg ->
            if (msg == "q2") throw RuntimeException("simulated LLM failure")
            "ok-$msg"
        }

        val rows = cmds.runScenario(sc, chat)

        assertEquals(3, rows.size, "прогон завершается для всех ходов даже при ошибке")
        assertEquals("ok-q1", rows[0].answer)
        assertNotEquals("ok-q2", rows[1].answer, "ошибка не маскируется под обычный ответ")
        assertTrue(rows[1].answer.contains("error"), "ответ-ошибка помечен: ${rows[1].answer}")
        assertEquals("ok-q3", rows[2].answer)
    }

    @Test
    fun `runScenario returns one row per turn preserving count and order`() = runTest {
        val cmds = ragCommands()
        val sc = scenario((1..5).map { turn("turn-$it") })
        val chat: suspend (String) -> String = { "resp" }

        val rows = cmds.runScenario(sc, chat)

        assertEquals(5, rows.size)
        // Порядок сохранён: user-сообщения в порядке turns.
        (1..5).forEach { i -> assertEquals("turn-$i", rows[i - 1].user) }
    }
}
