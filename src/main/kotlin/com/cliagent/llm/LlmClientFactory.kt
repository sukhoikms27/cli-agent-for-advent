package com.cliagent.llm

import com.cliagent.config.AppConfig

/**
 * Фабрика [LlmClient] — единая точка dispatch по [AppConfig.provider]. День 25: заменила
 * хардкод `OpenAiCompatibleClient(...)` в [com.cliagent.cli.ChatCommand.run].
 *
 * Резолв провайдера: явный [AppConfig.provider] (env `CLI_AGENT_PROVIDER` override) → иначе
 * [LlmProvider.autoDetect] по [AppConfig.baseUrl]. Пока все провайдеры → [OpenAiCompatibleClient]
 * (Ollama говорит на OpenAI-compat `/v1/chat/completions`). Seam для будущих нативных клиентов.
 *
 * Проверка apiKey: для провайдеров с [LlmProvider.requiresApiKey]==true apiKey не должен быть
 * пустым — иначе бросаем IllegalStateException с подсказкой. Ollama — без auth, пустой apiKey OK.
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

        // День 25: пока все провайдеры используют OpenAI-compatible wire-формат.
        // Когда появится нативный Anthropic/Gemini — dispatch по provider здесь.
        return OpenAiCompatibleClient(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey
        )
    }

    /** Резолв провайдера для отображения в UI/баннере. */
    fun resolveProvider(config: AppConfig): LlmProvider =
        LlmProvider.fromString(config.provider) ?: LlmProvider.autoDetect(config.baseUrl)
}
