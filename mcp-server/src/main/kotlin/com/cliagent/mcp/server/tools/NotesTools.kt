package com.cliagent.mcp.server.tools

import com.cliagent.mcp.server.notes.NotesStore
import com.cliagent.mcp.server.util.stringArg
import com.cliagent.mcp.server.util.toolError
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Day 19 — tools для process- и save-этапов пайплайна tech-дайджеста:
 *  - `format_report` — детерминированная обработка: title + массив текстовых блоков → markdown-отчёт
 *    с датой. **Не LLM-суммаризация** (по уточнению куратора: «не буквально LLM-суммаризация, а
 *    отчёт/итог»); MCP-сервер остаётся независимым от LLM. [buildReport] — чистая функция для тестов.
 *  - `save_to_file` — сохранение результата в `notes/{slug}.md` (atomic write, path-injection guard).
 *  - `list_notes` — показывает сохранённые заметки (демонстрирует персистентность Day 19).
 *
 * Tool-ошибки → [toolError] (isError=true, видны LLM), не exception (конвенция MCP, как Day 18).
 */
internal fun registerNotesTools(server: Server, store: NotesStore) {
    registerFormatReport(server)
    registerSaveToFile(server, store)
    registerListNotes(server, store)
}

private fun registerFormatReport(server: Server) {
    server.addTool(
        name = "format_report",
        description = "Собрать структурированный Markdown-отчёт из заголовка и набора текстовых " +
            "блоков (секций). Используй, чтобы оформить собранные данные (из search_wikipedia, " +
            "get_repo и др.) в единый документ перед сохранением. Каждый блок — строка текста " +
            "(например, выдержка из статьи или метаданные репозитория). Дата добавляется автоматически.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "Заголовок отчёта, например 'Tech Digest: Kotlin'")
                }
                putJsonObject("sections") {
                    put("type", "array")
                    put("description", "Текстовые блоки отчёта. Каждый элемент — строка с содержимым секции")
                    putJsonObject("items") { put("type", "string") }
                }
            },
            required = listOf("title", "sections"),
        ),
    ) { req -> handleFormatReport(req) }
}

private fun registerSaveToFile(server: Server, store: NotesStore) {
    server.addTool(
        name = "save_to_file",
        description = "Сохранить текстовый результат (например, отчёт из format_report) в файл в " +
            "каталоге заметок и вернуть путь. Имя файла санитизируется (только латиница/цифры/" +
            "дефис); перезаписывает существующее имя. Завершающий шаг пайплайна «найди → обработай " +
            "→ сохрани».",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("filename") {
                    put("type", "string")
                    put("description", "Имя файла без расширения, например 'kotlin-digest' (станет kotlin-digest.md)")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Текст для сохранения (например, вывод format_report)")
                }
            },
            required = listOf("filename", "content"),
        ),
    ) { req -> handleSaveToFile(req, store) }
}

private fun registerListNotes(server: Server, store: NotesStore) {
    server.addTool(
        name = "list_notes",
        description = "Показать список сохранённых заметок/отчётов (имя, размер, дата изменения). " +
            "Помогает проверить результат предыдущих пайплайнов и найти сохранённый файл.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) { _ -> handleListNotes(store) }
}

// ── handlers ──────────────────────────────────────────────────────────────────

private fun handleFormatReport(req: CallToolRequest): CallToolResult {
    val title = stringArg(req.arguments, "title")?.trim()
    if (title.isNullOrBlank()) return toolError("Параметр 'title' обязателен.")
    val sections = stringArrayArg(req.arguments, "sections")
    if (sections.isEmpty()) return toolError("Параметр 'sections' обязателен и не может быть пустым.")
    val markdown = buildReport(title, sections, LocalDate.now())
    return CallToolResult(content = listOf(TextContent(markdown)), isError = false)
}

private fun handleSaveToFile(req: CallToolRequest, store: NotesStore): CallToolResult {
    val filename = stringArg(req.arguments, "filename")?.trim()
    if (filename.isNullOrBlank()) return toolError("Параметр 'filename' обязателен.")
    val content = stringArg(req.arguments, "content")
    if (content == null) return toolError("Параметр 'content' обязателен.")
    val path = store.save(filename, content)
    val text = "✓ Сохранено: ${path.fileName} (${content.length} символов) в ${path.parent}"
    return CallToolResult(content = listOf(TextContent(text)), isError = false)
}

private fun handleListNotes(store: NotesStore): CallToolResult {
    val notes = store.list()
    if (notes.isEmpty()) {
        return CallToolResult(
            content = listOf(TextContent("Заметок пока нет. Сохраните отчёт через save_to_file.")),
            isError = false,
        )
    }
    val text = buildString {
        appendLine("Сохранённые заметки (${notes.size}):")
        notes.forEach {
            appendLine("  • ${it.name} — ${it.sizeBytes} байт, изменён ${fmtDate(it.modifiedMillis)}")
        }
    }.trimEnd()
    return CallToolResult(content = listOf(TextContent(text)), isError = false)
}

// ── чистая функция сборки отчёта (тестируется без IO — аналог aggregate() из Day 18) ─

/**
 * Детерминированная сборка Markdown-отчёта из заголовка и текстовых секций. Без IO, без времени
 * (дата — параметр для детерминированных тестов). Вынесена из handler'а для unit-тестирования.
 *
 * @param title    заголовок отчёта
 * @param sections непустой список текстовых блоков (по одному абзацу/секции)
 * @param date     дата отчёта (вставляется под заголовком)
 */
internal fun buildReport(title: String, sections: List<String>, date: LocalDate): String {
    require(sections.isNotEmpty()) { "buildReport requires non-empty sections" }
    return buildString {
        appendLine("# $title")
        appendLine()
        appendLine("_${date.format(DATE_FMT)}_")
        appendLine()
        sections.forEachIndexed { i, block ->
            appendLine("## Раздел ${i + 1}")
            appendLine()
            appendLine(block.trim())
            appendLine()
        }
    }.trimEnd()
}

// ── helpers ───────────────────────────────────────────────────────────────────

/** Извлекает массив строк из JsonObject arguments; пустой список если отсутствует/не массив. */
private fun stringArrayArg(args: JsonObject?, key: String): List<String> {
    val arr = args?.get(key) as? JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
}

private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun fmtDate(epochMillis: Long): String =
    LocalDate.ofEpochDay(epochMillis / 86_400_000L).format(DATE_FMT)
