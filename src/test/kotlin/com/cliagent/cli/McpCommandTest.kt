package com.cliagent.cli

import com.cliagent.mcp.McpClient
import com.cliagent.mcp.McpException
import com.cliagent.mcp.McpTransportConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Юнит-тесты `/mcp` (день 16–18): конфиг-ветки и путь исключений покрываются без реального
 * MCP-сервера (последний — ручная верификация T8). mordant non-TTY в тестах → no-op для
 * спиннеров (паттерн MarkdownRenderTest).
 *
 * День 18: `handleMcp` принимает [McpTransportConfig] (Stdio | Http) вместо строковой команды.
 */
class McpCommandTest {

    private val cmd = ChatCommand()

    @Test
    fun `mcp with no args and no config prints status`() = runTest {
        assertDoesNotThrow {
            cmd.handleMcp("/mcp", transport = null)
        }
    }

    @Test
    fun `mcp with no args and configured stdio command prints configured status`() = runTest {
        assertDoesNotThrow {
            cmd.handleMcp(
                "/mcp",
                transport = McpTransportConfig.Stdio(
                    "npx -y @modelcontextprotocol/server-filesystem /tmp".split("\\s+".toRegex())
                ),
            )
        }
    }

    @Test
    fun `mcp with no args and configured remote url prints remote status`() = runTest {
        assertDoesNotThrow {
            cmd.handleMcp(
                "/mcp",
                transport = McpTransportConfig.Http("https://mcp.example.com/mcp", "tok"),
            )
        }
    }

    @Test
    fun `mcp list-tools without configured command warns and returns early`() = runTest {
        // early-return до создания McpClient — фабрика не должна зваться
        assertDoesNotThrow {
            cmd.handleMcp("/mcp list-tools", transport = null)
        }
    }

    @Test
    fun `mcp unknown subcommand prints usage`() = runTest {
        assertDoesNotThrow {
            cmd.handleMcp("/mcp frobnicate", transport = null)
        }
    }

    @Test
    fun `mcp list-tools with failing factory prints error and keeps REPL alive`() = runTest {
        // Фабрика имитирует сбой subprocess/connect; REPL не должен упасть (исключение ловится).
        val failing: (McpTransportConfig) -> McpClient = { throw McpException("boom") }
        assertDoesNotThrow {
            cmd.handleMcp("/mcp list-tools some-cmd", transport = null, mcpClientFactory = failing)
        }
    }
}
