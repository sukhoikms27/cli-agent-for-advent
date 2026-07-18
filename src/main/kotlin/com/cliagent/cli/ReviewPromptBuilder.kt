package com.cliagent.cli

import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.SystemPrompts

/**
 * День 32 — чистые функции сборки промпта для PR-review ([ReviewPrCommand]). Вынесены из command-
 * класса для unit-тестирования (без IO, без LLM, детерминированные).
 *
 *  - [extractQueryFromDiff] — извлекает retrieval-запрос из git-diff (имена файлов + идентификаторы).
 *  - [buildReviewPrompt] — собирает цепочку сообщений: system (codeReviewer + RAG) + user (diff).
 */
/**
 * Извлекает retrieval-запрос из git-diff для контекста ревью. Берёт:
 *  - имена файлов из строк `diff --git a/... b/...` и `+++ b/...` (без путей, только basename);
 *  - идентификаторы из добавленных строк (`+`): слова [A-Za-z_][A-Za-z0-9_]* длиной ≥ 4.
 *
 * Это даёт RAG-у «предметную область» изменений (какие классы/функции/файлы затронуты), чтобы
 * подтянуть релевантные куски документации. Без этого retrieval по всему diff'у был бы шумным.
 *
 * @param diff сырой git-diff
 * @return строка-запрос для embedding; пустая если diff пустой
 */
internal fun extractQueryFromDiff(diff: String): String {
    if (diff.isBlank()) return ""
    val tokens = mutableSetOf<String>()

    // Имена файлов из diff-хедеров (basename без расширения — обычно имя класса/модуля).
    val fileRegex = Regex("""^\+\+\+ b/(.+)$""", RegexOption.MULTILINE)
    fileRegex.findAll(diff).forEach { m ->
        val path = m.groupValues[1].trim()
        // /dev/null означает удаление файла — пропускаем (нет имени для extraction).
        if (path == "dev/null" || path == "/dev/null") return@forEach
        val base = path.substringAfterLast('/').substringBeforeLast('.')
        if (base.length >= 3) tokens.add(base)
    }

    // Идентификаторы из добавленных строк (что нового появилось в коде).
    val addedLines = diff.lineSequence().filter { it.startsWith("+") && !it.startsWith("+++") }
    val identRegex = Regex("""[A-Za-z_][A-Za-z0-9_]{3,}""")
    addedLines.forEach { line ->
        identRegex.findAll(line).forEach { m ->
            tokens.add(m.value)
        }
    }

    // Ограничиваем число токенов (защита embedding-запроса от раздувания).
    return tokens.take(MAX_QUERY_TOKENS).joinToString(" ")
}

/**
 * Сборка цепочки сообщений для PR-review: codeReviewer system prompt + опц. RAG block + diff.
 * Diff идёт в user-сообщении (а не system) — это контент для анализа, не инструкция.
 */
internal fun buildReviewPrompt(diff: String, ragBlock: String?): List<ChatMessage> {
    val systemContent = buildString {
        appendLine(SystemPrompts.codeReviewer.content)
        if (ragBlock != null) {
            appendLine()
            appendLine(ragBlock)
        }
    }
    val userContent = buildString {
        appendLine("Проанализируй следующий git-diff и дай структурированное ревью:")
        appendLine()
        appendLine("```diff")
        appendLine(diff)
        appendLine("```")
    }
    return listOf(
        ChatMessage(role = "system", content = systemContent),
        ChatMessage(role = "user", content = userContent),
    )
}

/** Лимит числа идентификаторов в retrieval-запросе (защита embedding от раздувания). */
private const val MAX_QUERY_TOKENS = 40
