package com.cliagent.rag

import com.cliagent.config.AppPaths
import com.cliagent.llm.LlmResult
import com.cliagent.rag.embedding.EmbeddingClient

/**
 * День 22: переиспользуемый retrieval-класс для RAG. Лекция недели 5: запрос → эмбеддинг запроса →
 * cosine-поиск топ-K чанков в индексе → результат для инъекции в промпт.
 *
 * До этого паттерн (load → embed(query) → topK) был размазан по [com.cliagent.cli.RagCommands.handleSearch]
 * и [ChunkingComparison.probe]. Здесь он выделен в одно место (DRY) и переиспользуется агентом
 * ([com.cliagent.agent.ContextAwareAgent]) и командой `/rag eval`.
 *
 * **Мягкая деградация:** все ожидаемые ошибки (Ollama недоступна, пустой/битый индекс, ошибка
 * эмбеддинга) возвращают `null` вместо throw — агент отвечает без RAG-блока, как в дни 1–21.
 * `CancellationException` пробрасывается (корутины, AGENTS.md).
 *
 * @param embedder эмбеддер запроса; lifecycle НЕ управляется здесь — владелец (ChatCommand /
 *                 RagCommands) открывает/закрывает его в `try/finally`.
 * @param store    источник индекса (default — `AppPaths.ragIndexFile`)
 * @param topK     сколько чанков извлекать (default 5; из [RagConfig.topK])
 * @param fallbackStore резервный источник индекса — если основной [store] пуст (нет embedded-чанков),
 *                 пробуем его. По умолчанию — per-strategy файл дефолтной стратегии (баг дня 21:
 *                 `/rag index` писал только туда, а не в основной index.json).
 */
class RagRetriever(
    private val embedder: EmbeddingClient,
    private val store: JsonRagStore = JsonRagStore(AppPaths.ragIndexFile),
    private val topK: Int = 5,
    private val fallbackStore: JsonRagStore? = null,
) {

    /**
     * Топ-K релевантных чанков для [query], отсортированных по убыванию косинусного сходства.
     *
     * @return `null` если индекс пуст/битый (нет embedded-чанков) либо эмбеддер вернул ошибку;
     *         пустой список если индекс есть, но сходство нулевое; иначе 1..topK чанков.
     */
    suspend fun retrieve(query: String): List<ScoredChunk>? {
        val index = loadIndexWithFallback()
        if (index.embeddedChunks.isEmpty()) return null

        val result = embedder.embed(listOf(query))
        val qVec = when (result) {
            is LlmResult.Error -> return null
            is LlmResult.Success -> result.data.firstOrNull() ?: return null
        }
        return topK(qVec, index.chunks, k = topK)
    }

    /**
     * Грузит индекс из основного [store]; если там нет embedded-чанков — пробует [fallbackStore].
     * Возвращает «лучший доступный» индекс (может быть пустым — тогда [retrieve] даст null).
     */
    private suspend fun loadIndexWithFallback(): RagIndex {
        val primary = store.load()
        if (primary.embeddedChunks.isNotEmpty()) return primary
        return fallbackStore?.load()?.takeIf { it.embeddedChunks.isNotEmpty() } ?: primary
    }
}

