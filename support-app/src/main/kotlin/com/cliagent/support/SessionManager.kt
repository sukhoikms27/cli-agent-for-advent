package com.cliagent.support

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * День 33 — анонимные сессии support-app. Зеркало web-app/SessionManager.kt с переименованным
 * env-секретом (SUPPORT_COOKIE_SECRET вместо MOTIVATOR_COOKIE_SECRET).
 *
 * Cookie `sid=<uuid>.<base64url-hmac>`, HttpOnly + SameSite=Lax, HMAC-SHA256, constant-time compare.
 * Изоляция: sessionId → chatId в JsonChatStore → каждый пользователь видит только свои сообщения.
 */
class SessionManager(
    secret: String = System.getenv(ENV_SECRET) ?: randomSecret(),
) {

    private val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    fun newSessionCookieValue(): String {
        val sid = UUID.randomUUID().toString()
        val sig = sign(sid)
        return "$sid.$sig"
    }

    fun validate(cookieValue: String?): String? {
        if (cookieValue.isNullOrBlank()) return null
        val parts = cookieValue.split(".", limit = 2)
        if (parts.size != 2) return null
        val (sid, sig) = parts
        if (!isValidUUID(sid)) return null
        val expected = sign(sid)
        return if (MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())) sid else null
    }

    fun setCookieHeader(cookieValue: String, maxAgeSeconds: Int = 60 * 60 * 24 * 30): String =
        "sid=$cookieValue; HttpOnly; SameSite=Lax; Path=/; Max-Age=$maxAgeSeconds"

    private fun sign(sid: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        val raw = mac.doFinal(sid.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun isValidUUID(s: String): Boolean =
        try { UUID.fromString(s); true } catch (_: IllegalArgumentException) { false }

    companion object {
        const val ENV_SECRET = "SUPPORT_COOKIE_SECRET"
        const val COOKIE_NAME = "sid"

        fun randomSecret(): String =
            UUID.randomUUID().toString() + UUID.randomUUID().toString()
    }
}
