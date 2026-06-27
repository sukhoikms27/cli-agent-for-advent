package com.cliagent.mcp.server.util

import java.nio.file.Path

/**
 * XDG-пути данных MCP-сервера (модуль :mcp-server, Day 18). Независим от `AppPaths` главного
 * приложения — это отдельный gradle-проект; сервер крутится на VPS под своим юзером
 * (systemd `User=mcp`).
 *
 * $XDG_DATA_HOME/cli-agent/weather/{city-slug}.json — накопленные снапшоты по городам.
 */
internal object DataPaths {
    val dataDir: Path = System.getenv("XDG_DATA_HOME")?.let { Path.of(it) }
        ?.resolve("cli-agent")
        ?: Path.of(System.getProperty("user.home"), ".local", "share", "cli-agent")

    /** Один JSON-файл на город: {snapshots:[WeatherSnapshot,...]}. */
    val weatherDir: Path get() = dataDir.resolve("weather")
}
