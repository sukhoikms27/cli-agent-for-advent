package com.cliagent.review.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.subcommands

/**
 * clikt-корень: `review-bot <subcommand>`.
 *
 * Подкоманды:
 *  - [ReviewCommand] (`review`) — основной pipeline ревью PR.
 *  - [IndexKbCommand] (`index-kb`) — индексация embedded чеклиста в RAG-индекс.
 */
class MainCli : NoOpCliktCommand(
    name = "review-bot",
    help = "Review Bot — AI-ревью студенческих заданий (Yandex Practicum, Android). " +
        "Подкоманды: review, index-kb.",
) {
    init {
        subcommands(ReviewCommand(), IndexKbCommand())
    }

    override fun run() {
        // invokeWithoutCommand не поддерживается в NoOpCliktCommand 4.4.0 — обрабатываем вручную.
        if (currentContext.invokedSubcommand == null) {
            echo("Review Bot. Использование: review-bot <review|index-kb> [options]")
            echo("Запустите `review-bot review --help` для деталей.")
        }
    }
}
