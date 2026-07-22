package com.cliagent.review.watch

import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.gray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notifier: печатает preview нового задания в терминал и ждёт реакции пользователя.
 *
 * Поведение:
 *  - Печатает цветной блок с инфой о задании.
 *  - Ждёт **Enter** (взять → печатает готовую команду для review-bot review) или **Esc/q**
 *    (пропустить → тишина до следующего события).
 *
 * ВАЖНО: Notifier **не запускает** pipeline автоматически (даже после Enter). Он только печатает
 * готовую команду, которую пользователь копирует/запускает. Причина — `review --pr <URL>` требует
 * PR-ссылки, которой у watcher'а нет (она внутри tracker-issue → CRM, это Collector Phase 1).
 * Когда Collector будет готов — Enter сможет продолжить pipeline автоматически.
 *
 * Поведение «single pending notification»: если уже ждём реакции на одно задание, новые события
 * становятся в очередь (не перебивают). Это чтобы не выводить 10 preview одновременно.
 *
 * `--auto-notify` (опционально, не в MVP): без ожидания Enter — сразу печать и продолжать.
 */
class Notifier(
    private val autoNotify: Boolean = false,
) {
    private val processing = AtomicBoolean(false)

    /**
     * Показать preview одного события.
     *
     * @param onAccepted вызывается, если пользователь нажал Enter.
     * @return true если preview показан (можно считать как «взял в работу»).
     */
    suspend fun show(event: WorkEvent, onAccepted: suspend (WorkEvent) -> Unit = {}) {
        if (!processing.compareAndSet(false, true)) {
            // Уже ждём реакции на другое задание — новое в очередь не ставим (MVP: drop).
            // Для production — Queue + последовательная обработка. Сейчас просто логируем.
            println(gray("⚠️  уже ждёт реакции другое задание — пропускаю новое"))
            return
        }
        try {
            printBlock(event)
            if (autoNotify) {
                onAccepted(event)
                return
            }
            when (readKey()) {
                UserKey.ENTER -> {
                    println(green("✓ принято → вот команда для ревью:"))
                    println("  " + buildReviewCommand(event))
                    println()
                    onAccepted(event)
                }
                UserKey.ESC, UserKey.Q -> {
                    println(yellow("↩ пропущено"))
                }
                UserKey.OTHER -> {
                    println(yellow("↩ пропущено (не Enter)"))
                }
            }
        } finally {
            processing.set(false)
        }
    }

    private fun printBlock(event: WorkEvent) {
        val sprint = event.sprint?.let { "sprint=$it" } ?: "sprint=?"
        val student = event.studentName ?: "(имя неизвестно)"
        val tracker = event.trackerId ?: event.trackerUrl ?: "(нет tracker-link)"
        val lines = buildString {
            appendLine("╔══════════════════════════════════════════════════════════╗")
            appendLine("║  📩  НОВОЕ ЗАДАНИЕ                                          ║")
            appendLine("╠══════════════════════════════════════════════════════════╣")
            appendLine("║  $student".padEnd(59) + "║")
            appendLine("║  $sprint  •  tracker: $tracker".padEnd(59) + "║")
            if (event.trackerUrl != null) {
                appendLine("║  открыть: ${event.trackerUrl}".padEnd(59) + "║")
            }
            appendLine("╠══════════════════════════════════════════════════════════╣")
            appendLine("║  Enter = принять   •   Esc/q = пропустить                  ║")
            appendLine("╚══════════════════════════════════════════════════════════╝")
        }
        // Без ANSI-раскраски блока — терминалы показывают ASCII-рамку стабильно.
        println(lines)
        // Цветной заголовок «waiting for input» (mordant TextColors сами проверят TTY).
        println(brightYellow("▸ waiting for input…"))
    }

    /** Готовит команду `review-bot review --pr <URL>` — пользователь копирует и запускает. */
    private fun buildReviewCommand(event: WorkEvent): String {
        val sprint = event.sprint ?: 7  // дефолт — 7 (Notes App); LLM вытащит реальный из чеклиста
        val student = event.studentName?.replace(" ", "\\ ") ?: "Student"
        val trackerUrl = event.trackerUrl ?: ""
        return buildString {
            append("CLI_AGENT_API_KEY=$${"\${CLI_AGENT_API_KEY}"} ")
            append("./gradlew :review-bot:run --args=\"review ")
            append("--pr <GH-PR-URL-ИЗ-CRM> ")  // TODO Phase 1: Collector достанет это из tracker
            append("--sprint $sprint ")
            append("--student \"$student\"")
            if (trackerUrl.isNotBlank()) append(" # $trackerUrl")
            append("\"")
        }
    }

    private enum class UserKey { ENTER, ESC, Q, OTHER }

    /**
     * Читает один keypress из stdin. Enter → ENTER; Esc или q → ESC/Q; остальное → OTHER.
     *
     * В raw-terminal нам нужен `stty -echo` и по-байтовое чтение, но это платформо-зависимо.
     * Для MVP используем построчное чтение (Enter нужно нажать с переводом строки). q+Enter тоже
     * работает. Если пользователь хочет Esc — пусть жмёт q+Enter.
     */
    private fun readKey(): UserKey {
        return try {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            val line = reader.readLine() ?: return UserKey.OTHER
            when {
                line.isEmpty() -> UserKey.ENTER
                line.trim().equals("q", ignoreCase = true) -> UserKey.Q
                line.first() == '\u001B' -> UserKey.ESC  // Esc-символ
                else -> UserKey.OTHER
            }
        } catch (e: Throwable) {
            UserKey.OTHER
        }
    }
}
