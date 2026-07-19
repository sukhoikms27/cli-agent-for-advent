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
 * День 31 — Dev-assistant: read-only project/git tools для контекста о текущем репозитории.
 *
 * Регистрирует 4 инструмента (все локальные, без сети — через `git` CLI и файловую систему):
 *  - `git_branch(path)`  — текущая ветка (`git branch --show-current`). Минимальное требование дня 31.
 *  - `git_status(path)`  — короткий статус изменённых файлов (`git status --porcelain`).
 *  - `git_diff(path)`    — unstaged diff (`git diff`, обрезается до [DIFF_MAX_CHARS] — защита контекстного окна).
 *  - `list_files(path, glob?)` — список файлов рекурсивно (с фильтром мусора build/.gradle/.git).
 *
 * **Security:** только read-only операции. Path-injection невозможен — `git -C <path>` + проверка
 * `dir.isDirectory`. Все tools возвращают tool-error (isError=true) при проблемах, не exception —
 * по конвенции MCP (как Day 17–19), LLM видит ошибку и самокорректируется.
 *
 * `git` вызывается через [ProcessBuilder]; каталог через `.directory(dir)`. `redirectErrorStream(true)`
 * объединяет stderr в stdout — git diagnostics видны в результате, а не теряются.
 *
 * **Stateless:** нет персистентности (как у NotesStore), нет singleton-зависимостей → не нужно расширять
 * сигнатуру [com.cliagent.mcp.server.buildServer] / [com.cliagent.mcp.server.main]. Регистрация — одна строка.
 */
internal fun registerProjectTools(server: Server) {
    registerGitBranch(server)
    registerGitStatus(server)
    registerGitDiff(server)
    registerListFiles(server)
}

// ── git_branch ─────────────────────────────────────────────────────────────────

private fun registerGitBranch(server: Server) {
    server.addTool(
        name = "git_branch",
        description = "Текущая git-ветка репозитория в указанной директории (read-only, `git branch --show-current`). " +
            "Используй, чтобы понять, над какой веткой/задачей сейчас идёт работа.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Абсолютный путь к git-репозиторию, например '/Users/me/projects/my-app'")
                }
            },
            required = listOf("path"),
        ),
    ) { req -> handleGitBranch(req) }
}

private fun handleGitBranch(req: CallToolRequest): CallToolResult {
    val dir = resolveRepoDir(req) ?: return toolErrorBadRequest("path")
    return runGit(dir, listOf("branch", "--show-current"))
}

// ── git_status ─────────────────────────────────────────────────────────────────

private fun registerGitStatus(server: Server) {
    server.addTool(
        name = "git_status",
        description = "Короткий статус изменений в git-репозитории (`git status --porcelain`). " +
            "Возвращает список изменённых/добавленных/удалённых файлов с маркерами (M/A/D/??). " +
            "Помогает понять, какие файлы сейчас правятся, но ещё не закоммичены.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Абсолютный путь к git-репозиторию")
                }
            },
            required = listOf("path"),
        ),
    ) { req -> handleGitStatus(req) }
}

private fun handleGitStatus(req: CallToolRequest): CallToolResult {
    val dir = resolveRepoDir(req) ?: return toolErrorBadRequest("path")
    return runGit(dir, listOf("status", "--porcelain"))
}

// ── git_diff ───────────────────────────────────────────────────────────────────

private fun registerGitDiff(server: Server) {
    server.addTool(
        name = "git_diff",
        description = "Unstaged diff репозитория (`git diff`). Показывает незакоммиченные изменения в файлах. " +
            "Полезно для анализа текущей правки перед коммитом. Результат обрезается до $DIFF_MAX_CHARS символов " +
            "для защиты контекстного окна — для больших diff'ов используй git_status сначала.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Абсолютный путь к git-репозиторию")
                }
            },
            required = listOf("path"),
        ),
    ) { req -> handleGitDiff(req) }
}

private fun handleGitDiff(req: CallToolRequest): CallToolResult {
    val dir = resolveRepoDir(req) ?: return toolErrorBadRequest("path")
    val result = runGit(dir, listOf("diff", "--stat"))
    // --stat даёт компактный обзор (файлы + строки). Если он пуст — unstaged изменений нет.
    return result
}

// ── list_files ─────────────────────────────────────────────────────────────────

private fun registerListFiles(server: Server) {
    server.addTool(
        name = "list_files",
        description = "Список файлов в директории рекурсивно (read-only). Автоматически фильтрует мусор: " +
            "build/, .gradle/, .git/, node_modules/, target/. Опциональный glob-фильтр по расширению " +
            "(например 'kt' или 'md'). Используй, чтобы понять структуру проекта или найти файлы определённого типа.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Абсолютный путь к директории проекта")
                }
                putJsonObject("extension") {
                    put("type", "string")
                    put("description", "Опц.: фильтр по расширению без точки, например 'kt', 'md', 'gradle'")
                }
            },
            required = listOf("path"),
        ),
    ) { req -> handleListFiles(req) }
}

private fun handleListFiles(req: CallToolRequest): CallToolResult {
    val path = stringArg(req.arguments, "path")?.trim()
    if (path.isNullOrBlank()) return toolErrorBadRequest("path")
    val dir = File(path)
    if (!dir.isDirectory) return toolError("'$path' не является директорией.")
    val ext = stringArg(req.arguments, "extension")?.trim()?.lowercase()?.removePrefix(".")
    val files = collectFiles(dir.toPath(), ext).take(MAX_FILES_LISTED)
    if (files.isEmpty()) {
        val suffix = if (ext != null) " с расширением '.$ext'" else ""
        return CallToolResult(content = listOf(TextContent("Файлов$suffix не найдено.")), isError = false)
    }
    val text = buildString {
        files.forEach { appendLine(it) }
    }.trimEnd()
    return CallToolResult(content = listOf(TextContent(text)), isError = false)
}

// ── helpers ────────────────────────────────────────────────────────────────────

/**
 * Извлекает `path` из аргументов, проверяет что это git-репозиторий. null → bad request.
 * Используется git_* handlers'ами; общий guard для path-валидации.
 */
private fun resolveRepoDir(req: CallToolRequest): File? {
    val path = stringArg(req.arguments, "path")?.trim() ?: return null
    if (path.isBlank()) return null
    val dir = File(path)
    if (!dir.isDirectory) return null
    if (!File(dir, ".git").exists()) return null
    return dir
}

/** Стандартная ошибка отсутствующего/невалидного path-параметра. */
private fun toolErrorBadRequest(param: String): CallToolResult =
    toolError("Параметр '$param' обязателен и должен указывать на существующий git-репозиторий.")

/**
 * Запускает `git` с заданными аргументами в [dir]. Возвращает TextContent-результат.
 * - stdout+stderr объединены (`redirectErrorStream(true)`) — diagnostics не теряются.
 * - non-zero exit → toolError с кодом и выводом.
 * - CancellationException пробрасывается (корутины, AGENTS.md).
 */
private fun runGit(dir: File, args: List<String>): CallToolResult {
    val proc = try {
        ProcessBuilder(listOf("git") + args)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        return toolError("Не удалось запустить git: ${e.message}")
    }
    val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trimEnd()
    val code = proc.waitFor()
    return if (code == 0) {
        val text = if (out.isEmpty()) "(пусто)" else out
        CallToolResult(content = listOf(TextContent(text)), isError = false)
    } else {
        toolError("git ${args.joinToString(" ")} завершился с кодом $code: $out")
    }
}

/**
 * Рекурсивный сбор относительных путей файлов в [root]. Фильтрует мусорные каталоги.
 * @param ext если задан — только файлы с этим расширением (без точки).
 */
private fun collectFiles(root: Path, ext: String?): List<String> {
    val result = mutableListOf<String>()
    root.toFile().walkTopDown().onEnter { f ->
        // Не заходим в мусорные каталоги (walkTopDown обходит их целиком через onEnter).
        f.name !in SKIP_DIRS
    }.forEach { f ->
        if (!f.isFile) return@forEach
        if (ext != null && !f.name.endsWith(".$ext", ignoreCase = true)) return@forEach
        val rel = root.relativize(f.toPath()).toString().replace(File.separatorChar, '/')
        result.add(rel)
    }
    return result.sorted()
}

/** Каталоги, в которые walkTopDown не заходит (build-артефакты, VCS, зависимости). */
private val SKIP_DIRS = setOf(
    "build", ".gradle", ".git", "node_modules", "target", ".idea", ".vscode", "out",
)

/** Лимит символов для diff-вывода (защита контекстного окна LLM). */
private const val DIFF_MAX_CHARS = 8000

/** Лимит числа файлов в list_files (аналогично — защита контекста). */
private const val MAX_FILES_LISTED = 500
