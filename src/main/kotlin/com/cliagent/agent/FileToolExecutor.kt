package com.cliagent.agent

import com.cliagent.llm.model.FunctionDef
import com.cliagent.llm.model.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * День 34 — in-process ToolExecutor для file-agent'а.
 *
 * В отличие от [com.cliagent.mcp.McpToolExecutor] (subprocess MCP-сервер), этот executor вызывает
 * file-тулы **напрямую** в том же процессе. Причина: `write_file` требует [confirmWrite] callback
 * к пользователю (Human-in-the-Loop, лекция нед.7), что невозможно через subprocess-границу MCP.
 *
 * Регистрирует 4 tool'а (зеркало mcp-server/tools/FileAgentTools.kt, но без MCP SDK):
 *  - read_file, find_in_files, list_project_files — read-only.
 *  - write_file — DANGEROUS, требует [confirmWrite]. null = read-only fail-safe.
 *
 * **Sandbox security:** все пути резолвятся в [root] через [resolveSafe] (path traversal guard).
 *
 * Tool-ошибки возвращаются строкой (как в McpToolExecutor), не exception — LLM видит ошибку и
 * самокорректируется. CancellationException пробрасывается (корутины, AGENTS.md).
 *
 * @param root sandbox-корень (default = CWD).
 * @param confirmWrite callback подтверждения write-операции: `(path, content) -> approved`.
 *        null = write блокирован (batch/CI read-only режим, fail-safe).
 */
class FileToolExecutor(
    private val root: File = File(System.getProperty("user.dir")),
    private val confirmWrite: (suspend (path: String, content: String) -> Boolean)? = null,
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun definitions(): List<ToolDefinition> = listOf(
        readDef(), findDef(), listDef(), writeDef(),
    )

    override suspend fun call(name: String, args: Map<String, Any?>): String = when (name) {
        "read_file" -> readFile(args)
        "find_in_files" -> findInFiles(args)
        "list_project_files" -> listProjectFiles(args)
        "write_file" -> writeFile(args)
        else -> "Unknown tool: $name"
    }

    override suspend fun close() { /* нет ресурсов — subprocess'ов нет */ }

    // ── handlers ──────────────────────────────────────────────────────────────

    private fun readFile(args: Map<String, Any?>): String {
        val path = args.strArg("path") ?: return errMissing("path")
        val file = resolveSafe(root, path) ?: return errPathEscape(path)
        if (!file.exists()) return "Error: файл не найден: $path"
        if (!file.isFile) return "Error: '$path' не является файлом."
        return try {
            val content = file.readText(Charsets.UTF_8)
            if (content.length > READ_MAX_CHARS) {
                content.take(READ_MAX_CHARS) + "\n... [truncated, ${content.length - READ_MAX_CHARS} chars omitted]"
            } else content
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Error: не удалось прочитать: ${e.message}"
        }
    }

    private fun findInFiles(args: Map<String, Any?>): String {
        val dirRel = args.strArg("dir")?.ifBlank { "." } ?: "."
        val query = args.strArg("query") ?: return errMissing("query")
        val ext = args.strArg("extension")?.lowercase()?.removePrefix(".")
        val dir = resolveSafe(root, dirRel) ?: return errPathEscape(dirRel)
        if (!dir.isDirectory) return "Error: '$dirRel' не является каталогом."

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
                // бинарный/нечитаемый — пропускаем
            }
            if (matches.size >= MAX_FIND_RESULTS) return@forEach
        }
        return if (matches.isEmpty()) "Совпадений не найдено."
        else matches.joinToString("\n") + if (matches.size >= MAX_FIND_RESULTS) "\n... [truncated]" else ""
    }

    private fun listProjectFiles(args: Map<String, Any?>): String {
        val dirRel = args.strArg("dir")?.ifBlank { "." } ?: "."
        val ext = args.strArg("extension")?.lowercase()?.removePrefix(".")
        val dir = resolveSafe(root, dirRel) ?: return errPathEscape(dirRel)
        if (!dir.isDirectory) return "Error: '$dirRel' не является каталогом."

        val files = mutableListOf<String>()
        dir.walkTopDown().onEnter { it.name !in SKIP_DIRS }.forEach { f ->
            if (!f.isFile) return@forEach
            if (ext != null && !f.name.endsWith(".$ext", ignoreCase = true)) return@forEach
            files.add(root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/'))
            if (files.size >= MAX_LIST_FILES) return@forEach
        }
        return if (files.isEmpty()) "Файлов не найдено." else files.sorted().joinToString("\n")
    }

    private suspend fun writeFile(args: Map<String, Any?>): String {
        val path = args.strArg("path") ?: return errMissing("path")
        val content = args.strArg("content") ?: return errMissing("content")
        val file = resolveSafe(root, path) ?: return errPathEscape(path)

        val confirm = confirmWrite ?: return "Error: write_file заблокирован (read-only режим — нет confirm-колбэка)."
        val approved = try {
            confirm(path, content)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return "Error: confirm-колбэк упал: ${e.message}"
        }
        if (!approved) return "Операция отменена пользователем (write в '$path')."

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            "✓ Записан: $path (${content.length} символов)"
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Error: не удалось записать: ${e.message}"
        }
    }

    // ── ToolDefinition schemas ────────────────────────────────────────────────

    private fun readDef() = ToolDefinition(
        function = FunctionDef(
            name = "read_file",
            description = "Прочитать содержимое текстового файла (read-only). Для анализа кода/документации.",
            parameters = buildJsonObject {
                putJsonObject("path") { put("type", "string"); put("description", "Путь к файлу") }
            }.toSchema(),
        )
    )

    private fun findDef() = ToolDefinition(
        function = FunctionDef(
            name = "find_in_files",
            description = "Поиск текста по файлам (grep, case-insensitive). Найти все места использования.",
            parameters = buildJsonObject {
                putJsonObject("dir") { put("type", "string"); put("description", "Каталог (default: корень)") }
                putJsonObject("query") { put("type", "string"); put("description", "Искомый текст") }
                putJsonObject("extension") { put("type", "string"); put("description", "Опц. фильтр по расширению") }
            }.toSchema(),
        )
    )

    private fun listDef() = ToolDefinition(
        function = FunctionDef(
            name = "list_project_files",
            description = "Список файлов проекта рекурсивно (read-only, с фильтром мусора).",
            parameters = buildJsonObject {
                putJsonObject("dir") { put("type", "string"); put("description", "Каталог (default: корень)") }
                putJsonObject("extension") { put("type", "string"); put("description", "Опц. фильтр по расширению") }
            }.toSchema(),
        )
    )

    private fun writeDef() = ToolDefinition(
        function = FunctionDef(
            name = "write_file",
            description = "⚠️ DANGEROUS: записать/перезаписать файл. Требует подтверждения пользователя. " +
                if (confirmWrite == null) "В read-only режиме ЗАПРЕЩЁН." else "",
            parameters = buildJsonObject {
                putJsonObject("path") { put("type", "string"); put("description", "Путь к файлу") }
                putJsonObject("content") { put("type", "string"); put("description", "Содержимое файла") }
            }.toSchema(),
        )
    )

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Map<String,Any?> → String arg (null-safe, как stringArg в MCP utils). */
    private fun Map<String, Any?>.strArg(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }?.trim()

    private fun JsonObject.toSchema(): kotlinx.serialization.json.JsonElement = this

    private fun errMissing(param: String) = "Error: параметр '$param' обязателен."
    private fun errPathEscape(path: String) =
        "Error: путь '$path' выходит за пределы sandbox (path traversal заблокирован)."

    companion object {
        private val SKIP_DIRS = setOf(
            "build", ".gradle", ".git", "node_modules", "target", ".idea", ".vscode", "out",
        )
        private val SKIP_FILES = setOf(".DS_Store", "Thumbs.db")
        private const val READ_MAX_CHARS = 30000
        private const val MAX_FIND_RESULTS = 50
        private const val MAX_FIND_FILE_BYTES = 1_500_000L
        private const val MAX_LIST_FILES = 500
    }
}

/**
 * Path-traversal-safe resolver (shared with mcp-server FileAgentTools). Возвращает canonical File
 * внутри [root], или null если путь пытается выйти за sandbox (`../`, абсолютные пути вне root).
 */
internal fun resolveSafe(root: File, relPath: String): File? {
    val resolved = if (File(relPath).isAbsolute) File(relPath) else File(root, relPath)
    val canonical = try { resolved.canonicalFile } catch (e: Exception) { return null }
    val rootCanonical = root.canonicalFile
    return if (canonical.path == rootCanonical.path ||
        canonical.path.startsWith(rootCanonical.path + File.separator)
    ) canonical else null
}
