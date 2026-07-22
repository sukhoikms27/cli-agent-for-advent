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
        "Подкоманды: review, index-kb, watch, install-userscript.",
) {
    init {
        subcommands(ReviewCommand(), IndexKbCommand(), WatchCommand(), InstallUserscriptCommand())
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echo("Review Bot. Использование: review-bot <review|index-kb|watch|install-userscript> [options]")
            echo("Запустите `review-bot review --help` для деталей.")
        }
    }
}
