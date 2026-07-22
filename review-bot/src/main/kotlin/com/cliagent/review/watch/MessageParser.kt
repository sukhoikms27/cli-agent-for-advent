package com.cliagent.review.watch

import kotlinx.serialization.json.Json

/**
 * Парсит JSON, присланный Tampermonkey userscript'ом на `/messenger-event`, в [WorkEvent].
 *
 * Userscript уже извлекает trackerUrl/sprint/studentName из DOM (см.
 * `resources/yandex-messenger-watcher.user.js`) — здесь мы только:
 *  1. десериализуем JSON,
 *  2. дозаполняем [WorkEvent.trackerId] из trackerUrl (если парсер userscript'а не сделал),
 *  3. валидируем критичные поля (trackerUrl должен быть).
 *
 * Если JSON невалиден — возвращаем null; вызыватель логирует и пропускает.
 *
 * Образец из реального чата:
 * ```
 * Новое задание: [5] Искандар Хамитов (hamitoffiskandar@yandex.ru)
 * ```
 * DOM: `<a class="link link_md" href="https://st.yandex-team.ru/PCR-1989840">Новое задание</a>`.
 *
 * Userscript парсит → POST'ит:
 * ```json
 * {"source":"dom","messageId":"1784693458493054","trackerUrl":"https://st.yandex-team.ru/PCR-1989840",
 *  "sprint":5,"studentName":"Искандар Хамитов","rawText":"Новое задание: [5] ..."}
 * ```
 */
object MessageParser {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Regex для извлечения tracker-id из URL вида `https://st.yandex-team.ru/PCR-1989840`. */
    private val TRACKER_ID = Regex("""/([A-Z]+-\d+)""")

    /** Десериализует + дозаполняет trackerId. null если JSON невалиден. */
    fun parse(rawJson: String): WorkEvent? {
        val event = try {
            json.decodeFromString(WorkEvent.serializer(), rawJson)
        } catch (e: Throwable) {
            return null
        }
        // Дозаполняем trackerId из trackerUrl, если userscript его не вытащил.
        val withId = if (event.trackerId.isNullOrBlank() && !event.trackerUrl.isNullOrBlank()) {
            val id = TRACKER_ID.find(event.trackerUrl)?.groupValues?.get(1)
            event.copy(trackerId = id)
        } else event
        return withId
    }

    /**
     * Минимальная валидность события.
     *
     * @return [WorkEventValidity.VALID] если событие достаточно полезно для показа пользователю.
     */
    fun validity(event: WorkEvent): WorkEventValidity {
        return when {
            !event.trackerUrl.isNullOrBlank() -> WorkEventValidity.VALID
            event.rawText.isNullOrBlank() -> WorkEventValidity.NO_USEFUL_DATA
            else -> WorkEventValidity.NO_TRACKER_URL
        }
    }
}
