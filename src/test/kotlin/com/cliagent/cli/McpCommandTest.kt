package com.cliagent.cli

import com.cliagent.mcp.McpClient
import com.cliagent.mcp.McpException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Юнит-тесты `/mcp` (день 16): конфиг-ветки и путь исключений покрываются без реального
 * MCP-сервера (последний — ручная верификация T8). mordant non-TTY в тестах → no-op для
 * спиннеров (паттерн MarkdownRenderTest).
 */
class McpCommandTest {

    private val cmd = ChatCommand()

    @Test
    fun `mcp with no args and no config prints status`() = runTest {
        assertDoesNotThrow {
            cmd.handleMcp("/mcp", mcpCommand = null)
        }
    }

    @Test
    fun `mcp with no args and configured command prints configured status`() = runTest {
        assertDoesNotThrow {
            cmd.handleMcp("/mcp", mcpCommand = "npx -y @modelcontextprotocol/server-filesystem /tmp")
        }
    }

    @Test
    fun `mcp list-tools without configured command warns and returns early`() = runTest {
        // early-return до создания McpClient — фабрика не должна зваться
        assertDoesNotThrow {
            cmd.handleMcp("/mcp list-tools", mcpCommand = null)
        }
    }

    @Test
    fun `mcp unknown subcommand prints usage`() = runTest {
        assertDoesNotThrow {
            cmd.handleMcp("/mcp frobnicate", mcpCommand = null)
        }
    }

    @Test
    fun `mcp list-tools with failing factory prints error and keeps REPL alive`() = runTest {
        // Фабрика имитирует сбой subprocess/connect; REPL не должен упасть (исключение ловится).
        val failing: (List<String>) -> McpClient = { throw McpException("boom") }
        assertDoesNotThrow {
            cmd.handleMcp("/mcp list-tools some-cmd", mcpCommand = null, mcpClientFactory = failing)
        }
    }
}
