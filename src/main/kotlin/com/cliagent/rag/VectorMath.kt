package com.cliagent.rag

import kotlin.math.sqrt

/**
 * День 21: векторная арифметика для RAG-поиска. Лекция недели 5: косинусное сходство сравнивает
 * векторы — `1` семантически идентичны, `0` о разном, `−1` противоположны.
 *
 * Реализовано вручную (без внешних либ — FAISS/NumPy в JVM-CLI избыточен для малого корпуса).
 * Линейный скан по `List<Float>` достаточен: десятки-сотни чанков обрабатываются за миллисекунды.
 */

/**
 * Косинусное сходство двух векторов: `a·b / (‖a‖·‖b‖)`. Возвращает 0 если любой вектор нулевой
 * (защита от деления на 0). Разная длина → сравнение по общей префиксной длине (защита от мусора).
 *
 * @return значение в [−1, 1]; 1 — идентичны по смыслу, 0 — ортогональны, −1 — противоположны.
 */
fun cosine(a: List<Float>, b: List<Float>): Float {
    if (a.isEmpty() || b.isEmpty()) return 0f
    val n = minOf(a.size, b.size)
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in 0 until n) {
        val av = a[i].toDouble()
        val bv = b[i].toDouble()
        dot += av * bv
        normA += av * av
        normB += bv * bv
    }
    val denom = sqrt(normA) * sqrt(normB)
    if (denom == 0.0) return 0f
    return (dot / denom).toFloat()
}

/**
 * Топ-K ближайших чанков к [query] (по убыванию сходства). Линейный скан; чанки без embedding
 * skip'ются. Если в индексе меньше K embedded-чанков — возвращается сколько есть.
 *
 * @param query вектор запроса
 * @param chunks кандидаты (берутся только с непустым [RagChunk.embedding])
 * @param k сколько вернуть (default 3)
 */
fun topK(
    query: List<Float>,
    chunks: List<RagChunk>,
    k: Int = 3,
): List<ScoredChunk> {
    val limit = k.coerceAtLeast(0)
    if (limit == 0) return emptyList()
    return chunks.asSequence()
        .filter { it.embedding != null }
        .map { ScoredChunk(it, cosine(query, it.embedding!!)) }
        .sortedByDescending { it.score }
        .take(limit)
        .toList()
}
