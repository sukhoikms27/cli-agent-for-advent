package com.cliagent.cli

import com.github.ajalt.clikt.core.CliktCommand

class CliAgentCommand : CliktCommand(name = "cli-agent", help = "CLI Agent — Kotlin LLM Agent") {
    override fun run() {
        echo("Use 'cli-agent chat' to start interactive chat.")
        echo("Run 'cli-agent --help' for available commands.")
    }
}
