package com.cliagent.memory

import com.cliagent.config.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Глобальное хранилище долговременной памяти (день 11).
 * Отдельный класс — чтобы не смешивать chat-scoped (JsonChatStore) и global read-modify-write.
 * Один файл [AppPaths.longTermFile], атомарная запись (temp + rename).
 */
class JsonLongTermStore(
    private val file: Path = AppPaths.longTermFile
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

    suspend fun load(): LongTermMemory = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext LongTermMemory()
        runCatching {
            json.decodeFromString<LongTermMemory>(Files.readString(file, Charsets.UTF_8))
        }.getOrNull() ?: LongTermMemory()
    }

    suspend fun save(memory: LongTermMemory) {
        ensureDir()
        withContext(Dispatchers.IO) {
            atomicWrite(file, json.encodeToString(LongTermMemory.serializer(), memory))
        }
    }

    suspend fun clear() {
        ensureDir()
        withContext(Dispatchers.IO) {
            atomicWrite(file, json.encodeToString(LongTermMemory.serializer(), LongTermMemory()))
        }
    }

    private fun atomicWrite(target: Path, content: String) {
        val tmp = target.resolveSibling(".${target.fileName}.${java.util.UUID.randomUUID()}.tmp")
        // Фикс краша (Day 19): UTF-8 явно — иначе на Windows Cp1251 валит на эмодзи/кириллице.
        Files.writeString(tmp, content, Charsets.UTF_8)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }
}
