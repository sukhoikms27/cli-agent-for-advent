package com.cliagent.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * День 18: E2E-проверка **клиентского** remote-транспорта. В отличие от интеграционного stdio-теста
 * (день 17), здесь поднимается сервер в http-режиме (Streamable HTTP) как отдельный процесс, а клиент
 * подключается к нему через `McpClient(McpTransportConfig.Http(...))` по настоящей сети на localhost —
 * ровно так же, как будет работать деплой на VPS (только URL другой).
 *
 * Покрывает:
 *  - bearer-auth: запрос без/с неверным токеном не проходит handshake;
 *  - `listTools` через Streamable HTTP возвращает `get_repo`;
 *  - `callTool(get_repo)` без GitHub-токена на сервере возвращает tool-error (isError=true) — этого
 *    достаточно для проверки transport/handshake/callTool без реального GitHub-запроса.
 *
 * **Условие запуска:** `-Dcli-agent.e2e.http=1` (тяжёлый — поднимает subprocess + сеть, не должен
 * идти в обычный `./gradlew test`). Сервер-артефакт должен быть собран (`:mcp-server:shadowJar`),
 * путь передаётся через `-Dcli-agent.mcp.jar` (default — стандартное место fat-jar).
 *
 * Запуск:
 *   ./gradlew :mcp-server:shadowJar
 *   ./gradlew test --tests 'com.cliagent.mcp.McpClientHttpE2ETest' \
 *       -Dcli-agent.e2e.http=1 -Dcli-agent.mcp.jar=mcp-server/build/libs/mcp-server-0.1.0-all.jar
 */
@EnabledIfSystemProperty(named = "cli-agent.e2e.http", matches = "1")
class McpClientHttpE2ETest {

    companion object {
        private const val BEARER = "e2e-bearer-secret"
        private const val JAR_DEFAULT = "mcp-server/build/libs/mcp-server-0.1.0-all.jar"

        private var process: Process? = null
        private var port: Int = 0
        private var url: String = ""

        @JvmStatic
        @BeforeAll
        fun startServer() {
            // Свободный порт — чтобы не конфликтовать с запущенными сервисами на CI/локале.
            port = ServerSocket(0).use { it.localPort }
            url = "http://127.0.0.1:$port/mcp"

            val jarPath = System.getProperty("cli-agent.mcp.jar") ?: JAR_DEFAULT
            assertTrue(File(jarPath).exists(), "fat-jar не найден: $jarPath — запустите :mcp-server:shadowJar")

            // Java, под которой крутится сам тест (toolchain JDK 17) — она же запустит сервер.
            // На Windows исполняемый — java.exe, на *nix — java; ищем оба.
            val binDir = File(System.getProperty("java.home"), "bin")
            val javaBin = listOf("java.exe", "java")
                .firstNotNullOfOrNull { File(binDir, it).takeIf { f -> f.exists() }?.absolutePath }
                ?: error("java not found in $binDir")

            val pb = ProcessBuilder(
                javaBin, "-jar", jarPath,
            ).apply {
                // http-режим сервера, без GitHub-токена (callTool вернёт tool-error — это и проверяем).
                environment()["CLI_AGENT_MCP_MODE"] = "http"
                environment()["CLI_AGENT_MCP_HOST"] = "127.0.0.1"
                environment()["CLI_AGENT_MCP_PORT"] = port.toString()
                environment()["CLI_AGENT_MCP_TOKEN"] = BEARER
                // Заглушаем stdout/stderr сервера, чтобы не мешать тест-выводу.
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
                redirectError(ProcessBuilder.Redirect.DISCARD)
            }
            process = pb.start()

            // Ждём готовности: сервер (embeddedServer CIO) стартует за секунды. Опрашиваем TCP-порт.
            val ready = waitForPort(port, timeoutMillis = 15_000)
            assertTrue(ready, "сервер не открыл порт $port за 15с — см. логи запуска")
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            process?.let { p ->
                runCatching { p.descendants().forEach { it.destroyForcibly() } }
                p.destroy()
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
                runCatching { p.descendants().forEach { it.destroyForcibly() } }
            }
            process = null
        }

        private fun waitForPort(port: Int, timeoutMillis: Long): Boolean {
            // Сервер готов, когда порт ЗАНЯТ им — попытка открыть ServerSocket на нём бросает
            // BindException ("address already in use"). Свободный порт = сервер ещё не стартовал.
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                try {
                    ServerSocket(port).use { /* порт свободен → сервер не готов */ }
                } catch (_: Exception) {
                    return true // порт занят сервером → готов
                }
                Thread.sleep(150)
            }
            return false
        }
    }

    @Test
    fun `listTools over remote Streamable HTTP returns get_repo`() = runBlocking {
        val client = McpClient(McpTransportConfig.Http(url = url, token = BEARER))
        withTimeout(60.seconds) {
            try {
                client.connect()
                val tools = client.listTools()
                assertTrue(tools.any { it.name == "get_repo" }, "server should expose get_repo over HTTP: $tools")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `callTool get_repo without server-side github token returns tool-error over HTTP`() = runBlocking {
        val client = McpClient(McpTransportConfig.Http(url = url, token = BEARER))
        withTimeout(60.seconds) {
            try {
                client.connect()
                val result = client.callTool("get_repo", mapOf("owner" to "JetBrains", "repo" to "kotlin"))
                assertTrue(result.isError, "expected tool-error without GitHub token: $result")
                assertTrue(
                    result.text.contains("token", ignoreCase = true),
                    "error should mention token: ${result.text}",
                )
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `wrong bearer token fails handshake`() = runBlocking {
        val client = McpClient(McpTransportConfig.Http(url = url, token = "WRONG-TOKEN"))
        withTimeout(60.seconds) {
            var threw = false
            try {
                client.connect()
                client.listTools() // handshake/initialize отшьётся 401 на сервере
            } catch (e: McpException) {
                threw = true
            } catch (e: Exception) {
                // SDK может обернуть ошибку транспорта по-разному — главное, что список не вернулся.
                threw = true
            } finally {
                runCatching { client.close() }
            }
            assertTrue(threw, "wrong bearer should prevent successful handshake/listTools")
        }
    }
}
