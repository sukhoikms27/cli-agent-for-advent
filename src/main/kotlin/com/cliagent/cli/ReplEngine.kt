package com.cliagent.cli

import com.cliagent.config.AppPaths
import org.jline.reader.Completer
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.completer.AggregateCompleter
import org.jline.reader.impl.completer.ArgumentCompleter
import org.jline.reader.impl.completer.StringsCompleter
import org.jline.terminal.TerminalBuilder
import java.nio.file.Files

/**
 * JLine3 REPL-движок (TUI): редактирование строк, персистентная история,
 * tab-completion slash-команд, корректные Ctrl+C (отмена ввода) / Ctrl+D (выход).
 *
 * Заменяет голый `BufferedReader.readLine()` из ChatCommand.
 */
class ReplEngine {

    private val terminal = TerminalBuilder.builder()
        .system(true)
        .dumb(true)   // fallback для non-TTY (piped stdin) — без editing, но читает строки
        .build()

    private val reader: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .completer(buildCompleter())
        .variable(LineReader.HISTORY_FILE, AppPaths.replHistoryFile.toString())
        .build()

    init {
        Files.createDirectories(AppPaths.replHistoryFile.parent)
    }

    /**
     * Один шаг REPL.
     * @return введённая строка; null = выход (Ctrl+D); "" = пустой ввод или Ctrl+C (continue).
     */
    fun readLine(): String? = try {
        reader.readLine("cli-agent> ")
    } catch (e: UserInterruptException) {
        ""        // Ctrl+C — отменить ввод, продолжить REPL
    } catch (e: EndOfFileException) {
        null      // Ctrl+D — выход
    }

    private fun buildCompleter(): Completer {
        val top = StringsCompleter(
            "/exit", "/help", "/history", "/chats", "/stats", "/cost",
            "/summary", "/compress", "/facts", "/reset",
            "/strategy", "/branch", "/memory", "/profile", "/invariants", "/task"
        )
        val strategy = ArgumentCompleter(
            StringsCompleter("/strategy"),
            StringsCompleter("sliding", "facts", "summary", "branch")
        )
        val branch = ArgumentCompleter(
            StringsCompleter("/branch"),
            StringsCompleter("create", "list", "switch")
        )
        val memory = ArgumentCompleter(
            StringsCompleter("/memory"),
            StringsCompleter("show", "save", "clear"),
            StringsCompleter("short", "working", "long", "task", "plan", "note", "decision", "knowledge")
        )
        val profile = ArgumentCompleter(
            StringsCompleter("/profile"),
            StringsCompleter("show", "set", "add", "remove", "extract", "clear"),
            StringsCompleter("style", "format", "about", "constraint")
        )
        val task = ArgumentCompleter(
            StringsCompleter("/task"),
            StringsCompleter("show", "start", "next", "set", "step", "expect", "plan", "impl", "verdict", "back", "done", "reset"),
            StringsCompleter("clarify", "planning", "execution", "validation", "done")
        )
        val invariants = ArgumentCompleter(
            StringsCompleter("/invariants"),
            StringsCompleter("show", "add", "remove", "clear"),
            StringsCompleter("STACK", "BAN", "ARCH", "BUSINESS")
        )
        return AggregateCompleter(top, strategy, branch, memory, profile, task, invariants)
    }
}
