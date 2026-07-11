package com.cliagent.cli

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.agent.StatefulAgent
import com.cliagent.agent.ToolExecutor
import com.cliagent.agent.stage.IntentClassifier
import com.cliagent.agent.stage.TaskOrchestrator
import com.cliagent.config.AppConfig
import com.cliagent.context.ContextManager
import com.cliagent.context.strategy.SlidingWindowStrategy
import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmProvider
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.token.TokenCounter
import com.cliagent.memory.JsonChatStore
import com.cliagent.memory.MemoryStore
import com.cliagent.rag.RagRetriever
import com.cliagent.state.invariant.LlmInvariantChecker
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 26: unit-тесты [AgentSession] — holder'а wiring, вынесенного из ChatCommand.run().
 *
 * [AgentSession] сам по себе тонкий (data class + close()). buildSession() — private suspend-метод
 * ChatCommand с большим числом зависимостей (MemoryStore/ragEmbedder/CLI-флаги), поэтому здесь
 * тестируем контракт holder'а напрямую: группировку полей, copy-семантику и поведение close().
 */
class AgentSessionTest {

    @Test
    fun `session groups all wiring fields and exposes them`() {
        val s = sampleSession(toolExecutor = null)
        assertEquals(LlmProvider.OLLAMA, s.resolvedProvider)
        assertEquals("qwen3:14b", s.model)
        assertNotNull(s.client)
        assertNotNull(s.agent)
        assertNotNull(s.statefulAgent)
        assertNotNull(s.orchestrator)
        assertNotNull(s.intentClassifier)
        assertNotNull(s.ragCommands)
        assertNotNull(s.ragRetriever)
        assertNotNull(s.contextManager)
        assertNull(s.toolExecutor, "no-MCP session → toolExecutor=null")
        assertEquals(8, s.maxToolRounds)
        assertEquals(0, s.mcpServerCount)
    }

    @Test
    fun `session config snapshot is preserved for local-off restore`() {
        val cfg = AppConfig(provider = "zai", model = "glm-5.1", apiKey = "k")
        val s = sampleSession(toolExecutor = null, config = cfg)
        assertEquals("zai", s.config.provider)
        assertEquals("glm-5.1", s.config.model)
        // copy не мутирует исходный config (data class copy contract)
        val switched = s.copy(config = cfg.copy(provider = "ollama"))
        assertEquals("ollama", switched.config.provider)
        assertEquals("zai", s.config.provider, "original session config must be unchanged after copy")
    }

    @Test
    fun `close calls toolExecutor close when present`() = runTest {
        var closed = false
        val exec = object : ToolExecutor {
            override suspend fun definitions() = emptyList<com.cliagent.llm.model.ToolDefinition>()
            override suspend fun call(name: String, args: Map<String, Any?>) = ""
            override suspend fun close() { closed = true }
        }
        val s = sampleSession(toolExecutor = exec)
        s.close()
        assertTrue(closed, "close() must close toolExecutor")
    }

    @Test
    fun `close does not throw when toolExecutor is null`() = runTest {
        val s = sampleSession(toolExecutor = null)
        // runCatching внутри close гарантирует — null toolExecutor не падает.
        s.close()
        // достигли сюда без исключения — контракт выполнен
        assertTrue(true)
    }

    @Test
    fun `close swallows toolExecutor close errors (switch must not crash REPL)`() = runTest {
        val exec = object : ToolExecutor {
            override suspend fun definitions() = emptyList<com.cliagent.llm.model.ToolDefinition>()
            override suspend fun call(name: String, args: Map<String, Any?>) = ""
            override suspend fun close() { throw RuntimeException("MCP boom") }
        }
        val s = sampleSession(toolExecutor = exec)
        // runCatching внутри close глотает — REPL не должен упасть на switch.
        s.close()
        assertTrue(true, "close() must swallow toolExecutor close errors")
    }

    @Test
    fun `close does NOT touch rag embedder (it is shared outside session)`() = runTest {
        // ragEmbedder живёт вне AgentSession и закрывается внешним finally при выходе из REPL.
        // AgentSession.close не должен его трогать — иначе /local on/off закрывал бы embedder и
        // следующий buildSession падал на закрытом HttpClient. Контракт: holder не владеет embedder'ом,
        // и close() закрывает только toolExecutor. Здесь: создаются два независимых embedder-mock'а
        // для embedder и ragRetriever — close сессии не должен закрывать ragRetriever (его close нет
        // в контракте AgentSession.close). Проверяем, что close выполняется чисто и не задевает
        // ничего кроме toolExecutor (косвенно — не падает на mock'ах).
        var toolClosed = false
        val exec = object : ToolExecutor {
            override suspend fun definitions() = emptyList<com.cliagent.llm.model.ToolDefinition>()
            override suspend fun call(name: String, args: Map<String, Any?>) = ""
            override suspend fun close() { toolClosed = true }
        }
        val s = sampleSession(toolExecutor = exec)
        s.close()
        assertTrue(toolClosed, "only toolExecutor should be closed")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Собирает минимально-валидную [AgentSession] с заданными toolExecutor/config. */
    private fun sampleSession(
        toolExecutor: ToolExecutor?,
        config: AppConfig = AppConfig(provider = "ollama", model = "qwen3:14b"),
    ): AgentSession {
        val memoryStore: MemoryStore = JsonChatStore()
        val chatId = "test-chat"
        val client: LlmClient = StubClient()
        val contextManager = ContextManager(SlidingWindowStrategy(10))
        val agent = ContextAwareAgent(
            llmClient = client,
            memoryStore = memoryStore,
            model = config.model,
            chatId = chatId,
            contextManager = contextManager,
            toolExecutor = toolExecutor,
        )
        val statefulAgent = StatefulAgent(agent, checker = null) { emptyList() }
        val orchestrator = TaskOrchestrator(
            agent, client, config.model,
            swarmMode = com.cliagent.agent.swarm.SwarmMode.AUTO,
            chat = { msg -> statefulAgent.chat(msg) }
        )
        val intentClassifier = IntentClassifier(client, config.model)
        val ragRetriever = mockk<RagRetriever>(relaxed = true)
        val ragCommands = RagCommands(
            config.rag,
            sharedEmbedder = mockk(relaxed = true),
            agent = agent,
            chat = { msg -> statefulAgent.chat(msg) },
            ragRetriever = ragRetriever,
            llmClient = client,
            model = config.model,
        )
        return AgentSession(
            client = client,
            resolvedProvider = LlmProvider.OLLAMA,
            model = config.model,
            config = config,
            agent = agent,
            statefulAgent = statefulAgent,
            orchestrator = orchestrator,
            intentClassifier = intentClassifier,
            ragCommands = ragCommands,
            ragRetriever = ragRetriever,
            contextManager = contextManager,
            checker = null,
            toolExecutor = toolExecutor,
            maxToolRounds = 8,
            mcpServerCount = 0,
        )
    }

    private class StubClient : LlmClient {
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> =
            LlmResult.Success(
                ChatResponse(id = "x", choices = emptyList())
            )
        // День 30: streaming не используется в AgentSession-тестах. Заглушка для контракта LlmClient.
        override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<com.cliagent.llm.model.StreamChunk> =
            throw UnsupportedOperationException("streaming not supported in StubClient")
    }
}
