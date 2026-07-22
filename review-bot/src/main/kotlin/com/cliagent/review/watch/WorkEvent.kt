package com.cliagent.review.watch

import kotlinx.serialization.Serializable

/**
 * Событие о новой работе — присылается userscript'ом (через POST на `/messenger-event`)
 * или эмулируется вручную (для smoke-test'а через `watch --simulate <json>`).
 *
 * @property source откуда пришло: `"dom"` (userscript MutationObserver) или `"simulate"`.
 * @property messageId уникальный id сообщения (data-timestamp из DOM); null если неизвестен.
 *                     Используется для дедупликации на стороне review-bot.
 * @property trackerUrl полная ссылка на Tracker-issue (`https://st.yandex-team.ru/PCR-XXXX`).
 * @property trackerId короткий id (например `PCR-1989840`), извлекается из [trackerUrl].
 * @property sprint номер спринта из `[N]` в сообщении; null если не распознан.
 * @property studentName ФИО студента; null если не распознано.
 * @property rawText видимый текст сообщения (до 500 символов) — для дебага.
 * @property ts unix-millis времени перехвата на стороне userscript'а.
 */
@Serializable
data class WorkEvent(
    val source: String = "unknown",
    val messageId: String? = null,
    val trackerUrl: String? = null,
    val trackerId: String? = null,
    val sprint: Int? = null,
    val studentName: String? = null,
    val rawText: String? = null,
    val ts: Long = System.currentTimeMillis(),
)

/**
 * Результат валидации [WorkEvent]'а: готов ли он к показу Notifier'ом.
 *
 * Минимально-достаточный набор для полезного preview: trackerUrl (без него пользователь не сможет
 * открыть работу) ИЛИ хотя бы rawText (какой-то сигнал, что что-то пришло). sprint и studentName —
 * желательны, но не критичны.
 */
enum class WorkEventValidity {
    VALID,
    NO_TRACKER_URL,
    NO_USEFUL_DATA,
}
