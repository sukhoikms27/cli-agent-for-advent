package com.cliagent.llm

import com.cliagent.config.AppConfig

/**
 * Фабрика [LlmClient] — единая точка dispatch по [AppConfig.provider]. День 25: заменила
 * хардкод `OpenAiCompatibleClient(...)` в [com.cliagent.cli.ChatCommand.run].
 *
 * Резолв провайдера: явный [AppConfig.provider] (env `CLI_AGENT_PROVIDER` override) → иначе
 * [LlmProvider.autoDetect] по [AppConfig.baseUrl].
 *
 * День 31: dispatch по провайдеру. OLLAMA → [OllamaNativeClient] (native `/api/chat`, `options`
 * escape-hatch, `keep_alive`, `think`). ZAI/OPENAI_COMPATIBLE → [OpenAiCompatibleClient] (OpenAI-
 * compatible `/chat/completions`). Native client даёт Ollama-специфичные фичи (keep_alive/think/
 * options), недоступные в OpenAI-compat endpoint. Это единственная точка выбора провайдера — вне
 * factory инстанцировать LlmClient-реализации нельзя (архитектурный инвариант).
 *
 * Проверка apiKey: для провайдеров с [LlmProvider.requiresApiKey]==true apiKey не должен быть
 * пустым — иначе бросаем IllegalStateException с подсказкой. Ollama — без auth, пустой apiKey OK.
 *
 * **Базовый URL:** config хранит OpenAI-compat base (`http://localhost:11434/v1`). Для OllamaNativeClient
 * конвертируем в native base через [nativeBaseFrom] (`http://localhost:11434`, без `/v1`).
 */
object LlmClientFactory {

    fun create(config: AppConfig): LlmClient {
        val provider = resolveProvider(config)

        if (provider.requiresApiKey() && config.apiKey.isBlank()) {
            error(
                "Provider '${provider.id}' требует API key, но он пуст. " +
                    "Установите CLI_AGENT_API_KEY или добавьте apiKey в config.json. " +
                    "Для локальной Ollama без auth: provider=ollama."
            )
        }

        // День 31: dispatch по провайдеру. Ollama — native client (options/keep_alive/think),
        // cloud/generic — OpenAI-compatible wire-формат.
        return when (provider) {
            LlmProvider.OLLAMA -> OllamaNativeClient(
                baseUrl = nativeBaseFrom(config.baseUrl),
            )
            LlmProvider.ZAI, LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
            )
        }
    }

    /** Резолв провайдера для отображения в UI/баннере. */
    fun resolveProvider(config: AppConfig): LlmProvider =
        LlmProvider.fromString(config.provider) ?: LlmProvider.autoDetect(config.baseUrl)
}
