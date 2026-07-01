package com.cliagent.rag

import com.cliagent.config.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * День 21: персистентность RAG-индекса в JSON. Один файл на стратегию chunking
 * (см. [AppPaths.ragIndexFixed]/[ragIndexStructural]). Зеркалирует паттерн
 * [com.cliagent.memory.JsonLongTermStore]: graceful на отсутствии/битом файле, atomicWrite, UTF-8 явно.
 *
 * @param file путь индекса (default [AppPaths.ragIndexFile]; тесты подставляют temp-путь).
 */
class JsonRagStore(
    private val file: Path = AppPaths.ragIndexFile,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    private suspend fun ensureDir() = withContext(Dispatchers.IO) {
        Files.createDirectories(file.parent)
    }

    /** Загружает индекс; пустой [RagIndex] если файл отсутствует/повреждён (graceful, не падает). */
    suspend fun load(): RagIndex = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext RagIndex(strategy = "empty")
        runCatching {
            json.decodeFromString<RagIndex>(Files.readString(file, Charsets.UTF_8))
        }.getOrNull() ?: RagIndex(strategy = "corrupt")
    }

    /** Атомарно сохраняет [index]. */
    suspend fun save(index: RagIndex) {
        ensureDir()
        withContext(Dispatchers.IO) {
            atomicWrite(file, json.encodeToString(RagIndex.serializer(), index))
        }
    }

    /** Стирает индекс (сохраняет пустой). */
    suspend fun clear(strategy: String = "empty") {
        ensureDir()
        withContext(Dispatchers.IO) {
            atomicWrite(file, json.encodeToString(RagIndex.serializer(), RagIndex(strategy = strategy)))
        }
    }

    /** Путь файла (для `/rag stats` — показать пользователю). */
    fun path(): Path = file

    private fun atomicWrite(target: Path, content: String) {
        val tmp = target.resolveSibling(".${target.fileName}.${java.util.UUID.randomUUID()}.tmp")
        // UTF-8 явно — фикс краша на эмодзи/кириллице на Windows (как Day 19, JsonLongTermStore).
        Files.writeString(tmp, content, Charsets.UTF_8)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }
}
