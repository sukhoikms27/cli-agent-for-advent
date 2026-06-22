package com.cliagent.cli

import com.github.ajalt.mordant.animation.textAnimation
import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.AnsiLevel
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
            block()
        } finally {
            job.cancel()
            animation.clear()
        }
    }
}
