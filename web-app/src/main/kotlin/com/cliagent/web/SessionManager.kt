package com.cliagent.web

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * День 30: анонимные сессии веб-агента «Мотиватор».
 *
 * Сессия = random UUID + HMAC-SHA256 подпись (sign-then-compare). Cookie формат:
 * `sid=<uuid>.<base64url-hmac>`; HttpOnly + SameSite=Lax. Сервер проверяет подпись при каждом
 * запросе — поддельный UUID отбрасывается.
 *
 * Изоляция чатов: sessionId = chatId в [JsonChatStore] → каждый браузер видит только свои файлы
 * `<chatsDir>/<sessionId>.json`. Чужие сессии прочитать нельзя (cookie не угадать, HMAC не подделать
 * без секрета).
 *
 * Секрет берётся из env [MOTIVATOR_COOKIE_SECRET]; дефолт — random per-process (сессии не переживут
 * рестарт, что приемлемо для учебного стенда).
 *
 * Альтернатива (logins/passwords) отклонена пользователем — нужны анонимные сессии.
 */
class SessionManager(
    secret: String = System.getenv(ENV_SECRET) ?: randomSecret(),
) {

    private val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    /** Создать новую анонимную сессию → возвращает cookie-value `sid=<uuid>.<sig>`. */
    fun newSessionCookieValue(): String {
        val sid = UUID.randomUUID().toString()
        val sig = sign(sid)
        return "$sid.$sig"
    }

    /** Извлечь и верифицировать sessionId из cookie-value. null — если нет или подпись неверна. */
    fun validate(cookieValue: String?): String? {
        if (cookieValue.isNullOrBlank()) return null
        val parts = cookieValue.split(".", limit = 2)
        if (parts.size != 2) return null
        val (sid, sig) = parts
        if (!isValidUUID(sid)) return null
        // constant-time compare (защита от timing-атаки на подпись)
        val expected = sign(sid)
        return if (MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())) sid else null
    }

    /** Полная Set-Cookie строка для HTTP-ответа. */
    fun setCookieHeader(cookieValue: String, maxAgeSeconds: Int = 60 * 60 * 24 * 30): String =
        "sid=$cookieValue; HttpOnly; SameSite=Lax; Path=/; Max-Age=$maxAgeSeconds"

    private fun sign(sid: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        val raw = mac.doFinal(sid.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun isValidUUID(s: String): Boolean =
        try {
            UUID.fromString(s); true
        } catch (_: IllegalArgumentException) {
            false
        }

    companion object {
        const val ENV_SECRET = "MOTIVATOR_COOKIE_SECRET"
        const val COOKIE_NAME = "sid"

        /** Случайный секрет (если env не задан) — сессии не переживут рестарт процесса. */
        fun randomSecret(): String =
            UUID.randomUUID().toString() + UUID.randomUUID().toString()
    }
}
