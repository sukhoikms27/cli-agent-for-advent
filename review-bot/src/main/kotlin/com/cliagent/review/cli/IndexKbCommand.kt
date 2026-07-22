package com.cliagent.review.cli

import com.cliagent.rag.DocumentLoader
import com.cliagent.rag.JsonRagStore
import com.cliagent.rag.RagIndexer
import com.cliagent.rag.chunk.StructuralChunker
import com.cliagent.review.ReviewBotFactory
import com.cliagent.review.ReviewBotPaths
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `review-bot index-kb [--reset]`.
 *
 * Распаковывает embedded чеклист (`resources/checklist-sprint-7.md`) во временную директорию
 * и индексирует его в RAG-индекс [ReviewBotPaths.ragIndexFile] через [RagIndexer] + [StructuralChunker]
 * + [OllamaEmbeddingClient] (nomic-embed-text).
 *
 * Запускать перед `review` (один раз, после старта Ollama с nomic-embed-text).
 */
class IndexKbCommand : CliktCommand(
    name = "index-kb",
    help = "Индексация embedded чеклиста в RAG-индекс (требует Ollama + nomic-embed-text).",
) {
    private val reset: Boolean by option("--reset", help = "Очистить индекс перед индексацией")
        .flag()

    override fun run() = runBlocking {
        val factory = ReviewBotFactory.fromEnv()
        factory.use {
            val indexFile = ReviewBotPaths.ragIndexFile
            val resourcesDir = ReviewBotPaths.resourcesDir
            Files.createDirectories(resourcesDir)
            Files.createDirectories(indexFile.parent)

            // 1. Распаковка embedded чеклиста в resourcesDir.
            val cl = javaClass.getResourceAsStream("/checklist-sprint-7.md")
                ?: error("Embedded checklist not found: /checklist-sprint-7.md")
            val target = resourcesDir.resolve("checklist-sprint-7.md")
            Files.copy(cl, target, StandardCopyOption.REPLACE_EXISTING)
            cl.close()
            echo("▸ Распакован чеклист: $target")

            // 2. Загрузка как RagDocument (через DocumentLoader; .md поддерживается).
            val documents = DocumentLoader(corpusRoots = listOf(resourcesDir.toString())).load()
            if (documents.isEmpty()) {
                echo("❌ Нет .md документов в $resourcesDir", err = true)
                return@runBlocking
            }
            echo("  → документов: ${documents.size}")

            // 3. Очистка индекса (опционально).
            val store = JsonRagStore(file = indexFile)
            if (reset) {
                echo("▸ Очищаю индекс…")
                store.clear(strategy = "empty")
            }

            // 4. Индексация.
            val chunker = StructuralChunker()
            val indexer = RagIndexer(chunker = chunker, embedder = it.embedder, store = store)
            echo("▸ Индексирую (embedder: ${it.embedder.modelName})…")
            val start = System.currentTimeMillis()
            val index = indexer.index(documents) { done, total ->
                echo("\r  → эмбеддинги: $done / $total", trailingNewline = false)
            }
            echo()
            val elapsed = (System.currentTimeMillis() - start) / 1000.0
            if (index == null) {
                echo(
                    "❌ Ошибка эмбеддинга — индекс не сохранён. Проверьте Ollama (${it.embedder.modelName}).",
                    err = true,
                )
                return@runBlocking
            }
            echo(
                "✓ Индекс создан за ${"%.1f".format(elapsed)}с: " +
                    "${index.chunks.size} чанков, ${index.documents.size} документов → $indexFile"
            )
        }
        Unit
    }
}
