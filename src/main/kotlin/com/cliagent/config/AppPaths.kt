package com.cliagent.config

import java.nio.file.Path

object AppPaths {
    val dataDir: Path = System.getenv("XDG_DATA_HOME")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".local", "share", "cli-agent")
    val chatsDir: Path get() = dataDir.resolve("chats")
}
