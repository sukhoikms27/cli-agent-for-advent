package com.cliagent.review.watch

import com.cliagent.config.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Persisted state для правила `consecutiveWorks < N` (пользовательское правило:
- «не более 2 работ подряд»).
 *
 * Хранится в `~/.local/share/cli-agent/review-bot/claim-state.json` (atomic write).
 *
 * - [consecutiveWorks] сколько работ было взято подряд без завершения проверки.
 * - [lastClaimAt] unix-millis последнего клейма (для auto-reset после таймаута).
 *
 * Сброс `consecutiveWorks → 0` происходит в двух случаях:
 *  1. Пользователь явно завершает проверку (verdict sent в [com.cliagent.review.cli.ReviewCommand]).
 *     (пока не подключено к pipeline — TODO Phase 1.)
 *  2. Прошло больше [STALE_AFTER_MS] (15 мин) с последнего клейма — считаем, что перерыв был.
 *
 * Не thread-safe для конкурентной записи — но watch — single-user single-process, это ОК.
 */
class ClaimStateStore(
    private val file: Path = AppPaths.dataDir.resolve("review-bot").resolve("claim-state.json"),
) {
    @Serializable
    data class State(
        val consecutiveWorks: Int = 0,
        val lastClaimAt: Long = 0L,
        val handledIds: List<String> = emptyList(),  // последние 100 tracker-ids для дедупликации
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Сброс consecutiveWorks если прошло больше этого времени с lastClaimAt. */
    private val STALE_AFTER_MS: Long = 15L * 60 * 1000

    suspend fun load(): State = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext State()
        try {
            json.decodeFromString(State.serializer(), Files.readString(file))
        } catch (e: Throwable) {
            State()
        }
    }

    suspend fun save(state: State) = withContext(Dispatchers.IO) {
        Files.createDirectories(file.parent)
        // Atomic write: temp + move.
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(
            tmp,
            json.encodeToString(State.serializer(), state),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /** Эффективный `consecutiveWorks` с учётом stale-reset. */
    suspend fun currentConsecutive(): Int {
        val state = load()
        val now = System.currentTimeMillis()
        return if (state.lastClaimAt > 0 && now - state.lastClaimAt > STALE_AFTER_MS) {
            0  // был перерыв — сбрасываем
        } else state.consecutiveWorks
    }

    /** Записать, что взяли новую работу. */
    suspend fun recordClaim(trackerId: String) {
        val state = load()
        val now = System.currentTimeMillis()
        val reset = now - state.lastClaimAt > STALE_AFTER_MS
        val updated = State(
            consecutiveWorks = if (reset) 1 else state.consecutiveWorks + 1,
            lastClaimAt = now,
            handledIds = (listOf(trackerId) + state.handledIds.filter { it != trackerId }).take(100),
        )
        save(updated)
    }

    /** Сброс consecutiveWorks → 0 (например, после отправки verdict). */
    suspend fun reset() {
        val state = load()
        save(state.copy(consecutiveWorks = 0, lastClaimAt = 0L))
    }

    /** Этот trackerId уже был обработан недавно? (дедупликация) */
    suspend fun alreadyHandled(trackerId: String): Boolean {
        return load().handledIds.contains(trackerId)
    }
}
