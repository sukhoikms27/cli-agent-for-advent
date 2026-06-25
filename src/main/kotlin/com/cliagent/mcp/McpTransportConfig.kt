package com.cliagent.mcp

/**
 * Конфиг транспорта MCP-сервера (день 18). До дня 17 был только stdio (subprocess-сервер); теперь
 * добавлен remote Streamable HTTP. `McpClient` ветвит `connect`/`close` по этому sealed-типу.
 *
 * **Приоритет в [com.cliagent.config.AppConfig]:** если заданы оба ([Stdio] + [Http]), remote
 * перекрывает локальный — один клиент не может одновременно говорить с двумя серверами.
 */
sealed interface McpTransportConfig {

    /**
     * Локальный stdio-сервер: subprocess через `ProcessBuilder`, JSON-RPC по stdin/stdout
     * (день 16–17, без изменений). [command] — argv запуска сервера (напр. `["npx","-y",...]`).
     */
    data class Stdio(val command: List<String>) : McpTransportConfig

    /**
     * Remote Streamable HTTP-сервер (день 18 — деплой на VPS). [url] — полный URL эндпоинта
     * (напр. `https://mcp.example.com/mcp`), [token] — bearer для заголовка `Authorization`.
     * GitHub-токен клиенту НЕ нужен — он держится на сервере (улучшение безопасности remote-режима).
     */
    data class Http(val url: String, val token: String) : McpTransportConfig
}
