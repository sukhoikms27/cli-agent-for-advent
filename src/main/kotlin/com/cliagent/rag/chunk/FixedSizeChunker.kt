package com.cliagent.rag.chunk

import com.cliagent.rag.RagChunk
import com.cliagent.rag.RagDocument
import com.cliagent.llm.token.estimateTokens

/**
 * День 21: chunking по фиксированному размеру токенов с overlap. Лекция недели 5:
 * «1-500, 451-950, 901-1400» — чанки частично перекрываются, граница «на замке».
 *
 * Минус (по лекции): можно разрезать посередине предложения. Overlap это частично смягчает.
 * Оценка токенов — `~4 chars/token` (как [com.cliagent.llm.token.TokenCounter]).
 *
 * @param chunkSizeTokens целевой размер чанка (default 500, рекомендация лекции 500–1000)
 * @param overlapTokens   перекрытие между соседними чанками (default 100 = 20% от size)
 */
class FixedSizeChunker(
    private val chunkSizeTokens: Int = 500,
    private val overlapTokens: Int = 100,
) : ChunkingStrategy {

    override val name: String = "fixed"

    init {
        require(chunkSizeTokens > 0) { "chunkSizeTokens must be > 0, got $chunkSizeTokens" }
        require(overlapTokens in 0 until chunkSizeTokens) {
            "overlapTokens must be in 0..<chunkSizeTokens, got overlap=$overlapTokens size=$chunkSizeTokens"
        }
    }

    override fun chunk(doc: RagDocument): List<RagChunk> {
        if (doc.content.isBlank()) return emptyList()
        val text = doc.content
        val chunkChars = chunkSizeTokens * 4        // ~4 char/token → целевой размер в символах
        val overlapChars = overlapTokens * 4
        val step = (chunkChars - overlapChars).coerceAtLeast(1)  // шаг окна

        val chunks = mutableListOf<RagChunk>()
        var index = 0
        var start = 0
        while (start < text.length) {
            val end = (start + chunkChars).coerceAtMost(text.length)
            val piece = text.substring(start, end)
            if (piece.isNotBlank()) {
                chunks.add(
                    RagChunk(
                        chunkId = "${doc.id}-$index",
                        documentId = doc.id,
                        source = doc.source,
                        title = doc.title,
                        section = "fixed-$index",
                        text = piece.trim(),
                        index = index,
                        tokenCount = estimateTokens(piece),
                        embedding = null,
                        strategy = name,
                    )
                )
                index++
            }
            if (end >= text.length) break
            start += step
        }
        return chunks
    }
}
