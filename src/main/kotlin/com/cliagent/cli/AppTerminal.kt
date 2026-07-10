package com.cliagent.cli

import com.github.ajalt.mordant.animation.textAnimation
import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Единая точка терминального вывода (mordant, TUI).
 * Цвета автоопределяются; [disableColor] — для `--no-color`.
 * Заменяет кликт `echo` и stray `println` по всему CLI-слою.
 */
object AppTerminal {

    var t: Terminal = Terminal()
        private set

    fun println(text: Any? = "") = t.println(text)

    fun print(text: Any?) = t.print(text)

    /** `--no-color`: пересоздать терминал без ANSI. */
    fun disableColor() {
        t = Terminal(AnsiLevel.NONE)
    }

    fun ok(msg: String) = t.println("${green("✓")} $msg")

    fun err(msg: String) = t.println("${red("Error:")} $msg")

    fun warn(msg: String) = t.println("${yellow("⚠️")} $msg")

    /**
     * Отрендерить [text] как GitHub Flavored Markdown (заголовки, списки, жирный/код,
     * fenced-блоки с рамкой, цитаты, таблицы). Цвет на TTY, plain на `--no-color`/пайпах.
     * Использует widget-overload `t.println(Widget)`, а не `println(Any?)` (иначе был бы toString).
     *
     * **Защитный fallback:** mordant-markdown (intellij-markdown) бросает
     * `IllegalStateException` на отдельных токенах — notably одиночный `$` воспринимается
     * как math-delimiter и ломает inline-парсер. LLM-ответ непредсказуем и часто содержит
     * `$` (математика, шелл, цены, пути вроде `path: $`), поэтому рендер НИКОГДА не должен
     * ронять REPL: при любой ошибке парсинга — fallback на plain-текст.
     */
    fun markdown(text: String) {
        try {
            t.println(Markdown(text))
        } catch (e: Exception) {
            t.println(text)
        }
    }

    /**
     * Крутит спиннер с [label], пока выполняется [block] (LLM-вызов и т.п.).
     *
     * День 15 (п.4): делегирует в [withSpinner] с [labelProvider] — статичный лейбл как частный
     * случай. Обратно совместимо: существующие call-sites `withSpinner("Thinking…") { ... }` не
     * меняются.
     *
     * mordant `Animation` сам ничего не выводит на non-interactive терминале (piped stdin),
     * поэтому спиннер не garble'ит вывод в пайпах; на TTY рисует кадры в одной строке.
     *
     * `CancellationException` не глотаем: finally отменяет джобу и чистит кадр,
     * затем исключение пробрасывается стандартно.
     */
    suspend fun <T> withSpinner(label: String, block: suspend () -> T): T =
        withSpinner({ label }, block)

    /**
     * Крутит спиннер с динамическим [labelProvider], пока выполняется [block] (день 15, п.4).
     *
     * В отличие от overload-а со статичным [String], [labelProvider] вызывается на каждом кадре —
     * лейбл отражает текущую стадию/действие (thinking/planning/executing/validating/…). Источник
     * метки — состояние агента (см. ChatCommand.spinnerLabel, задача 22).
     *
     * mordant `Animation` сам ничего не выводит на non-interactive терминале; на TTY рисует кадры
     * в одной строке. `CancellationException` пробрасывается после очистки кадра.
     */
    suspend fun <T> withSpinner(labelProvider: () -> String, block: suspend () -> T): T = coroutineScope {
        val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
        val animation = t.textAnimation<Int> { tick ->
            "${frames[tick % frames.length]} ${labelProvider()}"
        }
        val job = launch {
            var tick = 0
            while (isActive) {
                animation.update(tick++)
                delay(120)
            }
        }
        try {
            val result = block()
            result
        } catch (e: Throwable) {
            throw e
        } finally {
            job.cancel()
            animation.clear()
        }
    }

    // ── День 24: таймер выполнения + серая строка длительности ────────────────────

    /**
     * Форматирует elapsed-время (мс) как `HH:MM:SS`. Используется в live-таймере спиннера и в
     * пост-выводе серой строки длительности после ответа агента (день 24).
     *
     * Примеры: `999ms` → `00:00:00`, `65_000ms` → `00:01:05`, `3_661_000ms` → `01:01:01`.
     */
    fun formatHMS(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    /**
     * День 24: серая (gray) строка длительности после ответа агента.
     *
     * Печатает `⏱ HH:MM:SS` серым цветом (ANSI gray = тёмно-серый; на `--no-color` —
     * plain-текст без ANSI-кодов). Не печатает ничего при `elapsedMillis <= 0` (мгновенный ответ
     * — canned «не знаю» или кеш — не засоряем вывод). Новая строка для визуального отделения.
     */
    fun printDuration(elapsedMillis: Long) {
        if (elapsedMillis <= 0) return
        t.println(gray("⏱ ${formatHMS(elapsedMillis)}"))
    }

    /**
     * День 24: спиннер с live-таймером. Крутит анимацию с лейблом `"<baseLabel> HH:MM:SS"`, где
     * время обновляется на каждом кадре (каждые 120ms) — пользователь видит, сколько уже длится
     * запрос. По завершении возвращает [TimedResult] с результатом и elapsed-временем для пост-вывода
     * через [printDuration].
     *
     * Время замеряется через `System.nanoTime()` в closure labelProvider-а (non-suspend, как требует
     * [withSpinner]) — точно и не зависит от системных часов. Альтернатива — расширение существующего
     * [withSpinner] `labelProvider`-overload-а; здесь вынесено отдельно, чтобы не ломать call-sites.
     *
     * `CancellationException` пробрасывается (как в [withSpinner] — finally чистит кадр).
     */
    suspend fun <T> withTimedSpinner(baseLabel: String, block: suspend () -> T): TimedResult<T> {
        val start = System.nanoTime()
        val result = withSpinner({ "$baseLabel ${formatHMS((System.nanoTime() - start) / 1_000_000)}" }, block)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        return TimedResult(result, elapsedMillis)
    }

    /**
     * День 24: результат [withTimedSpinner] — значение блока + затраченное время (мс).
     * `elapsedMillis` используется для серой строки [printDuration] после ответа.
     */
    data class TimedResult<T>(val value: T, val elapsedMillis: Long)
}
