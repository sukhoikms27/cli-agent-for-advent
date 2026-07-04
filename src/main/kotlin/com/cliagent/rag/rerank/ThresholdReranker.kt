package com.cliagent.rag.rerank

import com.cliagent.rag.ScoredChunk

/**
 * День 23: фильтр релевантности по порогу similarity. Самый простой реранкер из лекции недели 5
 * (вариант «порог similarity» в day23.md): отсекает чанки с косинусным сходством ниже [threshold].
 *
 * Косинусное сходство `∈ [−1, 1]`: 1 — семантически идентичны, 0 — о разном (лекция).
 * Типичные пороги: `0.3` (weak — убрать только явно нерелевантное), `0.5` (умеренный),
 * `0.7` (строгий — только высокорелевантные).
 *
 * Порядок кандидатов сохраняется (они уже отсортированы по убыванию cosine из `topK`).
 * `threshold = 0.0f` → no-op (все чанки ≥ 0 по построению topK) → поведение дня 22.
 *
 * Задел под день 24: при `topK`-после-фильтрации = 0 → «слабый контекст» → режим «не знаю».
 */
class ThresholdReranker(
    private val threshold: Float = 0.3f,
) : Reranker {
    override val name: String = "threshold"

    init {
        require(threshold in -1f..1f) { "threshold must be in [-1, 1], got $threshold" }
    }

    override suspend fun rerank(query: String, candidates: List<ScoredChunk>): List<ScoredChunk> =
        candidates.filter { it.score >= threshold }
}
