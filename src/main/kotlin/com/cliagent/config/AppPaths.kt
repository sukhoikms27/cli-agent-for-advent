package com.cliagent.config

import java.nio.file.Path

object AppPaths {
    val dataDir: Path = System.getenv("XDG_DATA_HOME")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".local", "share", "cli-agent")
    val chatsDir: Path get() = dataDir.resolve("chats")

    /**
     * Каталог конфигурации (XDG_CONFIG_HOME, день 20). Сюда кладётся единый [configFile]
     * (config.json) — масштабируемая точка конфигурации: модель, base-url, maxToolRounds,
     * массив MCP-серверов (`mcp`). Секреты (apiKey/token) — в файле (права 600) ИЛИ env-override.
     */
    val configDir: Path = System.getenv("XDG_CONFIG_HOME")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".config", "cli-agent")
    val configFile: Path get() = configDir.resolve("config.json")

    /** Глобальная долговременная память (кросс-чат/кросс-сессия, день 11). */
    val longTermDir: Path get() = dataDir.resolve("longterm")
    val longTermFile: Path get() = longTermDir.resolve("memory.json")

    /**
     * Каталог RAG-индексов (день 21). Один JSON-файл на стратегию chunking — для сравнения
     * (`/rag compare`): [ragIndexFixed] для fixed-size, [ragIndexStructural] для structural.
     * [ragIndexFile] — текущий/дефолтный индекс (alias для удобства CLI `/rag stats`).
     */
    val ragDir: Path get() = dataDir.resolve("rag")
    val ragIndexFile: Path get() = ragDir.resolve("index.json")
    val ragIndexFixed: Path get() = ragDir.resolve("index-fixed.json")
    val ragIndexStructural: Path get() = ragDir.resolve("index-structural.json")

    /** Персистентная история JLine3 REPL (между сессиями, TUI). */
    val replHistoryFile: Path get() = dataDir.resolve("repl-history")
}
