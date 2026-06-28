package com.cliagent.mcp

import kotlinx.serialization.Serializable

/**
 * Конфигурация одного MCP-сервера в едином [com.cliagent.config.AppConfig.mcp] (день 20).
 * Масштабируемая модель: добавление сервера = дописать элемент в массив `mcp` config.json,
 * ноль кода. Хранится декларативно (как в Claude Code) — [command]+[args] → stdio-сервер,
 * [url]+[token] → remote Streamable HTTP.
 *
 * **Имя сервера** ([name]) — routing-ключ и основа namespace-префикса при коллизии tool-имён
 * между серверами (см. [CompositeMcpToolExecutor]). Уникальность имени — ответственность
 * конфигурации; дубликаты логируются, последний побеждает.
 *
 * @param name уникальное имя сервера (routing-key)
 * @param command исполняемый файл stdio-сервера (напр. `"java"`, `"npx"`); null при remote
 * @param args аргументы stdio-сервера (напр. `["-jar",".../mcp-all.jar"]`)
 * @param env переменные окружения subprocess (напр. `{"GITHUB_TOKEN":"..."}`); для stdio
 * @param url URL remote Streamable HTTP-сервера (напр. `"https://mcp.example.com/mcp"`)
 * @param token bearer-токен для remote (заголовок `Authorization`)
 * @param enabled `false` → сервер skip'ается (быстрое отключение без удаления записи)
 */
@Serializable
data class McpServerConfig(
    val name: String,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,
    val token: String? = null,
    val enabled: Boolean = true,
) {
    /**
     * Тип транспорта: [url] приоритетнее [command] (как в [com.cliagent.config.AppConfig] —
     * remote перекрывает stdio). Если не задано ни то, ни другое — это конфиг-ошибка.
     */
    fun toTransport(): McpTransportConfig = when {
        !url.isNullOrBlank() -> McpTransportConfig.Http(url = url, token = token.orEmpty())
        !command.isNullOrBlank() -> McpTransportConfig.Stdio(listOf(command) + args)
        else -> error("McpServerConfig '$name': neither 'url' nor 'command' is set")
    }

    /** Человекочитаемый транспорт для `/mcp`-вывода. */
    fun transportLabel(): String = when {
        !url.isNullOrBlank() -> "http: $url"
        !command.isNullOrBlank() -> "stdio: $command ${args.joinToString(" ")}"
        else -> "<misconfigured: no url/command>"
    }
}
