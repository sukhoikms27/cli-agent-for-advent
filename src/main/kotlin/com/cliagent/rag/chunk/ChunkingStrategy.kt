package com.cliagent.rag.chunk

import com.cliagent.rag.RagChunk
import com.cliagent.rag.RagDocument

/**
 * День 21: стратегия нарезки документов на чанки. Лекция недели 5: чанкинг с overlap решает
 * потерю контекста на границах фрагментов.
 *
 * Задание day-21 требует минимум 2 стратегии: фиксированный размер и структурную (по заголовкам).
 * Каждая реализация проставляет метаданные задания (source/title/section/chunk_id) через [RagChunk].
 */
interface ChunkingStrategy {
    /** Имя стратегии ("fixed" | "structural") — persist'ится в [RagChunk.strategy] и [com.cliagent.rag.RagIndex.strategy]. */
    val name: String

    /**
     * Нарезает [doc] на чанки. Чанки нумеруются 0-based в [RagChunk.index]; [RagChunk.chunkId]
     * = "{doc.id}-{index}". Возвращается пустой список только для пустого документа.
     */
    fun chunk(doc: RagDocument): List<RagChunk>
}

/** Тип стратегии chunking — для CLI-флагов и factory. */
enum class ChunkingStrategyType {
    FIXED,
    STRUCTURAL;

    companion object {
        /** Парсит строку ("fixed"/"structural", case-insensitive); default — [STRUCTURAL]. */
        fun fromString(s: String?): ChunkingStrategyType = when (s?.lowercase()?.trim()) {
            "fixed", "fixed_size", "fixedsize" -> FIXED
            "structural", "structure", "section", "header", "heading" -> STRUCTURAL
            null -> STRUCTURAL
            else -> STRUCTURAL
        }
    }
}
