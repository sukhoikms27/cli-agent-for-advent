package com.cliagent.cli

import com.cliagent.llm.model.ChatMessage
import com.cliagent.rag.ScoredChunk
import com.cliagent.rag.RagChunk

/**
 * День 31 — чистые функции сборки промпта для [AskCommand]. Вынесены из command-класса для
 * unit-тестирования (как `buildReport` в mcp-server/tools/NotesTools.kt): без IO, без LLM,
 * детерминированные.
 *
 *  - [buildPrompt] — собирает цепочку сообщений: system (devAssistant + git + RAG) + user question.
 *  - [formatRagBlock] — форматирует top-K чанков в блок `[Retrieved context]`.
 */
internal fun buildAskPrompt(
    question: String,
    gitContext: String,
    ragBlock: String?,
    systemPromptContent: String,
): List<ChatMessage> {
    val systemContent = buildString {
        appendLine(systemPromptContent)
        appendLine()
        appendLine("[Project git context]")
        appendLine(gitContext)
        if (ragBlock != null) {
            appendLine()
            appendLine(ragBlock)
        }
    }
    return listOf(
        ChatMessage(role = "system", content = systemContent),
        ChatMessage(role = "user", content = question),
    )
}

/**
 * Форматирует top-K чанков в блок `[Retrieved context]` для инъекции в system-prompt.
 * Каждый чанк: `[N] (source › section)\n<text>`. Пустой список → пустую строку (caller решает,
 * инжектить ли).
 */
internal fun formatRagBlock(hits: List<ScoredChunk>): String {
    if (hits.isEmpty()) return ""
    val parts = hits.mapIndexed { i, hit ->
        val c = hit.chunk
        val src = if (c.section.isNotBlank()) "${c.source} › ${c.section}" else c.source
        "[${i + 1}] ($src)\n${c.text.trim()}"
    }
    return "[Retrieved context]\n${parts.joinToString("\n\n")}"
}

/** Фабрика тестового чанка (для unit-тестов [formatRagBlock]). */
internal fun testChunk(source: String, section: String, text: String, score: Float = 1.0f): ScoredChunk =
    ScoredChunk(
        chunk = RagChunk(
            chunkId = "$source-0",
            documentId = source,
            source = source,
            title = source,
            section = section,
            text = text,
            index = 0,
        ),
        score = score,
    )
