package com.cliagent.rag

import com.cliagent.config.AppPaths
import com.cliagent.llm.LlmResult
import com.cliagent.rag.embedding.EmbeddingClient
import com.cliagent.rag.rerank.Reranker
import com.cliagent.rag.rewrite.QueryRewriter

/**
 * День 22: переиспользуемый retrieval-класс для RAG. Лекция недели 5: запрос → эмбеддинг запроса →
 * cosine-поиск топ-K чанков в индексе → результат для инъекции в промпт.
 *
 * **День 23 (реранкинг и фильтрация):** pipeline расширен до
 * `rewrite(query) → embed(rewritten) → topK(candidatePoolSize) → rerank → take(topK)`.
 *  - [rewriter] и [reranker] — nullable с defaults `null` → байт-идентичное поведение дня 22
 *    (`topK(qVec, chunks, topK)`). Это backward-compat инвариант.
 *  - Runtime-toggle через [setRewriter]/[setReranker] (как `setRagEnabled` дня 22) — для A/B-сравнения
 *    в одном чате без перезапуска (`/rag rewrite`, `/rag rerank`, `/rag compare-modes`).
 *  - **Мягкая деградация сохранена:** rewriter/reranker при внутренней ошибке возвращают
 *    исходный запрос/порядок (не null), поэтому `retrieve()` падает только при ошибке эмбеддинга/
 *    пустом индексе (как день 22).
 *
 * До дня 22 паттерн (load → embed(query) → topK) был размазан по [com.cliagent.cli.RagCommands.handleSearch]
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
 * @param topK     сколько финальных чанков инжектить в промпт ПОСЛЕ реранкинга (default 5; из
 *                 [RagConfig.topK]). При reranker=null = число извлекаемых чанков (день 22).
 * @param fallbackStore резервный источник индекса — если основной [store] пуст (нет embedded-чанков),
 *                 пробуем его. По умолчанию — per-strategy файл дефолтной стратегии (баг дня 21:
 *                 `/rag index` писал только туда, а не в основной index.json).
 * @param rewriter query rewrite (день 23); null = identity (поведение дня 22). Lifecycle внешний.
 * @param reranker реранкер/фильтр (день 23); null = без 2-го этапа (поведение дня 22). Lifecycle внешний.
 * @param candidatePoolSize топ-K ДО фильтрации (день 23, лекция: «ищем топ-20 кандидатов»);
 *                 применяется только при reranker ≠ null. Default = topK (no-op если reranker=null).
 */
class RagRetriever(
    private val embedder: EmbeddingClient,
    private val store: JsonRagStore = JsonRagStore(AppPaths.ragIndexFile),
    private val topK: Int = 5,
    private val fallbackStore: JsonRagStore? = null,
    // День 23: nullable defaults → байт-идентично дню 22 при null.
    private var rewriter: QueryRewriter? = null,
    private var reranker: Reranker? = null,
    private var candidatePoolSize: Int = topK,
) {

    /** Day 23 runtime-toggle: установить query rewriter (null = identity / день 22). */
    fun setRewriter(rewriter: QueryRewriter?) {
        this.rewriter = rewriter
    }

    /** Day 23 runtime-toggle: установить reranker (null = без 2-го этапа / день 22). */
    fun setReranker(reranker: Reranker?) {
        this.reranker = reranker
    }

    /** Day 23 runtime-toggle: размер candidate-pool (топ-K ДО фильтрации). */
    fun setCandidatePoolSize(size: Int) {
        require(size > 0) { "candidatePoolSize must be > 0, got $size" }
        this.candidatePoolSize = size
    }

    /** @return текущий rewriter (для статус-вывода и compare-modes); null = identity. */
    fun getRewriter(): QueryRewriter? = rewriter

    /** @return текущий reranker (для статус-вывода и compare-modes); null = none. */
    fun getReranker(): Reranker? = reranker

    /**
     * Топ-K релевантных чанков для [query] (после реранкинга/фильтрации, день 23).
     *
     * Pipeline дня 23: `rewrite → embed → topK(candidatePool) → rerank → take(topK)`.
     *  - rewriter=null → query как есть (день 22).
     *  - reranker=null → `topK(qVec, chunks, topK)` (день 22, байт-идентично).
     *  - reranker≠null → ищем pool = `topK(qVec, chunks, max(candidatePoolSize, topK))`, реранжируем,
     *    берём `topK` лучших по новой оценке. Pool не меньше topK — иначе финальный take обрежет
     *    релевантное.
     *
     * @return `null` если индекс пуст/битый (нет embedded-чанков) либо эмбеддер вернул ошибку;
     *         пустой список если индекс есть, но сходство нулевое/порог всё отсёк; иначе 1..topK чанков.
     */
    suspend fun retrieve(query: String): List<ScoredChunk>? {
        val index = loadIndexWithFallback()
        if (index.embeddedChunks.isEmpty()) return null

        // День 23 (B): rewrite ДО эмбеддинга. rewriter=null → query без изменений.
        val rewritten = rewriter?.rewrite(query) ?: query

        val result = embedder.embed(listOf(rewritten))
        val qVec = when (result) {
            is LlmResult.Error -> return null
            is LlmResult.Success -> result.data.firstOrNull() ?: return null
        }

        // День 23 (C): reranker=null → простой topK (байт-идентично дню 22).
        // reranker≠null → candidate-pool (≥ topK) → реранк → финальный topK.
        val activeReranker = reranker
        return if (activeReranker == null) {
            topK(qVec, index.chunks, k = topK)
        } else {
            val poolSize = maxOf(candidatePoolSize, topK)
            val pool = topK(qVec, index.chunks, k = poolSize)
            activeReranker.rerank(rewritten, pool).take(topK)
        }
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

