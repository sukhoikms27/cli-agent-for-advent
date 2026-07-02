package com.cliagent.rag

import com.cliagent.rag.chunk.ChunkingStrategy
import com.cliagent.rag.embedding.EmbeddingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * День 21: оркестратор пайплайна индексации. Лекция недели 5:
 * chunking → embeddings → сохранение индекса.
 *
 * Поток: [documents] → [chunker] → [embedder] (batched) → [JsonRagStore].save.
 * Чанки без эмбеддинга не отбрасываются (persist'ятся с `embedding=null`) — но [RagIndex.embeddedChunks]
 * фильтрует их для retrieval. Это позволяет пересохранить индекс без эмбеддингов (офлайн-дебаг).
 *
 * Прогресс-колбэк [onProgress] — для `/rag index` (mordant-спиннер/лог): `(processed, total)`.
 *
 * @param chunker  стратегия нарезки (fixed/structural)
 * @param embedder эмбеддинг-клиент (Ollama); null = построить индекс без векторов
 * @param store    персистентность (JSON)
 */
class RagIndexer(
    private val chunker: ChunkingStrategy,
    private val embedder: EmbeddingClient?,
    private val store: JsonRagStore,
) {

    /**
     * Индексирует [documents]: нарезает чанки, генерирует эмбеддинги, сохраняет индекс.
     *
     * @return построенный [RagIndex] (уже сохранённый в store), либо null при ошибке эмбеддинга
     *   (индекс без векторов НЕ сохраняется — retry позже; пользователь видит сообщение embedder'а).
     * @param onProgress вызывается после каждого batch'а эмбеддингов: `(embeddedChunks, totalChunks)`
     */
    suspend fun index(
        documents: List<RagDocument>,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): RagIndex? {
        // 1. Chunking — CPU-работа, но малая; остаёмся в Dispatchers.Default для консистентности
        val rawChunks = withContext(Dispatchers.Default) {
            documents.flatMap { chunker.chunk(it) }
        }

        // 2. Embeddings — IO (HTTP к Ollama). embedder может быть null (офлайн-индекс).
        val chunks: List<RagChunk> = if (embedder != null) {
            val texts = rawChunks.map { it.text }
            val total = texts.size
            if (total == 0) {
                return RagIndex(
                    strategy = chunker.name,
                    embeddingModel = embedder.modelName,
                    dimension = embedder.dimension,
                    documents = documents,
                    chunks = emptyList(),
                    createdAt = System.currentTimeMillis(),
                ).also { store.save(it) }
            }
            // Batched embed с прогрессом: эмулируем по chunked-batch'ам embedder'а
            val allVectors = mutableListOf<List<Float>>()
            val batchSize = 32
            for ((i, batch) in texts.chunked(batchSize).withIndex()) {
                val start = i * batchSize
                val batchResult = embedder.embed(batch)
                if (batchResult is com.cliagent.llm.LlmResult.Error) {
                    // Возвращаем null — индекс не сохраняется; сообщение embedder'а показывается вызывающим.
                    return null
                }
                @Suppress("UNCHECKED_CAST")
                allVectors.addAll((batchResult as com.cliagent.llm.LlmResult.Success).data)
                onProgress(start + batch.size, total)
            }
            rawChunks.zip(allVectors) { chunk, vec -> chunk.copy(embedding = vec, tokenCount = chunk.tokenCount) }
        } else {
            rawChunks
        }

        // 3. Сохранение
        val index = RagIndex(
            strategy = chunker.name,
            embeddingModel = embedder?.modelName ?: "",
            dimension = embedder?.dimension ?: 0,
            documents = documents,
            chunks = chunks,
            createdAt = System.currentTimeMillis(),
        )
        store.save(index)
        return index
    }
}
