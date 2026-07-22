package com.cliagent.review.watch

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WorkFilterTest {

    @TempDir
    lateinit var tmpDir: Path

    /** Создаёт реальный ClaimStateStore на temp-директории и подмешивает state. */
    private suspend fun storeWith(
        consecutive: Int = 0,
        handled: List<String> = emptyList(),
    ): ClaimStateStore {
        val store = ClaimStateStore(file = tmpDir.resolve("state.json"))
        // Подмешиваем state через публичный API: handledIds добавляем через recordClaim (он же
        // инкрементит consecutive), потом сбрасываем consecutive до нужного.
        var s = store.load().copy(handledIds = handled, consecutiveWorks = consecutive)
        store.save(s)
        return store
    }

    private fun event(
        trackerId: String = "PCR-1",
        sprint: Int = 5,
        trackerUrl: String = "https://st.yandex-team.ru/PCR-1",
        studentName: String = "Test",
    ) = WorkEvent(
        source = "test",
        trackerId = trackerId,
        trackerUrl = trackerUrl,
        sprint = sprint,
        studentName = studentName,
        rawText = "test",
    )

    @Test
    fun `accepts new work under limit`() = runBlocking {
        val filter = WorkFilter(state = storeWith(consecutive = 0), maxConsecutive = 2)
        val decision = filter.evaluate(event())
        assertTrue(decision is WorkFilter.FilterDecision.Accept)
    }

    @Test
    fun `accepts at limit minus one`() = runBlocking {
        val filter = WorkFilter(state = storeWith(consecutive = 1), maxConsecutive = 2)
        val decision = filter.evaluate(event())
        assertTrue(decision is WorkFilter.FilterDecision.Accept)
    }

    @Test
    fun `suppresses when limit reached`() = runBlocking {
        val filter = WorkFilter(state = storeWith(consecutive = 2), maxConsecutive = 2)
        val decision = filter.evaluate(event())
        assertTrue(decision is WorkFilter.FilterDecision.Suppress)
        assertTrue((decision as WorkFilter.FilterDecision.Suppress).reason.contains("лимит"))
    }

    @Test
    fun `suppresses when already handled (deduplication)`() = runBlocking {
        val filter = WorkFilter(
            state = storeWith(consecutive = 0, handled = listOf("PCR-1")),
            maxConsecutive = 2,
        )
        val decision = filter.evaluate(event(trackerId = "PCR-1"))
        assertTrue(decision is WorkFilter.FilterDecision.Suppress)
        assertTrue((decision as WorkFilter.FilterDecision.Suppress).reason.contains("уже обработано"))
    }

    @Test
    fun `suppresses when sprint not in mySprints`() = runBlocking {
        val filter = WorkFilter(
            state = storeWith(consecutive = 0),
            maxConsecutive = 2,
            mySprints = setOf(3, 4),
        )
        val decision = filter.evaluate(event(sprint = 5))
        assertTrue(decision is WorkFilter.FilterDecision.Suppress)
        assertTrue((decision as WorkFilter.FilterDecision.Suppress).reason.contains("не в mySprints"))
    }

    @Test
    fun `accepts when sprint in mySprints`() = runBlocking {
        val filter = WorkFilter(
            state = storeWith(consecutive = 0),
            maxConsecutive = 2,
            mySprints = setOf(3, 4, 5),
        )
        val decision = filter.evaluate(event(sprint = 5))
        assertTrue(decision is WorkFilter.FilterDecision.Accept)
    }

    @Test
    fun `accepts when mySprints empty (all sprints allowed)`() = runBlocking {
        val filter = WorkFilter(
            state = storeWith(consecutive = 0),
            maxConsecutive = 2,
            mySprints = emptySet(),
        )
        assertTrue(filter.evaluate(event(sprint = 99)) is WorkFilter.FilterDecision.Accept)
    }

    @Test
    fun `suppresses empty event with no useful data`() = runBlocking {
        val filter = WorkFilter(state = storeWith(consecutive = 0), maxConsecutive = 2)
        val decision = filter.evaluate(WorkEvent())
        assertTrue(decision is WorkFilter.FilterDecision.Suppress)
        assertTrue((decision as WorkFilter.FilterDecision.Suppress).reason.contains("нет полезных данных"))
    }

    @Test
    fun `stale state resets consecutive after timeout`() = runBlocking {
        // lastClaimAt в далёком прошлом → currentConsecutive должно дать 0 (reset).
        val store = ClaimStateStore(file = tmpDir.resolve("stale.json"))
        val stale = ClaimStateStore.State(
            consecutiveWorks = 5,
            lastClaimAt = System.currentTimeMillis() - 60 * 60 * 1000,  // 1 час назад
            handledIds = emptyList(),
        )
        store.save(stale)
        assertEquals(0, store.currentConsecutive())
    }
}
