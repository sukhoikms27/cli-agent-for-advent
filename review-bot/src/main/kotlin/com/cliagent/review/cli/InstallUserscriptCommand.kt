package com.cliagent.review.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * `review-bot install-userscript [--port 8082]`.
 *
 * Распаковывает embedded userscript (`yandex-messenger-watcher.user.js`) в `~/review-bot-userscript/`
 * и печатает инструкции по установке в Tampermonkey.
 *
 * `--port` перезаписывает `WEBHOOK_URL` в скрипте, чтобы userscript указывал на тот же порт,
 * на котором запущен `review-bot watch`. Default 8082.
 */
class InstallUserscriptCommand : CliktCommand(
    name = "install-userscript",
    help = "Распаковывает Tampermonkey userscript и печатает инструкции по установке.",
) {
    private val port: Int by option("--port")
        .int()
        .default(8082)

    private val outputDir: Path = Path.of(System.getProperty("user.home"), "review-bot-userscript")

    override fun run() {
        val resource = javaClass.getResourceAsStream("/yandex-messenger-watcher.user.js")
            ?: error("Embedded userscript not found: /yandex-messenger-watcher.user.js")
        Files.createDirectories(outputDir)
        val target = outputDir.resolve("yandex-messenger-watcher.user.js")
        val raw = resource.bufferedReader(Charsets.UTF_8).readText()
        resource.close()

        // Подставляем порт (default в скрипте — 8082; если отличется — перезапишем).
        val patched = if (port != 8082) {
            raw.replace("http://localhost:8082/messenger-event", "http://localhost:$port/messenger-event")
        } else raw
        Files.writeString(
            target,
            patched,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        echo("✓ Userscript распакован: $target")
        echo()
        echo("УСТАНОВКА В TAMPERMONKEY:")
        echo("  1. Открой Tampermonkey → Dashboard → Utilities → «Import from file» (или перетащи файл в окно).")
        echo("  2. ИЛИ открой chrome://extensions → Tampermonkey → «Create a new script» → вставь содержимое файла.")
        echo("  3. Сохрани скрипт (Ctrl+S).")
        echo("  4. Перезагрузи вкладку Яндекс.Мессенджера.")
        echo("  5. Открой F12 → Console — увидишь «[review-bot] userscript загружен. webhook = ...».")
        echo()
        echo("ЗАПУСК WATCHER'А (в терминале):")
        echo("  ./gradlew :review-bot:run --args=\"watch --port $port\"")
        echo()
        echo("SMOKE-TEST (без браузера):")
        echo("  ./gradlew :review-bot:run --args=\"watch\" &")
        echo("  curl -X POST http://localhost:$port/messenger-event \\")
        echo("    -H 'Content-Type: application/json' \\")
        echo("    -d '{\"source\":\"simulate\",\"trackerUrl\":\"https://st.yandex-team.ru/PCR-1989840\",\"sprint\":5,\"studentName\":\"Искандар Хамитов\",\"rawText\":\"Новое задание: [5] ...\"}'")
        echo()
        echo("Лог userscript'а (в браузере): F12 → Console, фильтр «[review-bot]».")
        Unit
    }
}
