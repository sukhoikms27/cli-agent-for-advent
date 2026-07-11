package com.cliagent.config

import com.cliagent.mcp.McpServerConfig
import com.cliagent.rag.RagConfig
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
 * @param provider LLM-провайдер как строка-дискриминатор (день 25: multi-provider support). empty
 *   → [com.cliagent.llm.LlmProvider.autoDetect] по [baseUrl] (z.ai / Ollama / generic). env override
 *   `CLI_AGENT_PROVIDER`. Значения: "zai" | "ollama" | "openai-compatible" (см. [LlmProvider.fromString])
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
    val provider: String = "",
    val maxToolRounds: Int = 8,
    val mcp: List<McpServerConfig> = emptyList(),
    /**
     * День 21–22 (RAG): конфиг индексации + инъекции документов. Default `RagConfig()` → старые
     * config.json грузятся без ошибок (RAG off, как дней 1–20). env override `CLI_AGENT_RAG_*` в
     * [ConfigRepository]. День 21: индексация + `/rag`-команды. День 22: инъекция retrieved-чанков
     * в промпт ([com.cliagent.agent.ContextAwareAgent] + [com.cliagent.agent.PromptBuilder]),
     * toggle `/rag on|off`, eval `/rag eval`.
     */
    val rag: RagConfig = RagConfig(),
    /**
     * День 30 (streaming SSE): режим серверного стриминга ответа. Решает проблему thinking-моделей
     * (qwen3:14b: 40-90с «пустоты» до первого токена) — токены идут по мере генерации, пользователь
     * видит контент сразу.
     *
     * Значения (строка, не bool — для auto-режима):
     *  - `"auto"` (default) — streaming включается автоматически для Ollama (локальные thinking-модели
     *    — главный бенефициар; cloud z.ai и так быстрый). Schema-evolution: старые config.json грузятся
     *    без ошибок → auto → прежний не-streaming путь для cloud (0 регрессий дней 1–29).
     *  - `"true"` — streaming всегда (даже для cloud; для дебага/сравнения latency).
     *  - `"false"` — streaming всегда выключен (поведение дней 1–29 для всех провайдеров).
     *
     * env override `CLI_AGENT_STREAM`. Streaming применяется только в REPL обычном чате (без активной
     * задачи / tools — см. [com.cliagent.cli.ChatCommand.dispatchFreeText]); stage-поток и tool-итерации
     * остаются на batch-пути (MVP).
     */
    val stream: String = "auto",
)
