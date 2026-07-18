package com.cliagent.mcp.server.tools

import com.cliagent.mcp.server.util.stringArg
import com.cliagent.mcp.server.util.toolError
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Path

/**
 * День 34 — File agent: read/write/search tools для работы с файлами проекта.
 *
 * Регистрирует 4 инструмента (по лекции нед.7 — System Tools: файлы):
 *  - `read_file(path)`           — чтение файла (с лимитом размера).
 *  - `find_in_files(dir, query)` — поиск текста по файлам (grep-аналог), возвращает matches с путями/строками.
 *  - `list_project_files(dir, ext?)` — список файлов рекурсивно (фильтр мусора build/.git/...).
 *  - `write_file(path, content)` — запись файла (DANGEROUS — требует подтверждения, см. [confirmWrite]).
 *
 * **Security — Dangerous Operations (лекция нед.7):**
 *  - Все пути резолвятся в [resolveSafe] с проверкой против path traversal (нельзя выйти за [root]).
 *  - [root] задаётся при регистрации (sandbox); по умолчанию — CWD.
 *  - `write_file` — DANGEROUS: требует подтверждения через [confirmWrite] callback. В CI-режиме
 *    (confirmWrite = null) write блокируется — только read-only операции (fail-safe).
 *  - Файлы вне sandbox (../escape) → toolError, не exception.
 *
 * **Tool Registry pattern:** все tools регистрируются здесь, agent вызывает их через ToolExecutor.
 * Идёт Fan-out при работе с несколькими файлами (параллельные read/find).
 *
 * @param root sandbox-корень для file-операций (default = CWD). Все пути — внутри него.
 * @param confirmWrite callback подтверждения опасной операции (write_file). null = write запрещён.
 *        В REPL прокидывается колбэк к AppTerminal.yesNoPrompt; в batch/CI = null (read-only).
 */
internal fun registerFileAgentTools(
    server: Server,
    root: File = File(System.getProperty("user.dir")),
    confirmWrite: (suspend (path: String, content: String) -> Boolean)? = null,
) {
    registerReadFile(server, root)
    registerFindInFiles(server, root)
    registerListProjectFiles(server, root)
    registerWriteFile(server, root, confirmWrite)
}

// ── read_file ──────────────────────────────────────────────────────────────────

private fun registerReadFile(server: Server, root: File) {
    server.addTool(
        name = "read_file",
        description = "Прочитать содержимое текстового файла (read-only). Используй для анализа " +
            "кода, конфигов, документации. Результат обрезается до $READ_MAX_CHARS символов " +
            "(защита контекстного окна). Для больших файлов — читай по частям или используй find_in_files.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Путь к файлу (относительно проекта или абсолютный в sandbox)")
                }
            },
            required = listOf("path"),
        ),
    ) { req -> handleReadFile(req, root) }
}

private fun handleReadFile(req: CallToolRequest, root: File): CallToolResult {
    val relPath = stringArg(req.arguments, "path")?.trim()
    if (relPath.isNullOrBlank()) return toolError("Параметр 'path' обязателен.")
    val file = resolveSafe(root, relPath) ?: return toolErrorPathEscape(relPath)
    if (!file.exists()) return toolError("Файл не найден: $relPath")
    if (!file.isFile) return toolError("'$relPath' не является файлом.")
    val content = try {
        file.readText(Charsets.UTF_8)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        return toolError("Не удалось прочитать файл: ${e.message}")
    }
    val result = if (content.length > READ_MAX_CHARS) {
        content.take(READ_MAX_CHARS) + "\n... [truncated, ${content.length - READ_MAX_CHARS} chars omitted]"
    } else {
        content
    }
    return CallToolResult(content = listOf(TextContent(result)), isError = false)
}

// ── find_in_files ──────────────────────────────────────────────────────────────

private fun registerFindInFiles(server: Server, root: File) {
    server.addTool(
        name = "find_in_files",
        description = "Поиск текста по файлам проекта (grep-аналог, read-only). Возвращает " +
            "совпадения с путями файлов и номерами строк. Регистронезависимый. Используй, чтобы " +
            "найти все места, где используется компонент/API/функция. Ограничение: $MAX_FIND_RESULTS совпадений.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("dir") {
                    put("type", "string")
                    put("description", "Каталог для поиска (относительно проекта). Default: корень проекта.")
                }
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Искомый текст (substring match, case-insensitive)")
                }
                putJsonObject("extension") {
                    put("type", "string")
                    put("description", "Опц.: фильтр по расширению без точки, например 'kt', 'md'")
                }
            },
            required = listOf("query"),
        ),
    ) { req -> handleFindInFiles(req, root) }
}

private fun handleFindInFiles(req: CallToolRequest, root: File): CallToolResult {
    val dirRel = stringArg(req.arguments, "dir")?.trim()?.ifBlank { "." } ?: "."
    val query = stringArg(req.arguments, "query")?.trim()
    if (query.isNullOrBlank()) return toolError("Параметр 'query' обязателен.")
    val ext = stringArg(req.arguments, "extension")?.trim()?.lowercase()?.removePrefix(".")
    val dir = resolveSafe(root, dirRel) ?: return toolErrorPathEscape(dirRel)
    if (!dir.isDirectory) return toolError("'$dirRel' не является каталогом.")

    val matches = mutableListOf<String>()
    val needle = query.lowercase()
    dir.walkTopDown().onEnter { it.name !in SKIP_DIRS && it.name !in SKIP_FILES }.forEach { f ->
        if (!f.isFile) return@forEach
        if (ext != null && !f.name.endsWith(".$ext", ignoreCase = true)) return@forEach
        if (f.length() > MAX_FIND_FILE_BYTES) return@forEach
        try {
            f.useLines(Charsets.UTF_8) { lines ->
                lines.forEachIndexed lineLoop@{ i, line ->
                    if (line.lowercase().contains(needle)) {
                        val rel = root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/')
                        matches.add("$rel:${i + 1}: ${line.trim().take(200)}")
                        if (matches.size >= MAX_FIND_RESULTS) return@lineLoop
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Бинарный/нечитаемый файл — пропускаем молча.
        }
        if (matches.size >= MAX_FIND_RESULTS) return@forEach
    }

    if (matches.isEmpty()) {
        return CallToolResult(content = listOf(TextContent("Совпадений не найдено.")), isError = false)
    }
    val truncated = if (matches.size >= MAX_FIND_RESULTS) "\n... [truncated at $MAX_FIND_RESULTS matches]" else ""
    return CallToolResult(content = listOf(TextContent(matches.joinToString("\n") + truncated)), isError = false)
}

// ── list_project_files ─────────────────────────────────────────────────────────

private fun registerListProjectFiles(server: Server, root: File) {
    server.addTool(
        name = "list_project_files",
        description = "Список файлов проекта рекурсивно (read-only). Фильтрует мусор: build/, .git/, " +
            ".gradle/, node_modules/. Опц. фильтр по расширению. Используй для понимания структуры " +
            "проекта перед поиском/анализом.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("dir") {
                    put("type", "string")
                    put("description", "Каталог (default: корень проекта)")
                }
                putJsonObject("extension") {
                    put("type", "string")
                    put("description", "Опц.: фильтр по расширению, например 'kt'")
                }
            },
            required = emptyList(),
        ),
    ) { req -> handleListProjectFiles(req, root) }
}

private fun handleListProjectFiles(req: CallToolRequest, root: File): CallToolResult {
    val dirRel = stringArg(req.arguments, "dir")?.trim()?.ifBlank { "." } ?: "."
    val ext = stringArg(req.arguments, "extension")?.trim()?.lowercase()?.removePrefix(".")
    val dir = resolveSafe(root, dirRel) ?: return toolErrorPathEscape(dirRel)
    if (!dir.isDirectory) return toolError("'$dirRel' не является каталогом.")

    val files = mutableListOf<String>()
    dir.walkTopDown().onEnter { it.name !in SKIP_DIRS }.forEach { f ->
        if (!f.isFile) return@forEach
        if (ext != null && !f.name.endsWith(".$ext", ignoreCase = true)) return@forEach
        val rel = root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/')
        files.add(rel)
        if (files.size >= MAX_LIST_FILES) return@forEach
    }
    if (files.isEmpty()) {
        val suffix = if (ext != null) " с расширением '.$ext'" else ""
        return CallToolResult(content = listOf(TextContent("Файлов$suffix не найдено.")), isError = false)
    }
    return CallToolResult(content = listOf(TextContent(files.sorted().joinToString("\n"))), isError = false)
}

// ── write_file (DANGEROUS) ─────────────────────────────────────────────────────

private fun registerWriteFile(
    server: Server,
    root: File,
    confirmWrite: (suspend (path: String, content: String) -> Boolean)?,
) {
    server.addTool(
        name = "write_file",
        description = "⚠️ DANGEROUS: записать/перезаписать файл. Требует подтверждения пользователя. " +
            "Используй для создания/изменения файлов (README, changelog, исходники). Путь должен быть " +
            "внутри sandbox проекта (path traversal блокируется). " +
            if (confirmWrite == null) "В CI/batch-режиме WRITE ЗАПРЕЩЁН (read-only fail-safe)." else "",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Путь к файлу (относительно проекта или абсолютный в sandbox)")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Полное содержимое файла (перезаписывает существующий)")
                }
            },
            required = listOf("path", "content"),
        ),
    ) { req -> handleWriteFile(req, root, confirmWrite) }
}

private suspend fun handleWriteFile(
    req: CallToolRequest,
    root: File,
    confirmWrite: (suspend (path: String, content: String) -> Boolean)?,
): CallToolResult {
    val relPath = stringArg(req.arguments, "path")?.trim()
    if (relPath.isNullOrBlank()) return toolError("Параметр 'path' обязателен.")
    val content = stringArg(req.arguments, "content") ?: return toolError("Параметр 'content' обязателен.")
    val file = resolveSafe(root, relPath) ?: return toolErrorPathEscape(relPath)

    // Fail-safe: в batch/CI-режиме (нет confirmWrite) — write блокируется.
    if (confirmWrite == null) {
        return toolError(
            "write_file заблокирован: не задан confirm-колбэк (batch/CI read-only режим). " +
                "Запустите в интерактивном режиме для записи файлов."
        )
    }

    // Human-in-the-Loop (лекция нед.7): подтверждение опасной операции.
    val approved = confirmWrite(relPath, content)
    if (!approved) {
        return toolError("Пользователь отклонил запись в '$relPath'. Операция отменена.")
    }

    return try {
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        CallToolResult(
            content = listOf(TextContent("✓ Записан: $relPath (${content.length} символов)")),
            isError = false,
        )
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        toolError("Не удалось записать файл: ${e.message}")
    }
}

// ── security helpers ───────────────────────────────────────────────────────────

/**
 * Резолвит [relPath] в абсолютный [File] внутри sandbox [root]. Возвращает null если путь выходит
 * за пределы root (path traversal: `../` escape, симлинки). UTF-8 path support.
 *
 * Примеры:
 *  - "src/Main.kt" → `<root>/src/Main.kt`
 *  - "../etc/passwd" → null (выход за sandbox)
 *  - "/abs/path" → null если вне root
 */
internal fun resolveSafe(root: File, relPath: String): File? {
    val resolved = if (File(relPath).isAbsolute) File(relPath) else File(root, relPath)
    val canonical = try {
        resolved.canonicalFile
    } catch (e: Exception) {
        return null
    }
    val rootCanonical = root.canonicalFile
    // canonical-путь должен начинаться с root-пути (path traversal guard).
    return if (canonical.path == rootCanonical.path ||
        canonical.path.startsWith(rootCanonical.path + File.separator)
    ) canonical else null
}

private fun toolErrorPathEscape(path: String): CallToolResult =
    toolError("Путь '$path' выходит за пределы sandbox проекта (path traversal заблокирован).")

/** Каталоги, пропускаемые при walkTopDown (build-артефакты, VCS, зависимости). */
private val SKIP_DIRS = setOf(
    "build", ".gradle", ".git", "node_modules", "target", ".idea", ".vscode", "out", ".hg", ".svn",
)

/** Файлы, пропускаемые при поиске (бинарные/крупные). */
private val SKIP_FILES = setOf(".DS_Store", "Thumbs.db")

/** Лимит символов для read_file (защита контекстного окна). */
private const val READ_MAX_CHARS = 30000

/** Лимит числа совпадений в find_in_files. */
private const val MAX_FIND_RESULTS = 50

/** Лимит размера файла для поиска (пропуск бинарников). */
private const val MAX_FIND_FILE_BYTES = 1_500_000L

/** Лимит числа файлов в list_project_files. */
private const val MAX_LIST_FILES = 500
