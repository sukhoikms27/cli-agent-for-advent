package com.cliagent.agent

import com.cliagent.llm.model.ToolDefinition
import kotlinx.coroutines.CancellationException

/**
 * День 34 (refactoring): универсальный composite для **любых** [ToolExecutor]'ов.
 *
 * Обобщение [com.cliagent.mcp.CompositeMcpToolExecutor] (завязанного на MCP) до произвольного
 * списка executor'ов. Это позволяет смешивать в одной сессии:
 *  - in-process executor'ы ([FileToolExecutor], [com.cliagent.support.tools.TicketToolExecutor])
 *  - MCP-executor'ы ([com.cliagent.mcp.McpToolExecutor])
 *  - любые будущие реализации [ToolExecutor]
 *
 * ## Что делает (симметрично CompositeMcpToolExecutor)
 *  - [definitions] → merge tools всех executor'ов в единый `List<ToolDefinition>` + routing-таблица.
 *  - [call] → lookup по таблице → вызов нужного executor'а. LLM не знает о разделении.
 *  - [close] → закрывает все executor'ы.
 *
 * ## Routing-таблица и prefix-on-collision
 * По умолчанию tools видны LLM под своими raw именами. Если одно и то же имя объявлено в нескольких
 * executor'ах — все экземпляры переименуются в `source__tool` (как в CompositeMcpToolExecutor).
 *
 * ## Graceful degradation
 * Упавший/недоступный executor → warning через [logger] + skip его tools. Остальные продолжают.
 *
 * @param executors список именованных executor'ов; [NamedToolExecutor.name] используется для
 *   prefix-on-collision и диагностики (как `server.name` в MCP-варианте).
 * @param logger sink для warning'ов (default stderr).
 */
class CompositeToolExecutor(
    private val executors: List<NamedToolExecutor>,
    private val logger: (String) -> Unit = { msg -> System.err.println(msg) },
) : ToolExecutor {

    /**
     * Routing-таблица: exposed tool-name (как видит LLM) → (source name, raw tool-name).
     * Заполняется в [definitions]. null = discovery ещё не выполнялся.
     */
    private var routing: Map<String, Pair<String, String>>? = null

    /**
     * Сливает tools всех executor'ов и строит routing-таблицу. Первый вызов выполняет discovery;
     * последующие — возвращают кэш (исполнитель persistent в сессии).
     *
     * Упавший executor — skip с warning, не валит остальные.
     */
    override suspend fun definitions(): List<ToolDefinition> {
        routing?.let { routingTable ->
            return buildDefinitions(routingTable)
        }
        // Сбор: sourceName → List<raw tool-name> (только успешно подключенные)
        val perSource: MutableMap<String, List<String>> = linkedMapOf()
        for (named in executors) {
            val rawNames = try {
                named.delegate.definitions().map { it.function.name }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger("⚠️ Tool executor '${named.name}' unavailable: ${e.message}; skipping its tools.")
                continue
            }
            perSource[named.name] = rawNames
        }
        routing = buildRoutingTable(perSource)
        return buildDefinitions(routing!!)
    }

    /**
     * Маршрутизирует вызов по routing-таблице. Возвращает текстовый результат (включая описание
     * tool-ошибки — конвенция [ToolExecutor.call]). Если имя не найдено (LLM выдумала tool) —
     * возвращает диагностику, не бросая, чтобы LLM могла самокорректироваться.
     */
    override suspend fun call(name: String, args: Map<String, Any?>): String {
        val table = routing ?: definitions().let { routing!! }
        val (sourceName, rawName) = table[name]
            ?: return "Tool '$name' not found across ${executors.size} executor(s). Available: ${table.keys.sorted()}"
        val exec = executors.firstOrNull { it.name == sourceName }?.delegate
            ?: return "Executor '$sourceName' is not registered (routing inconsistent)."
        return try {
            exec.call(rawName, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "Executor '$sourceName' call '$rawName' failed: ${e.message}"
        }
    }

    /** Закрывает все executor'ы. CancellationException re-throw (конвенция AGENTS.md). */
    override suspend fun close() {
        val errors = mutableListOf<Throwable>()
        for (named in executors) {
            try {
                named.delegate.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errors += e
            }
        }
        routing = null
    }

    // ── internals ──

    /**
     * Routing-таблица с prefix-on-collision. Для каждого raw имени, объявленного в ≥2 executor'ах,
     * все экземпляры переименовываются в `source__tool`. Уникальные имена остаются raw.
     */
    private fun buildRoutingTable(perSource: Map<String, List<String>>): Map<String, Pair<String, String>> {
        // имя → список источников, где оно объявлено
        val nameToSources: Map<String, List<String>> = perSource
            .flatMap { (source, names) -> names.map { it to source } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.distinct() }

        val table = mutableMapOf<String, Pair<String, String>>()
        for ((source, names) in perSource) {
            for (rawName in names) {
                val sources = nameToSources[rawName].orEmpty()
                val exposed = if (sources.size > 1) "${source}__${rawName}" else rawName
                table[exposed] = source to rawName
            }
        }
        return table
    }

    /**
     * Возвращает List<ToolDefinition> для поля `tools` LLM-запроса. Перечитывает tools executor'ов
     * и применяет переименование коллизий (по routing-таблице).
     */
    private suspend fun buildDefinitions(table: Map<String, Pair<String, String>>): List<ToolDefinition> {
        val result = mutableListOf<ToolDefinition>()
        for ((exposed, routed) in table) {
            val (sourceName, rawName) = routed
            val exec = executors.firstOrNull { it.name == sourceName }?.delegate ?: continue
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

/**
 * Именованный исполнитель: связывает [ToolExecutor] с человекочитаемым именем источника.
 *
 * [name] используется для:
 *  - prefix-on-collision в [CompositeToolExecutor] (`source__tool`)
 *  - диагностики в логах и возвращаемых tool-ошибках
 *
 * @param name уникальное имя источника (например, "file", "github", "mcp-weather").
 * @param delegate сам исполнитель.
 */
data class NamedToolExecutor(
    val name: String,
    val delegate: ToolExecutor,
)
