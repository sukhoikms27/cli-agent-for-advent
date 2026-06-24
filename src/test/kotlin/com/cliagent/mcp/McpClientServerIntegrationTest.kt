package com.cliagent.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * День 17: интеграционная проверка реальной связки McpClient → наш GitHubMcpServer (subprocess,
 * stdio). Опциональная — запускается только при `CLI_AGENT_MCP_INTEGRATION=1` и требует собранного
 * launch-скрипта (`CLI_AGENT_MCP_BIN`). Без GitHub-токена `get_repo` возвращает tool-error — этого
 * достаточно для проверки transport/handshake/listTools/callTool (реальный HTTP до GitHub и LLM
 * function-calling проверяются вручную, см. plan day17 T8).
 *
 * Запуск:
 *   ./gradlew :mcp-server:installDist
 *   CLI_AGENT_MCP_INTEGRATION=1 \
 *   CLI_AGENT_MCP_BIN=$PWD/mcp-server/build/install/mcp-server/bin/mcp-server \
 *   ./gradlew test --tests 'com.cliagent.mcp.McpClientServerIntegrationTest'
 */
@EnabledIfEnvironmentVariable(named = "CLI_AGENT_MCP_INTEGRATION", matches = "1")
class McpClientServerIntegrationTest {

    @Test
    fun `connect listTools and callTool against github mcp server`() {
        val bin = System.getenv("CLI_AGENT_MCP_BIN") ?: error("set CLI_AGENT_MCP_BIN to the mcp-server launch script")
        val client = McpClient(listOf(bin))
        runBlocking {
            // Внешний hard-timeout:哪怕 McpClient.close() зависнет на join транспортного scope,
            // тест упадёт за 60с, а не повесит gradle-worker.
            withTimeout(60.seconds) {
                try {
                    client.connect()
                    val tools = client.listTools()
                    assertTrue(tools.any { it.name == "get_repo" }, "server should expose get_repo: $tools")

                    // Без токена сервер возвращает tool-error (isError=true), не бросая exception.
                    val result = client.callTool("get_repo", mapOf("owner" to "JetBrains", "repo" to "kotlin"))
                    assertTrue(result.isError, "expected tool-error without token: $result")
                    assertTrue(result.text.contains("token", ignoreCase = true), "error should mention token: ${result.text}")
                } finally {
                    client.close()
                }
            }
        }
        // Defensive: добиваем любой задержавшийся subprocess сервера (launch-script может породить
        // java-ребёнка, не всегда полностью убиваемый через ProcessHandle.descendants).
        killLingeringMcpServer()
    }

    private fun killLingeringMcpServer() {
        ProcessHandle.allProcesses().forEach { ph ->
            val cmd = ph.info().commandLine().orElse("")
            if (cmd.contains("GitHubMcpServer") || cmd.contains("mcp.server.GitHubMcpServer")) {
                runCatching { ph.destroyForcibly() }
            }
        }
    }
}
