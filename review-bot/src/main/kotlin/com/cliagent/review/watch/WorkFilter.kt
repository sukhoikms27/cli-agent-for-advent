package com.cliagent.review.watch

/**
 * Применяет пользовательские правила к [WorkEvent]:
 *
 *  1. **Дедупликация** по trackerId (через [ClaimStateStore.alreadyHandled]) — userscript может
 *     выстрелить несколько раз (например, при scroll-back по истории). Не хотим повторно
 *     показывать preview для уже обработанной работы.
 *  2. **consecutiveWorks < maxConsecutive** — правило пользователя «не более 2 работ подряд».
 *     Если уже взято 2 работы и они не закрыты — watcher **молчит** (или показывает suppressed-
 *     note, чтобы пользователь понимал, что работа появилась, но он уперся в лимит).
 *  3. **mySprints** (опционально) — если задан список «моих» спринтов, фильтруем по нему.
 *     По умолчанию пусто = все спринты (по ответу пользователя: «любой спринт ок»).
 *
 * Фильтр возвращает [FilterDecision]: показывать / подавить / пропустить.
 */
class WorkFilter(
    private val state: ClaimStateStore,
    private val maxConsecutive: Int = 2,
    private val mySprints: Set<Int> = emptySet(),
) {

    /** Результат фильтрации. */
    sealed class FilterDecision {
        /** Показать preview, предложить клейм. */
        data class Accept(val event: WorkEvent) : FilterDecision()

        /** Подавить preview, но залогировать причину (дедупликация / лимит / не мой спринт). */
        data class Suppress(val event: WorkEvent, val reason: String) : FilterDecision()
    }

    /**
     * @param event событие из userscript'а.
     */
    suspend fun evaluate(event: WorkEvent): FilterDecision {
        // 0. Невалидное событие — пропускаем молча (userscript не должен такое слать, но всё же).
        val validity = MessageParser.validity(event)
        if (validity == WorkEventValidity.NO_USEFUL_DATA) {
            return FilterDecision.Suppress(event, "нет полезных данных (пустой event)")
        }
        // 1. Дедупликация по trackerId.
        val tid = event.trackerId ?: event.trackerUrl
        if (tid != null && state.alreadyHandled(tid)) {
            return FilterDecision.Suppress(event, "уже обработано (trackerId=$tid)")
        }
        // 2. Не мой спринт.
        if (mySprints.isNotEmpty() && event.sprint != null && event.sprint !in mySprints) {
            return FilterDecision.Suppress(event, "спринт ${event.sprint} не в mySprints=$mySprints")
        }
        // 3. consecutiveWorks < max.
        val current = state.currentConsecutive()
        if (current >= maxConsecutive) {
            return FilterDecision.Suppress(
                event,
                "лимит: consecutiveWorks=$current >= maxConsecutive=$maxConsecutive"
            )
        }
        return FilterDecision.Accept(event)
    }
}
