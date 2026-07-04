package com.cliagent.rag.rerank

import com.cliagent.rag.ScoredChunk

/**
 * День 23: heuristic-реранкер через term-overlap (лексическое перекрытие запрос↔чанк).
 *
 * Мотивация (лекция недели 5): cosine-сходство эмбеддингов может дать ложные срабатывания —
 * «обзор CICD инструментов» и «как настроить CICD для KMP» семантически близки, но только второй
 * отвечает на запрос. Term-overlap — дешёвый лексический сигнал, корректирующий семантический:
 * чанк, разделяющий ключевые термины с запросом, повышается даже при среднем cosine.
 *
 * Формула: `finalScore = alpha * cosine + (1 - alpha) * jaccard(queryTerms, chunkTerms)`.
 *  - `alpha = 1.0` → только cosine → порядок дня 22 (no-op rerank).
 *  - `alpha = 0.5` (default) → равный вклад семантики и лексики.
 *
 * Jaccard = |A ∩ B| / |A ∪ B| ∈ [0, 1]. Токенизация: lowercase, по границам слов (без пунктуации).
 * Чистая функция (без IO), детерминированная.
 *
 * @param alpha вес cosine в финальной оценке; 1.0 → no-op (поведение дня 22)
 */
class HeuristicReranker(
    private val alpha: Float = 0.5f,
) : Reranker {
    override val name: String = "heuristic"

    init {
        require(alpha in 0f..1f) { "alpha must be in [0, 1], got $alpha" }
    }

    override suspend fun rerank(query: String, candidates: List<ScoredChunk>): List<ScoredChunk> {
        val queryTerms = tokenize(query)
        return candidates
            // finalScore = alpha*cosine + (1-alpha)*jaccard; сохраняем chunk в Pair для пересортировки
            .map { sc -> sc to (alpha * sc.score + (1f - alpha) * jaccard(queryTerms, tokenize(sc.chunk.text))) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.isNotBlank() }
            .toSet()

    /** Jaccard = |A ∩ B| / |A ∪ B|; пустые множества → 0 (защита от деления на 0). */
    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        val union = a.size + b.size - a.intersect(b).size
        if (union == 0) return 0f
        return a.intersect(b).size.toFloat() / union
    }
}
