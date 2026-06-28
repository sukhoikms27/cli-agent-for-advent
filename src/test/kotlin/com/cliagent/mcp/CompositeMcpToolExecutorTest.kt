package com.cliagent.mcp

import com.cliagent.llm.model.FunctionDef
import com.cliagent.llm.model.ToolDefinition
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 20: multi-server оркестрация [CompositeMcpToolExecutor]. Покрывает:
 * - routing: вызов tool'а маршрутизируется на правильный сервер.
 * - prefix-on-collision (Р2): коллизия имён → `server__tool`; уникальные — raw.
 * - graceful degradation: упавший сервер skip'ается, остальные работают.
 * - lifecycle: close() закрывает все executors; повторные definitions() кэшируются.
 *
 * Через [executorFactory] подсовываем mock [McpToolExecutor] на каждый транспорт — без реального
 * MCP-сервера (DI-seam, как `factory` в [McpToolExecutor]).
 */
class CompositeMcpToolExecutorTest {

    private fun toolDef(name: String) = ToolDefinition(function = FunctionDef(name = name, description = "d"))

    /** Фабрика mock-executor'ов: каждый mock отвечает за свой список tool-имён. */
    private fun mockExecutorFor(tools: List<String>, callResult: (String) -> String = { "result:$it" }): McpToolExecutor {
        return mockk {
            coEvery { definitions() } returns tools.map { toolDef(it) }
            coEvery { call(any(), any()) } answers {
                val name = firstArg<String>()
                callResult(name)
            }
            coEvery { close() } returns Unit
        }
    }

    @Test
    fun `routes call to correct server by tool name`() = runTest {
        val execA = mockExecutorFor(listOf("get_repo", "search_wikipedia"))
        val execB = mockExecutorFor(listOf("read_file", "write_file"))
        val composite = CompositeMcpToolExecutor(
            servers = listOf(
                McpServerConfig(name = "local", command = "java"),
                McpServerConfig(name = "filesystem", command = "npx"),
            ),
            executorFactory = { if (it == McpServerConfig(name = "local", command = "java").toTransport()) execA else execB },
        )

        val defs = composite.definitions()
        // 4 tool'а, raw имена (нет коллизий)
        assertEquals(setOf("get_repo", "search_wikipedia", "read_file", "write_file"), defs.map { it.function.name }.toSet())

        assertEquals("result:read_file", composite.call("read_file", emptyMap()))
        assertEquals("result:get_repo", composite.call("get_repo", emptyMap()))
    }

    @Test
    fun `prefix-on-collision - shared tool name renamed to server__tool`() = runTest {
        // Оба сервера имеют tool "read_file" → коллизия → переименование в server__read_file
        val execA = mockExecutorFor(listOf("read_file"))
        val execB = mockExecutorFor(listOf("read_file"))
        val composite = CompositeMcpToolExecutor(
            servers = listOf(
                McpServerConfig(name = "alpha", command = "a"),
                McpServerConfig(name = "beta", command = "b"),
            ),
            executorFactory = { transport ->
                when (transport) {
                    is McpTransportConfig.Stdio -> if (transport.command.first() == "a") execA else execB
                    else -> execA
                }
            },
        )

        val defs = composite.definitions()
        assertEquals(setOf("alpha__read_file", "beta__read_file"), defs.map { it.function.name }.toSet())

        // вызов по префиксованному имени маршрутизируется на нужный сервер с raw именем
        assertEquals("result:read_file", composite.call("alpha__read_file", emptyMap()))
        assertEquals("result:read_file", composite.call("beta__read_file", emptyMap()))
        coVerify { execA.call("read_file", any()) }
        coVerify { execB.call("read_file", any()) }
    }

    @Test
    fun `graceful degradation - unavailable server skipped, others work`() = runTest {
        val warnings = mutableListOf<String>()
        // execB бросает при definitions() — имитация недоступного сервера
        val failingExec = mockk<McpToolExecutor> {
            coEvery { definitions() } throws McpException("connection refused")
            coEvery { close() } returns Unit
        }
        val execA = mockExecutorFor(listOf("get_repo"))
        val composite = CompositeMcpToolExecutor(
            servers = listOf(
                McpServerConfig(name = "local", command = "java"),
                McpServerConfig(name = "dead", command = "x"),
            ),
            logger = { warnings += it },
            executorFactory = { transport ->
                when (transport) {
                    is McpTransportConfig.Stdio -> if (transport.command.first() == "x") failingExec else execA
                    else -> execA
                }
            },
        )

        val defs = composite.definitions()
        // только tool'ы живого сервера
        assertEquals(listOf("get_repo"), defs.map { it.function.name })

        // warning записан про упавший сервер
        assertTrue(warnings.any { it.contains("dead") && it.contains("unavailable") })

        // живой сервер работает
        assertEquals("result:get_repo", composite.call("get_repo", emptyMap()))
        // tool упавшего сервера не маршрутизируется
        val missing = composite.call("dead_tool", emptyMap())
        assertTrue(missing.contains("not found"))
    }

    @Test
    fun `disabled server is skipped from discovery`() = runTest {
        val execA = mockExecutorFor(listOf("get_repo"))
        val composite = CompositeMcpToolExecutor(
            servers = listOf(
                McpServerConfig(name = "local", command = "java"),
                McpServerConfig(name = "off", command = "npx", enabled = false),
            ),
            executorFactory = { execA },
        )

        val defs = composite.definitions()
        assertEquals(listOf("get_repo"), defs.map { it.function.name })
    }

    @Test
    fun `unknown tool name returns diagnostic, not exception`() = runTest {
        val execA = mockExecutorFor(listOf("get_repo"))
        val composite = CompositeMcpToolExecutor(
            servers = listOf(McpServerConfig(name = "local", command = "java")),
            executorFactory = { execA },
        )

        val result = composite.call("nonexistent", emptyMap())
        assertTrue(result.contains("not found"))
        assertTrue(result.contains("get_repo"))   // подсказка доступных
    }

    @Test
    fun `definitions are cached - executorFactory called once per server`() = runTest {
        var factoryCalls = 0
        val exec = mockExecutorFor(listOf("get_repo"))
        val composite = CompositeMcpToolExecutor(
            servers = listOf(McpServerConfig(name = "local", command = "java")),
            executorFactory = { factoryCalls++; exec },
        )

        composite.definitions()
        composite.definitions()
        composite.definitions()

        assertEquals(1, factoryCalls)   // lazy + кэш routing-таблицы
    }

    @Test
    fun `close closes all executors`() = runTest {
        val execA = mockExecutorFor(listOf("get_repo"))
        val execB = mockExecutorFor(listOf("read_file"))
        val composite = CompositeMcpToolExecutor(
            servers = listOf(
                McpServerConfig(name = "a", command = "java"),
                McpServerConfig(name = "b", command = "npx"),
            ),
            executorFactory = { transport ->
                when (transport) {
                    is McpTransportConfig.Stdio -> if (transport.command.first() == "java") execA else execB
                    else -> execA
                }
            },
        )

        composite.definitions()   // прогрев executors
        composite.close()

        coVerify(exactly = 1) { execA.close() }
        coVerify(exactly = 1) { execB.close() }
    }

    @Test
    fun `serverSummary lists all enabled servers`() = runTest {
        val execA = mockExecutorFor(listOf("get_repo"))
        val execB = mockExecutorFor(listOf("read_file", "write_file"))
        val composite = CompositeMcpToolExecutor(
            servers = listOf(
                McpServerConfig(name = "local", command = "java"),
                McpServerConfig(name = "filesystem", command = "npx"),
            ),
            executorFactory = { transport ->
                when (transport) {
                    is McpTransportConfig.Stdio -> if (transport.command.first() == "java") execA else execB
                    else -> execA
                }
            },
        )

        val summary = composite.serverSummary()
        assertEquals(2, summary.size)
        val local = summary.first { it.name == "local" }
        assertEquals(1, local.toolCount)
        assertTrue(local.connected)
        val fs = summary.first { it.name == "filesystem" }
        assertEquals(2, fs.toolCount)
        assertTrue(fs.connected)
    }

    @Test
    fun `empty servers yields empty definitions`() = runTest {
        val composite = CompositeMcpToolExecutor(servers = emptyList())
        assertTrue(composite.definitions().isEmpty())
    }

    @Test
    fun `toTransport prefers url over command`() {
        val remote = McpServerConfig(name = "vps", url = "https://mcp.example.com/mcp", token = "tok")
        assertTrue(remote.toTransport() is McpTransportConfig.Http)

        val local = McpServerConfig(name = "local", command = "java", args = listOf("-jar", "x.jar"))
        val stdio = local.toTransport() as McpTransportConfig.Stdio
        assertEquals(listOf("java", "-jar", "x.jar"), stdio.command)
    }
}
