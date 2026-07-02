package com.cliagent.rag

import com.cliagent.config.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * День 21: обход корпуса документов → `List<RagDocument>`. Лекция недели 5: «README / статьи /
 * код / pdf → текст». Мы ограничиваемся `.md` (документация) и `.kt` (исходники как «эквивалент
 * в коде»); pdf — за рамками (мультимодальный RAG / OCR по лекции).
 *
 * [documentId] стабилен между запусками (хэш относительного пути) → реиндексация идемпотентна:
 * тот же путь = тот же id = те же chunkId'ы. [title] = первый H1 MD-заголовок либо имя файла.
 *
 * I/O — `suspend` + `withContext(Dispatchers.IO)`; `CancellationException` не глотается (проект).
 *
 * @param corpusRoots относительные пути корней корпуса (разрешаются от CWD). Default —
 *   `[AppPaths]`-независимые директории проекта; обычно передаётся из [RagConfig.corpusRoots].
 */
class DocumentLoader(
    private val corpusRoots: List<String> = listOf("plan", "docs", "README.md"),
) {
    /** Читаемые расширения (lowercase, без точки). */
    private val extensions = setOf("md", "kt")

    /** Максимальный размер файла (байт) — ограничение от случайно-гигантских файлов. */
    private val maxFileBytes = 1_500_000L // ~1.5 MB

    /**
     * Загружает все документы из [corpusRoots]. Несуществующие корти/файлы skip'аются (warn в лог).
     * Возвращает документы отсортированные по [RagDocument.source] — детерминированный порядок
     * важен для стабильного сравнения стратегий (`/rag compare`).
     */
    suspend fun load(): List<RagDocument> = withContext(Dispatchers.IO) {
        val docs = mutableListOf<RagDocument>()
        for (root in corpusRoots) {
            val rootPath = Path.of(root)
            if (!Files.exists(rootPath)) continue
            collect(rootPath, docs)
        }
        docs.sortBy { it.source }
        docs
    }

    private fun collect(root: Path, out: MutableList<RagDocument>) {
        if (Files.isRegularFile(root)) {
            readDocument(root)?.let(out::add)
            return
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // skip директории сборки/кэша — мусор не индексируем
                val rel = root.relativize(file).toString().replace('\\', '/')
                if (rel.contains("/build/") || rel.contains("/.gradle/") ||
                    rel.contains("/target/") || rel.startsWith("build/") ||
                    rel.contains("/node_modules/")
                ) return FileVisitResult.CONTINUE
                readDocument(file)?.let(out::add)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Читает один файл → [RagDocument], или null если расширение не поддерживается/файл велик. */
    private fun readDocument(file: Path): RagDocument? {
        val ext = file.fileName.toString().substringAfterLast('.', "").lowercase()
        if (ext !in extensions) return null
        val size = runCatching { Files.size(file) }.getOrDefault(Long.MAX_VALUE)
        if (size > maxFileBytes) return null
        val content = runCatching { Files.readString(file, Charsets.UTF_8) }.getOrNull() ?: return null
        if (content.isBlank()) return null
        val source = file.toString().replace('\\', '/')
        val title = extractTitle(content, file)
        return RagDocument(
            id = stableId(source),
            source = source,
            title = title,
            content = content,
            fileType = ext,
            createdAt = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L),
        )
    }

    /** Title: первый H1 (`# …`) MD-файла, иначе имя файла без расширения. */
    private fun extractTitle(content: String, file: Path): String {
        if (file.fileName.toString().endsWith(".md", ignoreCase = true)) {
            content.lineSequence()
                .firstOrNull { it.startsWith("# ") && it.isNotBlank() }
                ?.removePrefix("# ")
                ?.trim()
                ?.take(120)
                ?.let { return it }
        }
        return file.fileName.toString()
    }

    /**
     * Стабильный id из относительного пути. FNV-1a (32-bit) — быстрый, детерминированный, без
     * внешних зависимостей. Один и тот же путь → один и тот же id → идемпотентная реиндексация.
     */
    private fun stableId(source: String): String {
        var hash = 0x811C9DC5L
        for (b in source.toByteArray(Charsets.UTF_8)) {
            hash = hash xor b.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return "doc-${hash.toString(16)}"
    }
}
