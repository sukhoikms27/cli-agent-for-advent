package com.cliagent.agent

import com.cliagent.llm.model.FunctionDef
import com.cliagent.llm.model.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * День 34 — in-process ToolExecutor для file-agent'а.
 *
 * В отличие от [com.cliagent.mcp.McpToolExecutor] (subprocess MCP-сервер), этот executor вызывает
 * file-тулы **напрямую** в том же процессе. Причина: `write_file` требует подтверждения
 * (Human-in-the-Loop, лекция нед.7) через [DangerousOpGate] — что невозможно через subprocess-границу MCP.
 *
 * Регистрирует 4 tool'а (зеркало mcp-server/tools/FileAgentTools.kt, но без MCP SDK):
 *  - read_file, find_in_files, list_project_files — read-only.
 *  - write_file — DANGEROUS, требует подтверждения через [gate]. [RejectAllGate] = read-only fail-safe.
 *
 * **Sandbox security:** все пути резолвятся в [root] через [resolveSafe] (path traversal guard).
 *
 * Tool-ошибки возвращаются строкой (как в McpToolExecutor), не exception — LLM видит ошибку и
 * самокорректируется. CancellationException пробрасывается (корутины, AGENTS.md).
 *
 * @param root sandbox-корень (default = CWD).
 * @param gate gate подтверждения опасных операций (write_file). Default [RejectAllGate] = read-only.
 *        В REPL передаётся [TtyGate] с y/N prompt; в stage/FSM — [PlanApprovedGate].
 */
class FileToolExecutor(
    private val root: File = File(System.getProperty("user.dir")),
    private val gate: DangerousOpGate = RejectAllGate(),
) : ToolExecutor {

    /**
     * Legacy-конструктор: backward-compat для существующих callers (FileAgentCommand,
     * FileToolExecutorTest) с `confirmWrite: (suspend (path, content) -> Boolean)?`.
     *
     * `confirmWrite == null` → [RejectAllGate] (read-only). Иначе адаптируется в [TtyGate]:
     * каждый write_file проходит через confirm-функцию, как прежде.
     */
    constructor(
        root: File = File(System.getProperty("user.dir")),
        confirmWrite: (suspend (path: String, content: String) -> Boolean)?,
    ) : this(
        root = root,
        gate = confirmWrite?.let { callback ->
            TtyGate { _, args ->
                val path = args["path"] as? String ?: ""
                val content = args["content"] as? String ?: ""
                callback(path, content)
            }
        } ?: RejectAllGate(),
    )

    override suspend fun definitions(): List<ToolDefinition> = listOf(
        readDef(), findDef(), listDef(), writeDef(), deleteDef(),
    )

    override suspend fun call(name: String, args: Map<String, Any?>): String = when (name) {
        "read_file" -> readFile(args)
        "find_in_files" -> findInFiles(args)
        "list_project_files" -> listProjectFiles(args)
        "write_file" -> writeFile(args)
        "delete_file" -> deleteFile(args)
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

        // Gate решает, одобрить ли write (TtyGate → y/N prompt, PlanApprovedGate → по плану,
        // RejectAllGate → fail-safe read-only, AutoApproveGate → CI). Документация в [DangerousOpGate].
        val approved = try {
            gate.approve("write_file", mapOf("path" to path, "content" to content))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return "Error: gate-проверка упала: ${e.message}"
        }
        if (!approved) {
            // Различаем read-only режим (RejectAllGate) от отказа пользователя (TtyGate) для диагностики.
            val reason = if (gate is RejectAllGate) "read-only режим (gate=RejectAllGate)" else "gate отклонил"
            return "Операция отменена ($reason, write в '$path' заблокирован)."
        }

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            "✓ Записан: $path (${content.length} символов)"
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Error: не удалось записать: ${e.message}"
        }
    }

    private suspend fun deleteFile(args: Map<String, Any?>): String {
        val path = args.strArg("path") ?: return errMissing("path")
        val file = resolveSafe(root, path) ?: return errPathEscape(path)
        if (!file.exists()) return "Error: файл не найден: $path"

        // delete_file — опасная операция (необратимая), проходит через тот же gate.
        val approved = try {
            gate.approve("delete_file", mapOf("path" to path))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return "Error: gate-проверка упала: ${e.message}"
        }
        if (!approved) {
            val reason = if (gate is RejectAllGate) "read-only режим (gate=RejectAllGate)" else "gate отклонил"
            return "Операция отменена ($reason, delete '$path' заблокирован)."
        }

        return try {
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
            "✓ Удалён: $path"
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Error: не удалось удалить: ${e.message}"
        }
    }

    // ── ToolDefinition schemas ────────────────────────────────────────────────

    private fun readDef() = ToolDefinition(
        function = FunctionDef(
            name = "read_file",
            description = "Прочитать содержимое текстового файла (read-only). Для анализа кода/документации.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к файлу") }
                }
                putJsonArray("required") { add("path") }
            },
        )
    )

    private fun findDef() = ToolDefinition(
        function = FunctionDef(
            name = "find_in_files",
            description = "Поиск текста по файлам (grep, case-insensitive). Найти все места использования.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("dir") { put("type", "string"); put("description", "Каталог (default: корень)") }
                    putJsonObject("query") { put("type", "string"); put("description", "Искомый текст") }
                    putJsonObject("extension") { put("type", "string"); put("description", "Опц. фильтр по расширению") }
                }
                putJsonArray("required") { add("query") }
            },
        )
    )

    private fun listDef() = ToolDefinition(
        function = FunctionDef(
            name = "list_project_files",
            description = "Список файлов проекта рекурсивно (read-only, с фильтром мусора).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("dir") { put("type", "string"); put("description", "Каталог (default: корень)") }
                    putJsonObject("extension") { put("type", "string"); put("description", "Опц. фильтр по расширению") }
                }
                // required пуст — все параметры опциональны
            },
        )
    )

    private fun writeDef() = ToolDefinition(
        function = FunctionDef(
            name = "write_file",
            description = "⚠️ DANGEROUS: записать/перезаписать файл. Требует подтверждения через gate. " +
                if (gate is RejectAllGate) "В read-only режиме (RejectAllGate) ЗАПРЕЩЁН." else "",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к файлу") }
                    putJsonObject("content") { put("type", "string"); put("description", "Содержимое файла") }
                }
                putJsonArray("required") { add("path"); add("content") }
            },
        )
    )

    private fun deleteDef() = ToolDefinition(
        function = FunctionDef(
            name = "delete_file",
            description = "⚠️ DANGEROUS: удалить файл или каталог (необратимо). " +
                "Требует подтверждения через gate. " +
                if (gate is RejectAllGate) "В read-only режиме (RejectAllGate) ЗАПРЕЩЁН." else "",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к файлу или каталогу") }
                }
                putJsonArray("required") { add("path") }
            },
        )
    )

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Map<String,Any?> → String arg (null-safe, как stringArg в MCP utils). */
    private fun Map<String, Any?>.strArg(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }?.trim()

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
