package com.cliagent.review.cli

import com.cliagent.review.ReviewBotFactory
import com.cliagent.review.ReviewPipeline
import com.cliagent.review.gh.GhException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking

/**
 * `review-bot review --pr <url> --sprint N --student "Name" [--publish] [--no-rag]`.
 *
 * Прогоняет pipeline ревью и создаёт PENDING review в GitHub через `gh` CLI от пользователя.
 */
class ReviewCommand : CliktCommand(
    name = "review",
    help = "AI-ревью PR: clone → RAG → LLM → PENDING review в GitHub.",
) {
    private val pr: String by option("--pr", help = "GitHub PR URL (https://github.com/owner/repo/pull/N)")
        .required()

    private val sprint: Int by option("--sprint", help = "Номер спринта (например, 7 для Notes App)")
        .int()
        .required()

    private val student: String by option("--student", help = "Имя студента (для «Привет, X!»)")
        .required()

    private val publish: Boolean by option(
        "--publish",
        help = "Опубликовать review сразу (APPROVE/REQUEST_CHANGES). Default: PENDING review."
    ).flag()

    private val noRag: Boolean by option("--no-rag", help = "Без RAG — ревью только по diff")
        .flag()

    override fun run() = runBlocking {
        val factory = ReviewBotFactory.fromEnv()
        factory.use {
            val pipeline = ReviewPipeline(it)
            try {
                pipeline.run(
                    prUrl = pr,
                    sprint = sprint,
                    studentName = student,
                    publish = publish,
                    noRag = noRag,
                    onProgress = { msg -> System.err.println("▸ $msg") },
                )
            } catch (e: GhException) {
                System.err.println("❌ ${e.message}")
                @Suppress("DEPRECATION")
                kotlin.system.exitProcess(1)
            } catch (e: Throwable) {
                System.err.println("❌ Unexpected: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace(System.err)
                @Suppress("DEPRECATION")
                kotlin.system.exitProcess(1)
            }
        }
        Unit
    }
}
