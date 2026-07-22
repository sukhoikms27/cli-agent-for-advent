package com.cliagent.review

import com.cliagent.config.AppPaths
import java.nio.file.Path

/**
 * Изолированные XDG-пути для review-bot.
 *
 * Все данные складываются под `~/.local/share/cli-agent/review-bot/` (или `$XDG_DATA_HOME/...`),
 * **не** пересекаются с индексами dev-assistant (rag/) и support-app (support/rag/).
 *
 * - [ragIndexFile] — JSON-индекс над embedded чеклистом (для RAG-retrieval).
 * - [dataDir] — корень данных review-bot; сюда же можно класть typical-mistakes store и т.п.
 */
object ReviewBotPaths {
    val dataDir: Path = AppPaths.dataDir.resolve("review-bot")

    /** RAG-индекс над embedded чеклистом. */
    val ragIndexFile: Path get() = dataDir.resolve("rag").resolve("index.json")

    /** Каталог для распаковки embedded ресурсов (чеклисты и т.п.) перед индексацией. */
    val resourcesDir: Path get() = dataDir.resolve("resources")
}
