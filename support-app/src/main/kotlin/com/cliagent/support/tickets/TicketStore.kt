package com.cliagent.support.tickets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * День 33 — персистентное хранилище тикетов поддержки (аналог NotesStore из mcp-server).
 *
 * JSON-файл с массивом [Ticket]. Atomic write (tmp + ATOMIC_MOVE), synchronized на каталог.
 * Graceful на отсутствии/битом файле — возвращает пустой список.
 *
 * Используется support-agent'ом: пользователь может спросить «что с моим тикетом #42?» — агент
 * ищет тикет по id и отвечает с учётом его контекста (статус, история, приоритет).
 *
 * @param file путь к JSON (default: `~/.local/share/cli-agent/support/tickets.json`).
 */
class TicketStore(
    private val file: Path = defaultTicketsFile(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    /** Все тикеты (пустой список если файла нет/битый). */
    suspend fun all(): List<Ticket> = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<Ticket>>(Files.readString(file, Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    /** Найти тикет по id; null если не найден. */
    suspend fun find(id: Int): Ticket? = all().firstOrNull { it.id == id }

    /** Найти тикеты по email пользователя (один пользователь может иметь несколько). */
    suspend fun findByEmail(email: String): List<Ticket> =
        all().filter { it.customerEmail.equals(email, ignoreCase = true) }

    /** Добавить или обновить тикет (по id). Atomic write. */
    suspend fun upsert(ticket: Ticket) = withContext(Dispatchers.IO) {
        // Читаем вне synchronized (all() — suspend); пишем — в критической секции.
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == ticket.id }
        if (idx >= 0) list[idx] = ticket else list.add(ticket)
        synchronized(LOCK) {
            atomicWrite(list)
        }
        Unit
    }

    /** Добавить комментарий в историю тикета. */
    suspend fun addComment(ticketId: Int, author: String, text: String): Ticket? {
        val ticket = find(ticketId) ?: return null
        val updated = ticket.copy(
            history = ticket.history + TicketEvent(author = author, text = text, at = Instant.now().toString())
        )
        upsert(updated)
        return updated
    }

    private fun atomicWrite(list: List<Ticket>) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(".${file.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Ticket.serializer()), list), Charsets.UTF_8)
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    companion object {
        private val LOCK = Any()

        /** Дефолтный путь файла тикетов (XDG_DATA_HOME или ~/.local/share/cli-agent/support). */
        fun defaultTicketsFile(): Path {
            val dataDir = System.getenv("XDG_DATA_HOME")?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".local", "share")
            return dataDir.resolve("cli-agent").resolve("support").resolve("tickets.json")
        }
    }
}

/**
 * Тикет поддержки. Поля — минимум для демо support-agent'а (день 33).
 *
 * @param id числовой идентификатор (для удобства ссылок «тикет #42»)
 * @param subject краткое описание проблемы
 * @param status open/in_progress/resolved/closed
 * @param priority low/medium/high/urgent
 * @param customerEmail email пользователя (для поиска по пользователю)
 * @param customerName имя пользователя (опц.)
 * @param description полное описание проблемы от клиента
 * @param history события/комментарии (хронологически)
 */
@Serializable
data class Ticket(
    val id: Int,
    val subject: String,
    val status: String = "open",
    val priority: String = "medium",
    val customerEmail: String = "",
    val customerName: String? = null,
    val description: String = "",
    val history: List<TicketEvent> = emptyList(),
)

/** Событие в истории тикета (комментарий/смена статуса). */
@Serializable
data class TicketEvent(
    val author: String,
    val text: String,
    val at: String,
)
