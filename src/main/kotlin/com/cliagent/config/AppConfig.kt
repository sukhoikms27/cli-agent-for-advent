package com.cliagent.config

import com.cliagent.mcp.McpServerConfig
import kotlinx.serialization.Serializable

/**
 * Единая конфигурация приложения (день 20). Хранится как JSON в [AppPaths.configFile]
 * (`~/.config/cli-agent/config.json`) — масштабируемая точка конфигурации.
 *
 * **Приоритет источников** (в [ConfigRepository]): env vars (override одиночных полей, для
 * секретов/CI) > `config.json` (primary source для [mcp]-массива и [maxToolRounds]) >
 * `local.properties` (legacy fallback). Массив серверов задаётся ТОЛЬКО через `config.json`.
 *
 * **Schema evolution** (AGENTS.md): все поля с defaults — старые/неполные файлы грузятся без
 * ошибок. Удаление полей запрещено; новые поля добавлять только с дефолтами.
 *
 * @param apiKey ключ z.ai (required при работе LLM). empty в файле — env override заполнит
 * @param model имя модели (default glm-5.1)
 * @param baseUrl API base URL (default z.ai coding endpoint)
 * @param maxToolRounds лимит раундов tool-use loop в [com.cliagent.agent.ContextAwareAgent]
 *   (default 8) — для «длинного флоу» оркестрации нескольких MCP-серверов (день 20)
 * @param mcp массив MCP-серверов (default empty — tools отключены, поведение дней 1–16).
 *   Каждый элемент — [McpServerConfig] (stdio или remote HTTP)
 */
@Serializable
data class AppConfig(
    val apiKey: String = "",
    val model: String = "glm-5.1",
    val baseUrl: String = "https://api.z.ai/api/coding/paas/v4",
    val maxToolRounds: Int = 8,
    val mcp: List<McpServerConfig> = emptyList(),
)
