package com.cliagent

import com.cliagent.cli.AskCommand
import com.cliagent.cli.ChatCommand
import com.cliagent.cli.CliAgentCommand
import com.cliagent.cli.ReviewPrCommand
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = CliAgentCommand()
    // День 34: FileAgentCommand удалён — file-работа доступна через /task с авто-детектом FILE_OP.
    .subcommands(ChatCommand(), AskCommand(), ReviewPrCommand())
    .main(args)
