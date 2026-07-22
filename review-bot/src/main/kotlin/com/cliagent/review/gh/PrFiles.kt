package com.cliagent.review.gh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Загружает файлы PR через `gh api .../pulls/{n}/files` и индексирует их патчи через [DiffLineIndexer].
 *
 * Возвращает:
 *  - [rawDiff] — полный unified-diff текст для LLM (конкатенация всех `patch` полей).
 *  - [lines] — индекс добавленных строк для анкоринга line-comments.
 *  - [fileNames] — список имён файлов (для быстрого reference).
 *
 * Лимит контекста: [rawDiff] обрезается до [MAX_DIFF_CHARS] (как в `ReviewPrCommand.DIFF_MAX_CHARS`),
 * чтобы не переполнять контекстное окно LLM.
 */
object PrFiles {

    /** Лимит символов diff для защиты контекстного окна (как `ReviewPrCommand.DIFF_MAX_CHARS`). */
    const val MAX_DIFF_CHARS: Int = 12_000

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * Загружает файлы PR и индексирует diff.
     */
    suspend fun load(coords: PrCoordinates): PrDiffData? {
        val r = GhCli.run(
            args = listOf("api", coords.apiPath("/files")),
            mergeStderr = false,
        )
        if (!r.success) {
            throw GhException("gh api .../pulls/{n}/files failed (exit ${r.exitCode}): ${r.stderr ?: r.stdout}")
        }
        return parse(r.stdout)
    }

    /**
     * Парсит JSON-ответ `.../pulls/{n}/files` через kotlinx.serialization (надёжнее regex на
     * реальных GitHub-ответах с URL-encoded путями и escape-последовательностями в патчах).
     *
     * Достаёт `(filename, patch)` пары; патч может отсутствовать (binary, >300 файлов, и т.п.).
     */
    internal fun parse(jsonResponse: String): PrDiffData? {
        val element: JsonElement = try {
            json.parseToJsonElement(jsonResponse)
        } catch (e: Throwable) {
            return null
        }
        val arr: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> JsonArray(listOf(element))  // один объект — оборачиваем
            else -> return null
        }
        val files = mutableListOf<Pair<String, String?>>()
        arr.forEach { item ->
            val obj = item.jsonObject
            val filename = obj["filename"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val patch = obj["patch"]?.jsonPrimitive?.contentOrNull
            files.add(filename to patch)
        }
        if (files.isEmpty()) return null

        val lines = DiffLineIndexer.indexFiles(files)
        val rawDiff = buildString {
            files.forEach { (name, patch) ->
                appendLine("--- a/$name")
                appendLine("+++ b/$name")
                if (patch != null) appendLine(patch) else appendLine("(binary or no patch)")
            }
        }
        val truncated = rawDiff.length > MAX_DIFF_CHARS
        val finalDiff = if (truncated) {
            rawDiff.take(MAX_DIFF_CHARS) + "\n... [diff truncated, ${rawDiff.length - MAX_DIFF_CHARS} chars omitted]"
        } else rawDiff
        return PrDiffData(
            rawDiff = finalDiff,
            truncated = truncated,
            lines = lines,
            fileNames = files.map { it.first },
        )
    }
}

/**
 * @property rawDiff unified-diff текст для LLM (обрезан до [PrFiles.MAX_DIFF_CHARS]).
 * @property truncated был ли rawDiff обрезан.
 * @property lines индекс добавленных строк (для анкоринга line-comments).
 * @property fileNames список имён файлов PR (без патчей).
 */
data class PrDiffData(
    val rawDiff: String,
    val truncated: Boolean,
    val lines: List<DiffLine>,
    val fileNames: List<String>,
)
