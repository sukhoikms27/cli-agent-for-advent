package com.cliagent.rag.rerank

import com.cliagent.rag.ScoredChunk

/**
 * День 23: второй этап retrieval — реранкинг/фильтрация. Лекция недели 5: после быстрого поиска
 * топ-K кандидатов (миллисекунды) реранкер переоценивает каждый чанк относительно запроса
 * (секунды) → LLM получает отфильтрованные, наиболее релевантные данные.
 *
 * Контракт: [rerank] принимает candidate-pool (результат `topK(queryVec, chunks, candidatePoolSize)`)
 * и возвращает переупорядоченный/отфильтрованный список. Реализация **не** обязана сохранять
 * размер — фильтры (threshold) могут уменьшать список. [com.cliagent.rag.RagRetriever] берёт
 * `.take(topK)` от результата, поэтому усечение до финального topK там, а не здесь.
 *
 * **Мягкая деградация:** при невозможности переоценить (LLM-ошибка, unparseable) реализация
 * обязана вернуть `candidates` в исходном порядке — retrieval не падает (как день 22).
 *
 * `suspend` — LLM-реализации ходят в сеть; чистые (threshold/heuristic) — не suspend по факту.
 */
interface Reranker {
    /** Имя режима для статус-вывода и конфига ("none" | "threshold" | "heuristic" | "llm"). */
    val name: String

    /**
     * @param query      исходный (возможно уже rewrite'нутый) запрос — для LLM/heuristic-оценки
     * @param candidates candidate-pool (топ-K ДО фильтрации), отсортирован по убыванию cosine
     * @return переупорядоченный/отфильтрованный список (мягкая деградация: исходный порядок при ошибке)
     */
    suspend fun rerank(query: String, candidates: List<ScoredChunk>): List<ScoredChunk>
}

/**
 * Тип реранкера/фильтра (поле [com.cliagent.rag.RagConfig.reranker]).
 * Алиасы tolerant к регистру/написанию (как [com.cliagent.rag.chunk.ChunkingStrategyType]).
 */
enum class RerankerType {
    NONE,
    THRESHOLD,
    HEURISTIC,
    LLM;

    companion object {
        /** @param s строка из конфига/CLI; null/unknown → [NONE] (безопасный дефолт = день 22). */
        fun fromString(s: String?): RerankerType {
            val key = s?.trim()?.lowercase() ?: return NONE
            return when {
                key.isEmpty() -> NONE
                key == "none" || key == "off" || key == "no" || key == "disabled" -> NONE
                key.startsWith("threshold") || key == "threshold" || key == "cutoff" || key == "filter" -> THRESHOLD
                key.startsWith("heuristic") || key == "heu" || key == "overlap" || key == "term" -> HEURISTIC
                key == "llm" || key == "ai" || key == "model" || key == "glm" || key == "judge" || key == "crossencoder" -> LLM
                else -> NONE
            }
        }
    }
}
