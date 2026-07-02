package com.cliagent.rag.chunk

import com.cliagent.rag.RagChunk
import com.cliagent.rag.RagDocument
import com.cliagent.llm.token.estimateTokens

/**
 * День 21: структурный chunking по заголовкам Markdown (`#`–`######`). Лекция недели 5:
 * «режем по смысловым блокам (абзацы, разделы)» — точнее фиксированного, лучше сохраняет контекст.
 *
 * Алгоритм:
 * 1. Документ режется на секции по строкам `^#{1,6}\s`. Каждая секция = заголовок + тело до
 *    следующего заголовка. Текст до первого заголовка → секция "(intro)".
 * 2. Если секция укладывается в [maxChunkTokens] → один чанк с `section` = текст заголовка.
 * 3. Если больше — под-нарезка по абзацам (двойной `\n\n`), затем по предложениям, с overlap.
 *
 * Для немаркдаун (`.kt`) документ трактуется как одна секция с под-нарезкой (как fixed, но без
 *Overlap-семантики заголовков — просто bounded куски исходника).
 *
 * @param maxChunkTokens   потолок размера чанка (default 500)
 * @param overlapTokens    перекрытие при под-нарезке больших секций (default 100)
 */
class StructuralChunker(
    private val maxChunkTokens: Int = 500,
    private val overlapTokens: Int = 100,
) : ChunkingStrategy {

    override val name: String = "structural"

    init {
        require(maxChunkTokens > 0) { "maxChunkTokens must be > 0, got $maxChunkTokens" }
        require(overlapTokens in 0 until maxChunkTokens) {
            "overlapTokens must be in 0..<maxChunkTokens, got overlap=$overlapTokens max=$maxChunkTokens"
        }
    }

    override fun chunk(doc: RagDocument): List<RagChunk> {
        if (doc.content.isBlank()) return emptyList()
        val isMarkdown = doc.fileType.equals("md", ignoreCase = true)
        val sections = if (isMarkdown) splitByHeadings(doc.content) else listOf(Section("(file)", doc.content))

        val chunks = mutableListOf<RagChunk>()
        var index = 0
        for (section in sections) {
            if (section.body.isBlank()) continue
            val sectionLabel = section.heading.ifBlank { "(section)" }
            if (estimateTokens(section.body) <= maxChunkTokens) {
                // Секция помещается целиком — один чанк
                chunks.add(buildChunk(doc, index++, sectionLabel, section.body))
            } else {
                // Секция велика — под-нарезка по абзацам с overlap
                for (piece in subdivide(section.body)) {
                    chunks.add(buildChunk(doc, index++, sectionLabel, piece))
                }
            }
        }
        return chunks
    }

    private fun buildChunk(doc: RagDocument, index: Int, section: String, text: String): RagChunk =
        RagChunk(
            chunkId = "${doc.id}-$index",
            documentId = doc.id,
            source = doc.source,
            title = doc.title,
            section = section,
            text = text.trim(),
            index = index,
            tokenCount = estimateTokens(text),
            embedding = null,
            strategy = name,
        )

    /** Секция: заголовок (без `#`) + тело до следующего заголовка. */
    private data class Section(val heading: String, val body: String)

    /**
     * Разбивает MD-текст на секции по строкам-заголовкам (`^#{1,6}\s`). Заголовок включается в
     * `body` секции (кормится в эмбеддер — даёт контекст). Текст до первого заголовка → "(intro)".
     */
    private fun splitByHeadings(text: String): List<Section> {
        val headingRegex = Regex("""^(#{1,6})\s+(.+)$""", RegexOption.MULTILINE)
        val lines = text.lines()
        val sections = mutableListOf<Section>()
        val current = StringBuilder()
        var currentHeading = "(intro)"

        fun flush() {
            if (current.isNotEmpty()) {
                sections.add(Section(currentHeading, current.toString().trim()))
                current.clear()
            }
        }

        for (line in lines) {
            val match = headingRegex.find(line)
            if (match != null) {
                flush()
                currentHeading = match.groupValues[2].trim()
                current.appendLine(line)   // заголовок входит в body секции
            } else {
                current.appendLine(line)
            }
        }
        flush()
        return sections
    }

    /**
     * Под-нарезка большой секции по абзацам (`\n\n`), затем по предложениям, с overlap-окном по
     * токенам. Гарантирует каждый кусок ≤ [maxChunkTokens].
     */
    private fun subdivide(body: String): List<String> {
        val maxChars = maxChunkTokens * 4
        val overlapChars = overlapTokens * 4
        val step = (maxChars - overlapChars).coerceAtLeast(1)
        // Сначала пытаемся резать по абзацам; если абзац сам по себе > maxChars — режем окном.
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < body.length) {
            val end = (start + maxChars).coerceAtMost(body.length)
            pieces.add(body.substring(start, end))
            if (end >= body.length) break
            start += step
        }
        return pieces
    }
}
