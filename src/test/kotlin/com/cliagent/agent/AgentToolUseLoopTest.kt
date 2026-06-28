package com.cliagent.agent

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.FunctionDef
import com.cliagent.llm.model.ToolCall
import com.cliagent.llm.model.ToolCallFunction
import com.cliagent.llm.model.ToolDefinition
import com.cliagent.llm.model.Usage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * День 17: agent tool-use loop. LLM возвращает tool_calls → агент исполняет tool через
 * [ToolExecutor] → feed-back → финальный ответ. Промежуточные сообщения (assistant с tool_calls,
 * role="tool") НЕ persist'ятся в history — только user + финальный assistant.
 */
class AgentToolUseLoopTest {

    private fun toolCallResp(): ChatResponse = ChatResponse(
        id = "r1",
        choices = listOf(
            Choice(
                index = 0,
                message = ChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_1",
                            function = ToolCallFunction(
                                name = "get_repo",
                                arguments = """{"owner":"JetBrains","repo":"kotlin"}"""
                            )
                        )
                    )
                ),
                finishReason = "tool_calls"
            )
        ),
        usage = Usage(1, 1, 2)
    )

    private fun finalResp(content: String): ChatResponse = ChatResponse(
        id = "r2",
        choices = listOf(Choice(0, ChatMessage(role = "assistant", content = content), "stop")),
        usage = Usage(1, 1, 2)
    )

    private fun storeMock(): MemoryStore = mockk(relaxed = true) {
        coEvery { loadHistory(any()) } returns emptyList()
        coEvery { loadWorkingMemory(any()) } returns null
        coEvery { loadLongTermMemory() } returns LongTermMemory()
        coEvery { loadSummary(any()) } returns null
        coEvery { loadFacts(any()) } returns emptyMap()
    }

    private fun repoToolDef() = ToolDefinition(
        function = FunctionDef(name = "get_repo", description = "GitHub repo metadata")
    )

    @Test
    fun `agent executes tool_call then returns final answer`() = runTest {
        val llm = mockk<LlmClient> {
            coEvery { chat(any()) } returnsMany listOf(
                LlmResult.Success(toolCallResp()),
                LlmResult.Success(finalResp("Kotlin repo has ~46k stars")),
            )
        }
        val exec = mockk<ToolExecutor> {
            coEvery { definitions() } returns listOf(repoToolDef())
            coEvery { call("get_repo", any()) } returns "JetBrains/kotlin | ⭐46000 | Kotlin | master"
        }
        val store = storeMock()
        val agent = ContextAwareAgent(llm, store, "m", "chat-1", toolExecutor = exec)

        val answer = agent.chat("Что знает агент о репозитории JetBrains/kotlin?")

        assertEquals("Kotlin repo has ~46k stars", answer)
        coVerify(exactly = 1) { exec.call("get_repo", any()) }
        // persist только user + финальный assistant (НЕ промежуточный tool-call assistant / tool results)
        coVerify(exactly = 2) { store.saveMessage(any(), any()) }
    }

    @Test
    fun `loop terminates at maxToolRounds when model keeps requesting tools`() = runTest {
        // Модель бесконечно просит tool_calls — цикл обязан оборваться по guard. Явный maxToolRounds=4
        // (день 20: параметр конфигурируем, default 8) — тест детерминирован от значения конструктора.
        val llm = mockk<LlmClient> {
            coEvery { chat(any()) } returns LlmResult.Success(toolCallResp())
        }
        val exec = mockk<ToolExecutor> {
            coEvery { definitions() } returns listOf(repoToolDef())
            coEvery { call(any(), any()) } returns "result"
        }
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1", toolExecutor = exec, maxToolRounds = 4)

        val answer = agent.chat("q")   // не должно зависнуть

        assertEquals("", answer)   // финализация последнего tool_call-сообщения (content="")
        coVerify(exactly = 4) { exec.call(any(), any()) }
    }

    @Test
    fun `maxToolRounds is configurable - default 8`() = runTest {
        // День 20: дефолт maxToolRounds=8 (вместо прежнего const 4). Проверяем, что конфигурация
        // пробрасывается — при maxToolRounds=3 цикл рвётся ровно после 3 tool-вызовов.
        val llm = mockk<LlmClient> {
            coEvery { chat(any()) } returns LlmResult.Success(toolCallResp())
        }
        val exec = mockk<ToolExecutor> {
            coEvery { definitions() } returns listOf(repoToolDef())
            coEvery { call(any(), any()) } returns "result"
        }
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1", toolExecutor = exec, maxToolRounds = 3)

        agent.chat("q")

        coVerify(exactly = 3) { exec.call(any(), any()) }
    }

    @Test
    fun `null toolExecutor behaves as single-shot (no tools)`() = runTest {
        val llm = mockk<LlmClient> {
            coEvery { chat(any()) } returns LlmResult.Success(finalResp("просто ответ"))
        }
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")   // toolExecutor = null
        assertEquals("просто ответ", agent.chat("q"))
    }
}
