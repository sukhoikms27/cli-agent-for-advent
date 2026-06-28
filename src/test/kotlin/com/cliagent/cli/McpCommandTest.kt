package com.cliagent.cli

import com.cliagent.mcp.McpServerConfig
import com.cliagent.mcp.McpTransportConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Юнит-тесты `/mcp` (день 16 → день 20 multi-server): конфиг-ветки и путь исключений покрываются
 * без реального MCP-сервера. mordant non-TTY в тестах → no-op для спиннеров.
 *
 * День 20: `handleMcp` принимает `List<McpServerConfig>` (массив) вместо одного транспорта.
 * Add/remove пишут в config.json — тестируются в [com.cliagent.config.ConfigRepositoryTest]
 * (там через @TempDir); здесь проверяются read-only ветки: сводка, unknown subcommand, summary
 * с серверами (без connect — serverSummary делает connect, поэтому тестируем сводку пустого списка
 * и unknown subcommand, не дёргающие сеть/соединение).
 */
class McpCommandTest {

    private val cmd = ChatCommand()

    @Test
    fun `mcp with no servers prints empty-status`() = runTest {
        assertDoesNotThrow {
            cmd.handleMcp("/mcp", servers = emptyList())
        }
    }

    @Test
    fun `mcp unknown subcommand prints usage`() = runTest {
        assertDoesNotThrow {
            cmd.handleMcp("/mcp frobnicate", servers = emptyList())
        }
    }

    @Test
    fun `mcp add without name prints usage`() = runTest {
        // нет имени → early-return с usage, без записи файла
        assertDoesNotThrow {
            cmd.handleMcp("/mcp add", servers = emptyList())
        }
    }

    @Test
    fun `mcp add malformed (no dash no url) prints error`() = runTest {
        // нет '--' и '--url' → ошибка формата, без записи
        assertDoesNotThrow {
            cmd.handleMcp("/mcp add someserver justwords", servers = emptyList())
        }
    }

    @Test
    fun `mcp remove without name prints usage`() = runTest {
        assertDoesNotThrow {
            cmd.handleMcp("/mcp remove", servers = emptyList())
        }
    }

    @Test
    fun `mcpServerConfig toTransport sanity`() {
        // косвенная проверка модели через репозитарный тест; здесь — только smoke
        val s = McpServerConfig(name = "x", command = "java", args = listOf("-jar", "y"))
        val t = s.toTransport()
        assert(t is McpTransportConfig.Stdio)
        assert((t as McpTransportConfig.Stdio).command == listOf("java", "-jar", "y"))
    }
}
