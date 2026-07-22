package com.cliagent.review.gh

/**
 * Результат парсинга PR URL.
 *
 * @property owner владелец репозитория (github.com/`owner`/...)
 * @property repo название репозитория (github.com/owner/`repo`/...)
 * @property number номер PR (github.com/owner/repo/pull/`number`)
 */
data class PrCoordinates(
    val owner: String,
    val repo: String,
    val number: Int,
) {
    /** REST-путь для GitHub API (без ведущего слэша — `gh api` сам подставит хост). */
    fun apiPath(suffix: String = ""): String =
        "repos/$owner/$repo/pulls/$number$suffix"

    /** HTML URL PR (для вывода пользователю). */
    val htmlUrl: String get() = "https://github.com/$owner/$repo/pull/$number"
}

/**
 * Парсит GitHub PR URL вида `https://github.com/{owner}/{repo}/pull/{number}`.
 *
 * Принимает также варианты:
 *  - `.../pull/123/files`
 *  - `.../pulls/123` (typo)
 *  - с trailing `/` или query (`?diff=unified`)
 *
 * @return [PrCoordinates] или null, если строка не похожа на PR URL.
 */
object PrUrlParser {

    // [A-Za-z0-9._-] — character class, как NAME_REGEX в GitHubTools.kt (path-injection guard).
    private val PR_URL_REGEX = Regex(
        """https?://github\.com/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)/pulls?/(\d+)"""
    )

    fun parse(url: String): PrCoordinates? {
        val m = PR_URL_REGEX.find(url.trim()) ?: return null
        val owner = m.groupValues[1]
        val repo = m.groupValues[2]
        val number = m.groupValues[3].toIntOrNull() ?: return null
        return PrCoordinates(owner, repo, number)
    }
}
