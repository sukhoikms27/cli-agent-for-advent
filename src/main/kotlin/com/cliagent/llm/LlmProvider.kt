package com.cliagent.llm

/**
 * Дискриминатор LLM-провайдера (multi-provider support). Определяет поведение auth
 * ([requiresApiKey]) и служит ключом для [LlmClientFactory]. День 25: основная мотивация —
 * подключение локальной Ollama (Qwen2.5) наряду с облачным z.ai.
 *
 * Ollama умеет OpenAI-compatible endpoint (`/v1/chat/completions`), поэтому пока все провайдеры
 * маппятся на [OpenAiCompatibleClient]. Enum + factory — seam для будущих нативных клиентов
 * (Anthropic messages, Gemini generateContent).
 */
enum class LlmProvider(val id: String) {
    /** z.ai (Zhipu) — облачный GLM-5.x. Требует Bearer API key. */
    ZAI("zai"),

    /** Локальная Ollama (Qwen2.5 и др.). Auth не требуется; ключ не нужен. */
    OLLAMA("ollama"),

    /** Прочие OpenAI-compatible (vLLM, LM Studio, OpenRouter, Together, DeepSeek-API). */
    OPENAI_COMPATIBLE("openai-compatible");

    /** true для провайдеров, требующих Bearer API key (z.ai + generic). Ollama → false. */
    fun requiresApiKey(): Boolean = this != OLLAMA

    companion object {
        /**
         * Разрешение провайдера по строке из config/env. null/blank → null (caller решает autoDetect).
         * Регистронезависимо: "ZAI"/"zai" → ZAI, "Ollama"/"OLLAMA" → OLLAMA.
         * Неизвестная строка → OPENAI_COMPATIBLE (catch-all, без падения).
         */
        fun fromString(s: String?): LlmProvider? {
            if (s.isNullOrBlank()) return null
            return when (s.trim().lowercase()) {
                ZAI.id -> ZAI
                OLLAMA.id -> OLLAMA
                "ollama-local", "local" -> OLLAMA
                "openai", "openai-compatible", "generic" -> OPENAI_COMPATIBLE
                else -> OPENAI_COMPATIBLE
            }
        }

        /**
         * Авто-определение провайдера по baseUrl (когда `provider` не задан явно).
         * "z.ai" в URL → ZAI; "localhost:11434" / "127.0.0.1:11434" / "ollama" → OLLAMA;
         * прочее → OPENAI_COMPATIBLE (безопасный default).
         */
        fun autoDetect(baseUrl: String): LlmProvider {
            val lower = baseUrl.lowercase()
            return when {
                "z.ai" in lower -> ZAI
                ":11434" in lower || "localhost" in lower && "ollama" in lower -> OLLAMA
                "ollama" in lower -> OLLAMA
                else -> OPENAI_COMPATIBLE
            }
        }
    }
}
