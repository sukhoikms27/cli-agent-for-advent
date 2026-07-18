package com.cliagent

import com.cliagent.cli.AskCommand
import com.cliagent.cli.ChatCommand
import com.cliagent.cli.CliAgentCommand
import com.cliagent.cli.FileAgentCommand
import com.cliagent.cli.ReviewPrCommand
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = CliAgentCommand()
    .subcommands(ChatCommand(), AskCommand(), ReviewPrCommand(), FileAgentCommand())
    .main(args)
