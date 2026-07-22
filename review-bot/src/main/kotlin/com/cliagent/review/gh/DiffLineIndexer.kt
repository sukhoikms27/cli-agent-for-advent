package com.cliagent.review.gh

/**
 * One line of a diff hunk that can receive a review comment.
 *
 * @property path file path in repo (e.g. "src/main/kotlin/Main.kt").
 * @property line absolute line number in the new (head) version of the file — valid for
 *               GitHub review comments with `side=RIGHT`.
 * @property content the line text (without `+` prefix).
 */
data class DiffLine(
    val path: String,
    val line: Int,
    val content: String,
)

/**
 * Parses unified-diff `patch` strings (as returned by `gh api .../pulls/{n}/files` in the `patch`
 * field of each file) into a map of `path → list of added lines`.
 *
 * Added lines (prefix `+`) get their absolute line number in the **new** version of the file
 * (valid for GitHub review comments with `side=RIGHT`). This is what we need for posting line
 * comments to a PR — comments on unchanged lines that aren't part of any hunk are rejected by
 * GitHub with 422.
 *
 * Hunk header format: `@@ -a,b +c,d @@` where `c` is the starting line number in the new file.
 * We walk the patch counting `+`/`-`/` ` prefixes to track the current new-file line counter.
 *
 * For new files (status `added`), the same logic applies — the patch starts with `@@ -0,0 +1,N @@`.
 */
object DiffLineIndexer {

    private val HUNK_HEADER = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /**
     * Index one file's patch.
     *
     * @param path file path (the `filename` field from the GitHub API response).
     * @param patch unified diff text (the `patch` field); may be null for binary/large files.
     * @return list of [DiffLine] for added lines, or empty list if patch is null/empty.
     */
    fun indexFile(path: String, patch: String?): List<DiffLine> {
        if (patch.isNullOrBlank()) return emptyList()
        val out = mutableListOf<DiffLine>()
        var newLine = 0
        patch.lineSequence().forEach { raw ->
            // Hunk header — reset the new-file line counter.
            HUNK_HEADER.find(raw)?.let { m ->
                newLine = m.groupValues[1].toInt()
                return@forEach
            }
            when {
                raw.startsWith("+++") || raw.startsWith("---") || raw.startsWith("diff ") ||
                    raw.startsWith("index ") || raw.startsWith("Index: ") -> return@forEach

                raw.startsWith("+") -> {
                    // Added line — content without the leading '+'.
                    val content = raw.removePrefix("+")
                    out.add(DiffLine(path = path, line = newLine, content = content))
                    newLine++
                }

                raw.startsWith("-") -> {
                    // Removed line — doesn't advance new-file counter.
                }

                raw.startsWith(" ") || raw.isEmpty() -> {
                    // Context line or empty — advances new-file counter (only non-empty context).
                    if (raw.isNotEmpty()) newLine++
                }

                // `\ No newline at end of file` and other markers — skip.
                else -> {}
            }
        }
        return out
    }

    /**
     * Index multiple files at once.
     *
     * @param files list of (path, patch) pairs (e.g. parsed from `gh api .../pulls/{n}/files`).
     * @return flat list of all added [DiffLine]s across all files.
     */
    fun indexFiles(files: List<Pair<String, String?>>): List<DiffLine> =
        files.flatMap { (path, patch) -> indexFile(path, patch) }

    /**
     * Find the first added line in [path] whose content contains [needle] (case-insensitive,
     * trimmed). Used to anchor an LLM-generated comment (which gave only a file + a hint about
     * the code) to a diff-valid line number.
     *
     * @return the matching [DiffLine], or null if no match.
     */
    fun findLine(lines: List<DiffLine>, path: String, needle: String): DiffLine? {
        val n = needle.trim().lowercase()
        if (n.isEmpty()) return lines.firstOrNull { it.path == path }
        return lines.firstOrNull { it.path == path && it.content.lowercase().contains(n) }
    }
}
