package com.cliagent

import com.cliagent.cli.ChatCommand
import com.cliagent.cli.CliAgentCommand
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = CliAgentCommand()
    .subcommands(ChatCommand())
    .main(args)
