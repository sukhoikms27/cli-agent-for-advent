package com.cliagent.config

import java.nio.file.Path

object AppPaths {
    val dataDir: Path = System.getenv("XDG_DATA_HOME")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".local", "share", "cli-agent")
    val chatsDir: Path get() = dataDir.resolve("chats")

    /** Глобальная долговременная память (кросс-чат/кросс-сессия, день 11). */
    val longTermDir: Path get() = dataDir.resolve("longterm")
    val longTermFile: Path get() = longTermDir.resolve("memory.json")

    /** Персистентная история JLine3 REPL (между сессиями, TUI). */
    val replHistoryFile: Path get() = dataDir.resolve("repl-history")
}
