package com.cliagent.review.gh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Результат вызова `gh` CLI.
 *
 * @property exitCode 0 = успех.
 * @property stdout стандартный вывод (UTF-8, trimmed).
 * @property stderr стандартный поток ошибок (UTF-8, trimmed). null если merged с stdout.
 * @property success shortcut для `exitCode == 0`.
 */
data class GhResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String?,
) {
    val success: Boolean get() = exitCode == 0
}

/**
 * Исключение при запуске/выполнении `gh`.
 */
class GhException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Обёртка над `gh` CLI (GitHub).
 *
 * **Идентичность:** все вызовы идут от имени того, кто выполнил `gh auth login` (личный
 * GitHub-аккаунт ревьюера). Никаких PAT / GitHub Apps / bot-токенов.
 *
 * Конвенции (AGENTS.md):
 *  - suspend для IO (Dispatchers.IO);
 *  - `CancellationException` **никогда не глотается** — пробрасывается наверх;
 *  - timeout через `withTimeout`, чтобы зависший процесс не блокировал pipeline.
 *
 * **Deadlock-безопасность:** если есть [stdin] (для `gh api --input -`), чтение stdout/stderr
 * и запись stdin выполняются **параллельно** в отдельных потоках. Это обязательно — иначе
 * pipe-буфер OS переполняется (gh пишет в stdout, пока мы пишем в stdin), и оба блокируются
 * навсегда (классическая Java-проблема Process + pipe).
 */
object GhCli {

    /** Дефолтный таймаут на один вызов gh. */
    const val DEFAULT_TIMEOUT_MS: Long = 60_000L

    /**
     * Запускает `gh` с аргументами, опционально кормит [stdin] в процесс.
     */
    suspend fun run(
        args: List<String>,
        stdin: String? = null,
        workingDir: File? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        mergeStderr: Boolean = false,
    ): GhResult = withContext(Dispatchers.IO) {
        val cmd = buildList {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            if (isWindows) addAll(listOf("cmd.exe", "/c"))
            add("gh")
            addAll(args)
        }
        val proc = try {
            ProcessBuilder(cmd)
                .apply { workingDir?.let { directory(it) } }
                .redirectErrorStream(mergeStderr)
                .start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw GhException("Не удалось запустить `gh ${args.joinToString(" ")}`: ${e.message}", e)
        }

        // Deadlock-безопасное чтение stdout/stderr в отдельных потоках ДО записи в stdin.
        val stdoutRef = AtomicReference("")
        val stderrRef = AtomicReference("")
        val stdoutJob = Thread {
            runCatching {
                stdoutRef.set(proc.inputStream.bufferedReader(Charsets.UTF_8).readText())
            }
        }.apply { isDaemon = true; name = "gh-stdout" }
        val stderrJob = Thread {
            runCatching {
                stderrRef.set(proc.errorStream.bufferedReader(Charsets.UTF_8).readText())
            }
        }.apply { isDaemon = true; name = "gh-stderr" }
        stdoutJob.start()
        stderrJob.start()

        // Запись stdin в отдельном потоке (параллельно с чтением stdout/stderr).
        val stdinJob = if (stdin != null) {
            Thread {
                try {
                    proc.outputStream.use {
                        it.write(stdin.toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Процесс мог уже завершиться и закрыть stdin — не критично.
                }
            }.apply { isDaemon = true; name = "gh-stdin" }.also { it.start() }
        } else {
            // Обязательно закрыть stdin, иначе gh (если он ждёт ввода) повиснет.
            runCatching { proc.outputStream.close() }
            null
        }

        val finished = try {
            if (timeoutMs > 0) {
                withTimeout(timeoutMs) { proc.waitFor() }
            } else {
                proc.waitFor()
            }
        } catch (e: CancellationException) {
            proc.destroyForcibly()
            throw e
        }

        // Дочитываем потоки (процесс завершён — потокам осталось немного).
        stdinJob?.join(2_000)
        stdoutJob.join(5_000)
        stderrJob.join(5_000)

        val stdout = stdoutRef.get().trimEnd()
        val stderr = if (!mergeStderr) {
            stderrRef.get().trimEnd().takeIf { it.isNotEmpty() }
        } else null
        GhResult(exitCode = finished, stdout = stdout, stderr = stderr)
    }

    /** Проверяет, что `gh` доступен и авторизован. Бросает [GhException] при проблеме. */
    suspend fun requireAuth(): String {
        val r = run(listOf("auth", "status"), mergeStderr = true)
        if (!r.success) {
            throw GhException(
                "gh не авторизован (exit ${r.exitCode}). Выполните `gh auth login`.\n${r.stdout}",
            )
        }
        val account = Regex("""account\s+(\S+)\s""").find(r.stdout)?.groupValues?.get(1)
            ?: Regex("""account:\s*(\S+)""").find(r.stdout)?.groupValues?.get(1)
        return account ?: "(unknown account)"
    }
}
