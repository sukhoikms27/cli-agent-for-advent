package com.cliagent.mcp.server.wikipedia

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Клиент к Wikipedia (Day 19 — search-источник для пайплайна tech-дайджеста). Read-only, **без
 * API-ключа** — безопасен для remote-сервера (как Open-Meteo в Day 18).
 *
 * **Двухшаговый search:** произвольный запрос пользователя ≠ точное название статьи, поэтому:
 *  1. [resolveTitle] — MediaWiki `opensearch`: по поисковой фразе находит ближайшее валидное
 *     название статьи (с резолвом редиректов). Возвращает первый title или null.
 *  2. [summary] — REST `page/summary/{title}`: plain-text extract + URL + описание.
 *
 * Композиция в [search]: один вызов «фраза → статья». null, если статья не найдена / ошибка сети.
 *
 * Ошибки сети/парсинга/HTTP → `null` (handler в WikipediaTools решает, вернуть tool-error или
 * fallback). `CancellationException` — re-throw (конвенция проекта, AGENTS.md).
 *
 * @param http injectable HttpClient (default — CIO + ContentNegotiation/json). В тестах
 *        подставляется HttpClient с MockEngine — без реальной сети.
 */
internal class WikipediaClient(
    private val http: HttpClient = defaultClient(),
) {
    /**
     * Поиск по произвольной фразе: opensearch (резолв названия) → summary (extract).
     * @param language языковой раздел ("en" / "ru"); allowlist см. [LANGS]
     * @return [WikiArticle] с extract'ом или null (не найдено / ошибка сети)
     */
    suspend fun search(query: String, language: String = "en"): WikiArticle? {
        if (!QUERY_REGEX.matches(query)) return null          // allowlist ДО подстановки в URL
        val lang = LANGS[language.lowercase()] ?: "en"
        val title = resolveTitle(query, lang) ?: return null
        return summary(title, lang)
    }

    /** Шаг 1: opensearch по фразе → первый валидный title статьи (с резолвом редиректов). */
    private suspend fun resolveTitle(query: String, lang: String): String? {
        val resp = try {
            http.get("https://$lang.wikipedia.org/w/api.php") {
                url.parameters.append("action", "opensearch")
                url.parameters.append("search", query.trim())
                url.parameters.append("limit", "1")
                url.parameters.append("namespace", "0")
                url.parameters.append("format", "json")
                url.parameters.append("redirects", "resolve")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (resp.status != HttpStatusCode.OK) return null
        // opensearch отдаёт JSON-массив: [query, [titles], [descriptions], [urls]].
        // Нам нужен titles[0] — первое валидное название статьи.
        val arr = runCatching { resp.body<JsonElement>() }.getOrNull() as? JsonArray ?: return null
        val titles = arr.getOrNull(1)?.let { it as? JsonArray } ?: return null
        val first = titles.firstOrNull()?.jsonPrimitive?.content ?: return null
        return first.takeIf { it.isNotBlank() }
    }

    /** Шаг 2: REST summary по title → plain-text extract + URL + описание. */
    private suspend fun summary(title: String, lang: String): WikiArticle? {
        val safe = title.replace(" ", "_")
        val resp = try {
            http.get("https://$lang.wikipedia.org/api/rest_v1/page/summary/$safe")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (resp.status != HttpStatusCode.OK) return null
        val data = runCatching { resp.body<SummaryResponse>() }.getOrNull() ?: return null
        // type=="disambiguation" / "no-extract" — берём, но extract может быть пустым.
        if (data.extract.isNullOrBlank() && data.description.isNullOrBlank()) return null
        val url = data.contentUrls?.desktop?.page
        return WikiArticle(
            title = data.title ?: title,
            description = data.description,
            extract = data.extract ?: "",
            url = url,
        )
    }

    fun close(): Unit = runCatching { http.close() }.let { }

    // ── internals ──────────────────────────────────────────────────────────────

    /**
     * Allowlist поисковой фразы ДО подстановки в URL: латиница, кириллица, цифры, пробел и базовая
     * пунктуация. Отсекает path/параметр-инъекции (../, ?, &, =, #) — как CITY_REGEX в Day 18.
     */
    private val QUERY_REGEX = Regex("^[A-Za-zА-Яа-яЁё0-9 .,()\\-']+$")

    /** Разрешённые языковые разделы (allowlist): ключ→поддомен. Расширяемый список. */
    private val LANGS = mapOf("en" to "en", "ru" to "ru", "de" to "de", "fr" to "fr")
}

/** Результат поиска одной статьи Wikipedia (для tool-ответа и передачи в format_report). */
internal data class WikiArticle(
    val title: String,
    val description: String?,
    val extract: String,
    val url: String?,
)

private fun defaultClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
}

/**
 * Подмножество полей REST summary (остальное игнорируется через `ignoreUnknownKeys`):
 * `extract` — plain-text выжимка статьи; `description` — короткая аннотация; `content_urls.desktop.page` —
 * канонический URL. Nullable — любые поля могут отсутствовать для disambiguation/mediawiki-страниц.
 */
@Serializable
private data class SummaryResponse(
    val title: String? = null,
    val description: String? = null,
    val extract: String? = null,
    @SerialName("content_urls") val contentUrls: ContentUrls? = null,
)

@Serializable
private data class ContentUrls(
    val desktop: DesktopUrl? = null,
)

@Serializable
private data class DesktopUrl(
    val page: String? = null,
)
