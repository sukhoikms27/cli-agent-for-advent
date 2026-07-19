package com.cliagent.cli

import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * День 34: y/N prompt для подтверждения опасных операций (Human-in-the-Loop, лекция нед.7).
 *
 * Универсальный confirm для любой опасной операции, проходящей через
 * [com.cliagent.agent.DangerousOpGate]. Принимает `op` (идентификатор) и `args` (параметры),
 * рендерит осмысленный preview и спрашивает y/N. Default = N (fail-safe: сомнительно → отказ).
 *
 * **Принцип**: новая опасная операция = новый `op`-string. Эта функция рендерит preview по
 * `args` и не требует правки при добавлении новых операций. Специфичная обработка (какой ключ
 * preview'нуть) — в [renderPreview], расширяется декларативно.
 *
 * Используется в [ChatCommand.buildSession] через [com.cliagent.agent.TtyGate] для всех
 * file-операций (write_file, delete_file, будущие move_file/git_commit/...).
 */
suspend fun confirmDangerousOp(op: String, args: Map<String, Any?>): Boolean {
    val path = (args["path"] as? String).orEmpty()
    AppTerminal.println()
    AppTerminal.println(yellow("⚠️  Опасная операция: $op") +
        (if (path.isNotEmpty()) yellow("  →  $path") else ""))
    renderPreview(op, args)
    AppTerminal.print("   Разрешить? [y/N] ")

    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    val answer = try {
        reader.readLine()?.trim()?.lowercase()
    } catch (e: Exception) {
        null
    }
    val approved = answer == "y" || answer == "yes"
    AppTerminal.println(
        if (approved) green("   ✓ Подтверждено")
        else red("   ✗ Отклонено")
    )
    return approved
}

/**
 * Специфичный preview по типу операции. Для новых op — добавить ветку сюда.
 * Не найдя op, показывает список аргументов (минимально информативно).
 */
private fun renderPreview(op: String, args: Map<String, Any?>) {
    when (op) {
        "write_file" -> {
            val content = (args["content"] as? String).orEmpty()
            AppTerminal.println(gray("   Размер: ${content.length} символов"))
            val preview = content.lineSequence().take(8).joinToString("\n")
            if (preview.isNotBlank()) AppTerminal.println(gray("   Preview:\n$preview"))
        }
        "delete_file" -> {
            // Удаление — preview не имеет смысла, но поясняем необратимость.
            AppTerminal.println(gray("   Действие необратимо — файл будет удалён без корзины."))
        }
        else -> {
            // Generic: показать ключевые поля (кроме длинного content).
            val summary = args.entries
                .filterNot { it.key == "content" }
                .joinToString(", ") { "${it.key}=${it.value}" }
            if (summary.isNotBlank()) AppTerminal.println(gray("   Аргументы: $summary"))
        }
    }
}

/**
 * Legacy-обёртка для backward-compat с кодом, который явно передавал `(path, content)`.
 * Делегирует в [confirmDangerousOp].
 */
suspend fun confirmWritePrompt(path: String, content: String): Boolean =
    confirmDangerousOp("write_file", mapOf("path" to path, "content" to content))
