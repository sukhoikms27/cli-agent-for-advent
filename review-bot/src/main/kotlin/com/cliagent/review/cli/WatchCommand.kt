package com.cliagent.review.cli

import com.cliagent.review.watch.ClaimStateStore
import com.cliagent.review.watch.MessageParser
import com.cliagent.review.watch.Notifier
import com.cliagent.review.watch.WorkEvent
import com.cliagent.review.watch.WorkFilter
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * `review-bot watch [--port 8082] [--max-consecutive 2] [--sprints 3,4,5] [--auto-notify]`.
 *
 * Запускает HTTP receiver для приёма POST от Tampermonkey userscript'а (см.
 * `resources/yandex-messenger-watcher.user.js`). Каждое сообщение проходит через:
 *
 *  1. [MessageParser] → [WorkEvent] (или drop если невалидный JSON).
 *  2. [WorkFilter] — дедупликация + правило `consecutiveWorks < maxConsecutive` + фильтр спринтов.
 *  3. [Notifier] — терминальный preview + Enter/Esc.
 *
 * Команда блокирует текущий поток до Ctrl+C. CORS: по умолчанию отвечает 200 на любые POST,
 * позволяет userscript'у не ждать ответа.
 *
 * Smoke-test без браузера:
 * ```
 * review-bot watch &
 * curl -X POST http://localhost:8082/messenger-event \
 *   -H 'Content-Type: application/json' \
 *   -d '{"source":"simulate","trackerUrl":"https://st.yandex-team.ru/PCR-1989840","sprint":5,"studentName":"Искандар Хамитов","rawText":"Новое задание: [5] ..."}'
 * ```
 */
@Suppress("OPT_IN_USAGE")
class WatchCommand : CliktCommand(
    name = "watch",
    help = "Слушает webhook от userscript'а (Messenger) и показывает preview новых заданий.",
) {
    private val port: Int by option("--port", help = "Порт HTTP receiver (default 8082)")
        .int()
        .default(8082)
        .help("Userscript POST'ит на http://localhost:<port>/messenger-event")

    private val maxConsecutive: Int by option("--max-consecutive", help = "Макс. работ подряд (default 2)")
        .int()
        .default(2)
        .help("Правило пользователя: не более N работ подряд без завершения проверки.")

    private val sprints: String? by option("--sprints", help = "Фильтр по спринтам (через запятую: 3,4,5)")
        .help("Пусто = все спринты (по ответу пользователя).")

    private val autoNotify: Boolean by option(
        "--auto-notify",
        help = "Не ждать Enter — сразу печатать preview и команду (для CI/uptime-мониторинга).",
    ).flag()

    override fun run() = runBlocking {
        val state = ClaimStateStore()
        val mySprints = sprints?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            ?: emptySet()
        val filter = WorkFilter(state = state, maxConsecutive = maxConsecutive, mySprints = mySprints)
        val notifier = Notifier(autoNotify = autoNotify)

        // Канал — буфер между HTTP-приёмником (fast I/O) и Notifier'ом (slow, ждёт Enter).
        val queue = Channel<WorkEvent>(capacity = 16)

        // Потребитель очереди — печатает preview по одному.
        val consumer = CoroutineScope(Dispatchers.Default).launch {
            for (event in queue) {
                val decision = filter.evaluate(event)
                when (decision) {
                    is WorkFilter.FilterDecision.Accept -> {
                        notifier.show(event) { ev ->
                            // Пользователь принял → фиксируем в state (consecutiveWorks++).
                            val tid = ev.trackerId ?: ev.trackerUrl ?: return@show
                            state.recordClaim(tid)
                        }
                    }
                    is WorkFilter.FilterDecision.Suppress -> {
                        echo("▸ suppressed: ${decision.reason}")
                    }
                }
            }
        }

        // HTTP receiver.
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
            routing {
                post("/messenger-event") {
                    val raw = call.receiveText()
                    val event = MessageParser.parse(raw)
                    if (event == null) {
                        call.respondText("ignored: invalid json", status = io.ktor.http.HttpStatusCode.BadRequest)
                        return@post
                    }
                    queue.trySend(event)
                    call.respondText("ok")
                }
            }
        }

        echo("review-bot watch запущен на http://127.0.0.1:$port/messenger-event")
        echo("  max-consecutive = $maxConsecutive")
        echo("  sprints filter  = ${mySprints.ifEmpty { "(все)" }}")
        echo("  Ожидай сообщений от userscript. Ctrl+C для остановки.")
        echo()

        server.start(wait = true)
        consumer.join()
        Unit
    }
}
