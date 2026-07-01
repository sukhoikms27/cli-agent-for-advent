package com.cliagent.rag

import com.cliagent.rag.chunk.ChunkingStrategy
import com.cliagent.rag.embedding.EmbeddingClient

/**
 * День 21: сравнение 2 стратегий chunking (требование задания). Не просто подсчёт, а содержательное
 * сопоставление — статистика + **пробный retrieval** по одним и тем же зонд-запросам на каждый
 * индекс, чтобы увидеть, какая стратегия даёт более релевантные чанки.
 *
 * Лекция недели 5: «оптимальные параметры (размер чанка, процент overlap) — экспериментально».
 * Этот класс — тот самый эксперимент.
 *
 * Результат ([ComparisonReport]) — data, без I/O; печатью (mordant-таблица) заняты CLI-команды.
 */
class ChunkingComparison(
    private val fixedChunker: ChunkingStrategy,
    private val structuralChunker: ChunkingStrategy,
    private val embedder: EmbeddingClient,
) {

    /**
     * Строит оба индекса по одному корпусу [documents] и собирает сравнительную статистику +
     * пробный retrieval по [probeQueries]. Возвращает null если индексация провалилась (ошибка
     * эмбеддинга — оба индекса требуют работающую Ollama).
     */
    suspend fun compare(
        documents: List<RagDocument>,
        probeQueries: List<String> = DEFAULT_PROBES,
        onProgress: suspend (String, Int, Int) -> Unit = { _, _, _ -> },
    ): ComparisonReport? {
        // 1. Fixed-индекс
        val fixedIndex = buildIndex(fixedChunker, documents, "fixed", onProgress) ?: return null
        // 2. Structural-индекс
        val structIndex = buildIndex(structuralChunker, documents, "structural", onProgress) ?: return null

        // 3. Статистика
        val fixedStats = statsOf(fixedIndex)
        val structStats = statsOf(structIndex)

        // 4. Пробный retrieval: каждый зонд → top-3 на каждом индексе
        val probeResults = probeQueries.map { q -> ProbeResult(query = q, fixed = probe(fixedIndex, q), structural = probe(structIndex, q)) }

        return ComparisonReport(
            documents = documents.size,
            fixed = fixedStats,
            structural = structStats,
            probes = probeResults,
        )
    }

    private suspend fun buildIndex(
        chunker: ChunkingStrategy,
        documents: List<RagDocument>,
        label: String,
        onProgress: suspend (String, Int, Int) -> Unit,
    ): RagIndex? {
        // Временный store в памяти не нужен — RagIndexer требует JsonRagStore; используем tmp-free путь:
        // повторяем логику индексации без персистентности (сравнение не должно портить основной индекс).
        val rawChunks = chunker.run { documents.flatMap { chunk(it) } }
        val texts = rawChunks.map { it.text }
        val total = texts.size
        if (total == 0) return RagIndex(
            strategy = chunker.name,
            embeddingModel = embedder.modelName,
            dimension = embedder.dimension,
            documents = documents,
            chunks = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        val allVectors = mutableListOf<List<Float>>()
        for ((i, batch) in texts.chunked(32).withIndex()) {
            val r = embedder.embed(batch)
            if (r is com.cliagent.llm.LlmResult.Error) return null
            @Suppress("UNCHECKED_CAST")
            allVectors.addAll((r as com.cliagent.llm.LlmResult.Success).data)
            onProgress(label, (i * 32) + batch.size, total)
        }
        val chunks = rawChunks.zip(allVectors) { c, v -> c.copy(embedding = v) }
        return RagIndex(
            strategy = chunker.name,
            embeddingModel = embedder.modelName,
            dimension = embedder.dimension,
            documents = documents,
            chunks = chunks,
            createdAt = System.currentTimeMillis(),
        )
    }

    /** Статистика по индексу: число чанков, размеры (min/avg/max токенов), размерность. */
    private fun statsOf(index: RagIndex): StrategyStats {
        val tokens = index.chunks.map { it.tokenCount }
        return StrategyStats(
            name = index.strategy,
            chunkCount = index.chunks.size,
            embeddedCount = index.embeddedChunks.size,
            minTokens = tokens.minOrNull() ?: 0,
            avgTokens = if (tokens.isEmpty()) 0 else tokens.average().toInt(),
            maxTokens = tokens.maxOrNull() ?: 0,
            dimension = index.dimension,
        )
    }

    /** Пробный retrieval: embed запроса → top-3 чанка. */
    private suspend fun probe(index: RagIndex, query: String): List<ScoredChunk> {
        val r = embedder.embed(listOf(query))
        if (r is com.cliagent.llm.LlmResult.Error) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val qVec = (r as com.cliagent.llm.LlmResult.Success).data.firstOrNull() ?: return emptyList()
        return topK(qVec, index.chunks, k = 3)
    }

    companion object {
        /** Зонд-запросы по умолчанию — про архитектуру проекта (т.к. корпус = документация проекта). */
        val DEFAULT_PROBES = listOf(
            "Какие стратегии управления контекстом есть в агенте?",
            "Как работает MCP-оркестрация нескольких серверов?",
            "Где хранятся чаты и как устроена персистентность?",
            "Что такое инварианты проекта и как они проверяются?",
            "Какие модели и API endpoint использует LLM-клиент?",
        )
    }
}

/** Отчёт сравнения — чистые данные; печатью занят CLI. */
data class ComparisonReport(
    val documents: Int,
    val fixed: StrategyStats,
    val structural: StrategyStats,
    val probes: List<ProbeResult>,
)

data class StrategyStats(
    val name: String,
    val chunkCount: Int,
    val embeddedCount: Int,
    val minTokens: Int,
    val avgTokens: Int,
    val maxTokens: Int,
    val dimension: Int,
)

data class ProbeResult(
    val query: String,
    val fixed: List<ScoredChunk>,
    val structural: List<ScoredChunk>,
)
