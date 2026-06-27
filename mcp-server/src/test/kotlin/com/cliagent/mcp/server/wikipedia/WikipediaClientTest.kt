package com.cliagent.mcp.server.wikipedia

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 19: WikipediaClient (search-этап пайплайна). Паттерн Day 18 WeatherClientTest: MockEngine с
 * роутингом opensearch-API ↔ rest-summary-API (различаем по host/path в URL, как geocoding↔forecast).
 */
class WikipediaClientTest {

    @Test
    fun `search resolves title via opensearch then fetches summary`() = runTest {
        val c = wikiClient(
            opensearch = """["kotlin",["Kotlin (programming language)"],[],[]]""",
            summary = """{"title":"Kotlin (programming language)","description":"programming language","extract":"Kotlin is a cross-platform...","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Kotlin_(programming_language)"}}}""",
        )
        val article = c.search("kotlin")
        assertNotNull(article)
        assertEquals("Kotlin (programming language)", article!!.title)
        assertTrue(article.extract.contains("cross-platform"))
        assertEquals("https://en.wikipedia.org/wiki/Kotlin_(programming_language)", article.url)
    }

    @Test
    fun `search returns null when opensearch finds nothing`() = runTest {
        val c = wikiClient(opensearch = """["nowhere",[],[],[]]""", summary = "")
        assertNull(c.search("nowhere"))
    }

    @Test
    fun `search returns null on opensearch HTTP error`() = runTest {
        val c = wikiClient(opensearch = "", summary = "", opensearchStatus = HttpStatusCode.InternalServerError)
        assertNull(c.search("kotlin"))
    }

    @Test
    fun `search returns null when summary has neither extract nor description`() = runTest {
        val c = wikiClient(
            opensearch = """["x",["X"],[],[]]""",
            summary = """{"title":"X"}""",
        )
        assertNull(c.search("x"))
    }

    @Test
    fun `search uses ru language section when requested`() = runTest {
        var seenHost = ""
        val http = HttpClient(MockEngine { request ->
            seenHost = request.url.host
            respond(
                """["kotlin",["Kotlin"],[],[]]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
        val c = WikipediaClient(http)
        c.search("kotlin", "ru")
        assertEquals("ru.wikipedia.org", seenHost)
    }

    @Test
    fun `query regex rejects path-like injection`() = runTest {
        val c = wikiClient(opensearch = """["x",[],[],[]]""", summary = "")
        // QUERY_REGEX отсекает ../ и спецсимволы ДО запроса
        assertNull(c.search("../etc"))
        assertNull(c.search("a;rm"))
    }

    @Test
    fun `unknown language falls back to en`() = runTest {
        var seenHost = ""
        val http = HttpClient(MockEngine { request ->
            seenHost = request.url.host
            respond(
                """["kotlin",["Kotlin"],[],[]]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
        val c = WikipediaClient(http)
        c.search("kotlin", "klingon")
        assertEquals("en.wikipedia.org", seenHost)
    }

    // ── helper: MockEngine с роутингом opensearch-API (w/api.php) ↔ summary-API (rest_v1) ──
    // opensearch: https://{lang}.wikipedia.org/w/api.php?action=opensearch...  (path = /w/api.php)
    // summary:    https://{lang}.wikipedia.org/api/rest_v1/page/summary/...   (path = /api/rest_v1...)
    // Различаем по подстроке path (encodedPath не содержит host).
    private fun wikiClient(
        opensearch: String,
        summary: String,
        opensearchStatus: HttpStatusCode = HttpStatusCode.OK,
    ): WikipediaClient {
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body: String
            val status: HttpStatusCode
            when {
                path.contains("/w/api.php") -> { body = opensearch; status = opensearchStatus }
                path.contains("/page/summary") -> { body = summary; status = HttpStatusCode.OK }
                else -> { body = ""; status = HttpStatusCode.NotFound }
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
        return WikipediaClient(http)
    }
}
