package com.cliagent.review

import com.cliagent.config.AppConfig
import com.cliagent.config.ConfigRepository
import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmClientFactory
import com.cliagent.rag.JsonRagStore
import com.cliagent.rag.RagRetriever
import com.cliagent.rag.embedding.OllamaEmbeddingClient
import java.nio.file.Path

/**
 * Wiring review-bot'а: создаёт LLM-клиент, embedder и RAG-retriever из env/config.
 *
 * Шаблон — [com.cliagent.support.SupportAgentFactory.fromEnv] (зеркало по архитектуре):
 *  1. `ConfigRepository().load()` — общий конфиг cli-agent (`~/.config/cli-agent/config.json` + env
 *     `CLI_AGENT_*`). Падает, если apiKey не задан для cloud → fallback на `AppConfig()`.
 *  2. Резолв провайдера/модели: env `REVIEW_PROVIDER` / `REVIEW_MODEL` (по умолчанию zai/glm-5.1).
 *  3. Embedder — всегда Ollama `nomic-embed-text` (z.ai не предоставляет embeddings endpoint).
 *  4. Изолированный RAG-индекс: [ReviewBotPaths.ragIndexFile] (не общий с dev-assistant).
 *
 * env:
 *  - REVIEW_PROVIDER (default: из config.json или `zai`) — LLM-провайдер: `zai` | `ollama`
 *  - REVIEW_MODEL (default: из config.json; для zai → glm-5.1, для ollama → qwen3:14b)
 *  - REVIEW_RAG_EMBEDDING_URL (default: http://127.0.0.1:11434) — base URL Ollama для embeddings
 *  - REVIEW_RAG_EMBEDDING_MODEL (default: nomic-embed-text) — модель эмбеддингов
 *  - REVIEW_RAG_DISABLED (default: false) — отключить RAG (только LLM)
 */
class ReviewBotFactory(
    val client: LlmClient,
    val model: String,
    val embedder: OllamaEmbeddingClient,
    val ragRetriever: RagRetriever?,
    val ragIndexFile: Path,
) : AutoCloseable {

    override fun close() {
        runCatching { (client as? AutoCloseable)?.close() }
        runCatching { embedder.close() }
    }

    companion object {
        fun fromEnv(): ReviewBotFactory {
            // 1. Базовый конфиг cli-agent (config.json + env overrides CLI_AGENT_*).
            val baseConfig = try {
                ConfigRepository().load()
            } catch (e: IllegalStateException) {
                AppConfig()
            }

            // 2. Резолв провайдера: REVIEW_PROVIDER > config.provider > default zai.
            val rawProvider = System.getenv("REVIEW_PROVIDER") ?: baseConfig.provider.ifBlank { "zai" }
            val provider = when (rawProvider.lowercase().trim()) {
                "z.ai", "zai" -> "zai"
                "ollama", "ollama-local", "local" -> "ollama"
                else -> rawProvider
            }
            val isOllama = provider == "ollama"

            // 3. Резолв модели.
            val model = System.getenv("REVIEW_MODEL")?.takeIf { it.isNotBlank() }
                ?: baseConfig.model.ifBlank { if (isOllama) "qwen3:14b" else "glm-5.1" }

            // 4. Сборка AppConfig для LlmClientFactory.
            val llmConfig = if (isOllama) {
                val ollamaUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434"
                baseConfig.copy(
                    provider = "ollama",
                    baseUrl = "$ollamaUrl/v1",
                    model = model,
                    apiKey = "",
                )
            } else {
                baseConfig.copy(
                    provider = provider,
                    model = model,
                    baseUrl = baseConfig.baseUrl.ifBlank { "https://api.z.ai/api/coding/paas/v4" },
                )
            }
            val client = LlmClientFactory.create(llmConfig)

            // 5. Embedder — всегда Ollama (z.ai не отдаёт embeddings).
            val embeddingUrl = System.getenv("REVIEW_RAG_EMBEDDING_URL")
                ?: System.getenv("OLLAMA_BASE_URL")
                ?: "http://127.0.0.1:11434"
            val embeddingModel = System.getenv("REVIEW_RAG_EMBEDDING_MODEL") ?: "nomic-embed-text"
            val ragEnabled = System.getenv("REVIEW_RAG_DISABLED")?.equals("true", ignoreCase = true) != true

            val ragIndexFile = ReviewBotPaths.ragIndexFile
            val embedder = OllamaEmbeddingClient(baseUrl = embeddingUrl, model = embeddingModel)

            // 6. RAG-retriever (если включён). Store — изолированный, не общий с dev-assistant.
            val retriever = if (ragEnabled) {
                RagRetriever(
                    embedder = embedder,
                    store = JsonRagStore(file = ragIndexFile),
                    topK = 5,
                )
            } else {
                null
            }

            return ReviewBotFactory(
                client = client,
                model = model,
                embedder = embedder,
                ragRetriever = retriever,
                ragIndexFile = ragIndexFile,
            )
        }
    }
}
