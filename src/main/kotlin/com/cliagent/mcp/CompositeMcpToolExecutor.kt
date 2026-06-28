package com.cliagent.mcp

import com.cliagent.agent.ToolExecutor
import com.cliagent.llm.model.FunctionDef
import com.cliagent.llm.model.ToolDefinition
import kotlinx.coroutines.CancellationException

/**
 * Multi-server оркестрация MCP (день 20). Реализует [ToolExecutor] поверх **нескольких**
 * [McpToolExecutor] (по одному на сервер из [McpServerConfig]). Agent-слой
 * ([com.cliagent.agent.ContextAwareAgent]) **не меняется** — он уже работает с одним
 * [ToolExecutor]; этот класс прозрачно агрегирует N серверов.
 *
 * ## Что делает
 * - [definitions] → merge tools всех `enabled` серверов в единый `List<ToolDefinition>`;
 *   строит routing-таблицу (exposed tool-name → сервер + raw имя).
 * - [call] → lookup по таблице → вызов нужного сервера. LLM не знает о разделении.
 * - [close] → закрывает все соединения.
 *
 * ## Routing-таблица и коллизии (Р2 — prefix-on-collision)
 * По умолчанию tool'ы видны LLM под своими **raw** именами (0 регрессий single-server). Если
 * одно и то же имя tool'а объявлено на **нескольких** серверах — все его экземпляры
 * переименовываются в `server__tool`, таблица использует префиксованные имена.
 *
 * ## Graceful degradation
 * Упавший/недоступный сервер → stderr warning + skip его tools. Остальные серверы продолжают
 * работать; агент **не падает** (соответствует философтии `loadToolsOrNull` в агенте).
 *
 * @param servers конфигурации серверов (из [com.cliagent.config.AppConfig.mcp])
 * @param logger sink для warning'ов (default stderr). В REPL подключается к AppTerminal.
 * @param executorFactory DI-шов для тестов: позволяет подсунуть фейк вместо реального
 *   [McpToolExecutor] (как `factory` в [McpToolExecutor]).
 */
class CompositeMcpToolExecutor(
    private val servers: List<McpServerConfig>,
    private val logger: (String) -> Unit = { msg -> System.err.println(msg) },
    private val executorFactory: (McpTransportConfig) -> McpToolExecutor = ::McpToolExecutor,
) : ToolExecutor {

    /** Один [McpToolExecutor] на сервер, lazy-создаётся при первом обращении. */
    private val executors: MutableMap<String, McpToolExecutor> = mutableMapOf()

    /**
     * Routing-таблица: exposed tool-name (как видит LLM) → (serverName, raw tool-name на сервере).
     * Заполняется в [definitions]. null = discovery ещё не выполнялся.
     */
    private var routing: Map<String, Pair<String, String>>? = null

    /**
     * Сливает tools всех `enabled` серверов и строит routing-таблицу. Первый вызов выполняет
     * lazy-discovery (подключение к каждому серверу); последующие — возвращают кэш (серверы
     * persistent в сессии, как single [McpToolExecutor]).
     *
     * Сервер с невалидным конфигом (ни url, ни command) или недоступный соединением — skip с
     * warning, не валит остальные.
     */
    override suspend fun definitions(): List<ToolDefinition> {
        routing?.let { routingTable ->
            return buildDefinitions(routingTable)
        }
        // Сбор: serverName → List<raw tool-name> (только успешно подключенные серверы)
        val perServer: MutableMap<String, List<String>> = linkedMapOf()
        for (server in servers.filter { it.enabled }) {
            val rawNames = try {
                val exec = executorFor(server)
                exec.definitions().map { it.function.name }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger("⚠️ MCP server '${server.name}' unavailable: ${e.message}; skipping its tools.")
                continue
            }
            perServer[server.name] = rawNames
        }
        routing = buildRoutingTable(perServer)
        return buildDefinitions(routing!!)
    }

    /**
     * Маршрутизирует вызов на сервер по routing-таблице. [name] — exposed имя (как в
     * [definitions]). Возвращает текстовый результат (включая описание tool-ошибки — конвенция
     * [McpToolExecutor.call]). Если имя не найдено (LLM выдумала tool) — возвращает диагностику,
     * не бросая, чтобы LLM могла самокорректироваться.
     */
    override suspend fun call(name: String, args: Map<String, Any?>): String {
        val table = routing ?: definitions().let { routing!! }
        val (serverName, rawName) = table[name]
            ?: return "Tool '$name' not found across ${servers.size} MCP server(s). Available: ${table.keys.sorted()}"
        val exec = executors[serverName]
            ?: return "Server '$serverName' is not connected (routing inconsistent)."
        return try {
            exec.call(rawName, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "Server '$serverName' call '$rawName' failed: ${e.message}"
        }
    }

    /** Закрывает все активные соединения. CancellationException re-throw (конвенция AGENTS.md). */
    override suspend fun close() {
        val errors = mutableListOf<Throwable>()
        for ((_, exec) in executors.toList()) {
            try {
                exec.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errors += e
            }
        }
        executors.clear()
        routing = null
    }

    /**
     * Сводка серверов для REPL `/mcp`: имя, транспорт, кол-во tools, статус. Discovery выполняется
     * лениво (подключается к каждому). Удобен для `/mcp` без отдельного list-tools на сервер.
     */
    suspend fun serverSummary(): List<ServerSummary> {
        definitions()   // прогрев routing-таблицы (опционально; даёт кол-во tools)
        return servers.filter { it.enabled }.map { server ->
            val toolCount = routing?.count { (_, routed) -> routed.first == server.name } ?: 0
            val connected = executors.containsKey(server.name)
            ServerSummary(
                name = server.name,
                transport = server.transportLabel(),
                toolCount = toolCount,
                connected = connected,
                enabled = server.enabled,
            )
        }
    }

    /** Структура сводки сервера для REPL-вывода. */
    data class ServerSummary(
        val name: String,
        val transport: String,
        val toolCount: Int,
        val connected: Boolean,
        val enabled: Boolean,
    )

    // ── internals ──

    private suspend fun executorFor(server: McpServerConfig): McpToolExecutor {
        return executors.getOrPut(server.name) {
            val transport = try {
                server.toTransport()
            } catch (e: Throwable) {
                throw e   // невалидный конфиг (ни url, ни command) — пробрасывается для skip в caller
            }
            executorFactory(transport)
        }
    }

    /**
     * Routing-таблица с prefix-on-collision. Для каждого raw имени, объявленного на ≥2 серверах,
     * все экземпляры переименовываются в `server__tool`. Уникальные имена остаются raw.
     */
    private fun buildRoutingTable(perServer: Map<String, List<String>>): Map<String, Pair<String, String>> {
        // имя → список серверов, где оно объявлено
        val nameToServers: Map<String, List<String>> = perServer
            .flatMap { (server, names) -> names.map { it to server } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.distinct() }

        val table = mutableMapOf<String, Pair<String, String>>()
        for ((server, names) in perServer) {
            for (rawName in names) {
                val servers = nameToServers[rawName].orEmpty()
                val exposed = if (servers.size > 1) "${server}__${rawName}" else rawName
                table[exposed] = server to rawName
            }
        }
        return table
    }

    /**
     * Возвращает List<ToolDefinition> для поля `tools` LLM-запроса. Перечитывает tools серверов
     * и применяет переименование коллизий (по routing-таблице).
     */
    private suspend fun buildDefinitions(table: Map<String, Pair<String, String>>): List<ToolDefinition> {
        val result = mutableListOf<ToolDefinition>()
        for ((exposed, routed) in table) {
            val (serverName, rawName) = routed
            val exec = executors[serverName] ?: continue
            val def = try {
                exec.definitions().firstOrNull { it.function.name == rawName }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            } ?: continue
            // переименование при коллизии: подменяем имя, которое видит LLM
            result += if (exposed == rawName) def else def.copy(function = def.function.copy(name = exposed))
        }
        return result
    }
}
