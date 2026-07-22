package com.cliagent.review.gh

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One inline comment to attach to a PR review.
 *
 * @property path file path (relative to repo root).
 * @property line absolute line number in the new (head) version of the file.
 * @property side always "RIGHT" for our use case (we comment on what the student added).
 * @property body markdown text of the comment.
 */
@Serializable
data class ReviewCommentDto(
    val path: String,
    val line: Int,
    val side: String = "RIGHT",
    val body: String,
)

/**
 * Body for `POST /repos/{o}/{r}/pulls/{n}/reviews`.
 *
 * - [event] = `null` (omitted) → PENDING review (visible only to author/reviewer).
 *   GitHub **не принимает** строку `"PENDING"` как event — единственный способ создать pending —
 *   опустить `event` полностью. Подтверждено эмпирически (422 на `"event":"PENDING"`).
 * - [event] = `"REQUEST_CHANGES"` / `"APPROVE"` → published immediately (dangerous, opt-in).
 * - [event] = `"COMMENT"` → published as comment-only review (no approve/request).
 *
 * @property body overall summary (shown as the review's main comment).
 * @property event review event type; null → PENDING review.
 * @property comments inline line comments (atomic — created in the same API call).
 */
@Serializable
data class ReviewBodyDto(
    val body: String,
    val event: String? = null,
    val comments: List<ReviewCommentDto> = emptyList(),
)

/**
 * Создаёт PR review через `gh api --input -` (stdin).
 *
 * Идентичность — от пользователя, выполнившего `gh auth login` (см. [GhCli]). Никаких PAT / bot.
 *
 * JSON передаётся через stdin (`--input -`) — это самый чистый способ из Kotlin для массивов
 * и не-ASCII символов (кириллица в комментариях). Использование `-f key=value` для `comments`
 * не работает корректно (`gh` трактует значение как строку, а endpoint ждёт массив).
 */
object PendingReviewPoster {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * Создаёт review.
     *
     * @param coords PR coordinates (owner/repo/number).
     * @param body review body (event + summary + comments).
     * @return URL созданного review (поле `html_url` из ответа GitHub) или null, если не распарсилось.
     * @throws GhException при ошибке gh / неавторизованном пользователе / невалидном line.
     */
    suspend fun post(coords: PrCoordinates, body: ReviewBodyDto): String? {
        val payload = json.encodeToString(ReviewBodyDto.serializer(), body)
        val args = listOf(
            "api",
            "--method", "POST",
            "-H", "Accept: application/vnd.github+json",
            coords.apiPath("/reviews"),
            "--input", "-",
        )
        val r = GhCli.run(args = args, stdin = payload, mergeStderr = false)
        if (!r.success) {
            throw GhException(
                "gh api reviews failed (exit ${r.exitCode}): ${r.stderr ?: r.stdout}",
            )
        }
        // Ответ — JSON созданного review; достаём html_url.
        return Regex(""""html_url"\s*:\s*"([^"]+)"""").find(r.stdout)?.groupValues?.get(1)
    }
}
