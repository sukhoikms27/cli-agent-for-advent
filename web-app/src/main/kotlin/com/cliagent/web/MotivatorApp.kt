package com.cliagent.web

import com.cliagent.llm.LlmCallException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.nio.charset.StandardCharsets

/**
 * День 30: точка входа веб-приложения «Мотиватор».
 *
 * Ktor Server (CIO) + cli-agent ([ContextAwareAgent] через [AgentFactory]). Развёрнут на VPS
 * `77.91.94.114:8080`, ходит в локальную Ollama на `127.0.0.1:11434` (qwen2.5:7b-instruct-q5_K_M).
 *
 * Архитектура чата: `/api/chat` — обычный POST с JSON-телом, ответ стримится как SSE через
 * [respondTextWriter] (text/event-stream). Это надёжнее, чем SSE-плагин для POST-запросов — тело
 * парсится штатным ContentNegotiation, а токены пишутся в writer по мере генерации агентом.
 *
 * env:
 *  - MOTIVATOR_PORT (default 8080) — порт веб-интерфейса
 *  - MOTIVATOR_HOST (default 0.0.0.0) — хост; 0.0.0.0 = доступ по прямому IP
 *  - MOTIVATOR_COOKIE_SECRET — HMAC-секрет для сессий (задать на VPS для персистентности)
 *  - OLLAMA_BASE_URL, MOTIVATOR_MODEL — см. [AgentFactory.fromEnv]
 */
fun main() {
    val port = (System.getenv("MOTIVATOR_PORT") ?: "8080").toInt()
    val host = System.getenv("MOTIVATOR_HOST") ?: "0.0.0.0"
    val sessionManager = SessionManager()
    val agentFactory = AgentFactory.fromEnv()

    embeddedServer(CIO, port = port, host = host) {
        motivatorModule(sessionManager, agentFactory)
    }.start(wait = true)
}

/** Все плагины + роуты. */
fun Application.motivatorModule(sessionManager: SessionManager, agentFactory: AgentFactory) {
    install(DefaultHeaders)
    install(ConditionalHeaders)
    install(Compression)
    install(PartialContent)
    install(CallLogging) { level = Level.INFO }
    install(StatusPages) {
        exception<LlmCallException> { call, cause ->
            call.respond(HttpStatusCode.BadGateway, ApiError("LLM error: ${cause.message}"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("Internal error: ${cause.message}"))
        }
    }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        })
    }

    routing {
        // Главная страница — статический index.html из resources/static/ (вся DOM в HTML,
        // без Kotlinx-HTML DSL: проще, меньше зависимостей, полный контроль над разметкой).
        get("/") {
            call.respondText(
                text = Thread.currentThread().contextClassLoader
                    .getResourceAsStream("static/index.html")!!
                    .bufferedReader().use { it.readText() },
                contentType = ContentType.Text.Html.withCharset(StandardCharsets.UTF_8),
            )
        }
        // Статика (app.js, style.css) — из resources/static/.
        staticResources(remotePath = "/static", basePackage = "static")

        // ── API ──

        route("/api") {
            // Создать анонимную сессию → Set-Cookie. Без логинов (требование задания).
            post("/session") {
                val cookieVal = sessionManager.newSessionCookieValue()
                call.response.headers.append(
                    HttpHeaders.SetCookie, sessionManager.setCookieHeader(cookieVal)
                )
                call.respond(SessionResponse(sessionId = cookieVal.substringBefore(".")))
            }

            // История чата текущей сессии (изоляция: только свои сообщения).
            get("/history") {
                val sid = call.requireSession(sessionManager) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("No valid session"))
                    return@get
                }
                call.respond(agentFactory.historyFor(sid))
            }

            // Чат: POST {message, temperature} → SSE-стрим токенов (text/event-stream).
            // EventSource (browser) умеет только GET; для POST клиент использует fetch() + ручное
            // чтение потока (см. app.js). Ответ — серия SSE-фреймов: start / token / done / error.
            post("/chat") {
                val req = call.receive<ChatRequestBody>()
                val sid = call.requireSession(sessionManager) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("No valid session"))
                    return@post
                }
                val temperature = req.temperature.coerceIn(0.0, 2.0)
                val agent = agentFactory.createFor(sid, temperature)

                // Двухкорутинная схема стриминга:
                //  1) generationJob (suspend) — chatStreamed пушит токены в Channel
                //  2) writer (respondTextWriter) — потребляет Channel, пишет SSE-фреймы
                // respondTextWriter блокирует на I/O; агент suspend — нельзя в одной корутине.
                val tokens = Channel<String>(capacity = 128)
                val generationJob = launch {
                    try {
                        agent.chatStreamed(
                            userMessage = req.message,
                            onToken = { delta -> tokens.send(delta) },
                        )
                        tokens.close() // сигнал: генерация завершена, writer выйдет из consumeEach
                    } catch (e: Throwable) {
                        tokens.close(e) // пробрасываем ошибку в writer
                    }
                }

                call.respondTextWriter(
                    contentType = ContentType.Text.EventStream.withCharset(StandardCharsets.UTF_8),
                ) {
                    writeSse("start", "{}")
                    flush()
                    val full = StringBuilder()
                    try {
                        tokens.consumeEach { delta ->
                            full.append(delta)
                            writeSse("token", Json.encodeToString(TokenChunk.serializer(), TokenChunk(delta)))
                            flush()
                        }
                        writeSse("done", Json.encodeToString(DoneEvent.serializer(), DoneEvent(full.toString())))
                    } catch (e: Throwable) {
                        writeSse("error", e.message ?: "LLM error")
                    }
                    flush()
                    generationJob.join()
                }
            }
        }
    }
}

// ── Локальный SSE-writer: пишет `event: <name>\ndata: <json>\n\n` ──
private fun java.io.Writer.writeSse(event: String, data: String) {
    write("event: $event\n")
    // data может содержать переводы строк — каждое на отдельной `data:` строке (SSE-спека).
    data.split("\n").forEach { line -> write("data: $line\n") }
    write("\n")
    flush()
}

// ── HTTP-хелперы ──

/** Извлечь sessionId из cookie, верифицировать HMAC. null → 401 в роуте. */
private fun ApplicationCall.requireSession(sessionManager: SessionManager): String? {
    val cookie = request.headers[HttpHeaders.Cookie] ?: return null
    val sidCookie = cookie.split(";")
        .map { it.trim() }
        .firstOrNull { it.startsWith("${SessionManager.COOKIE_NAME}=") }
        ?.substringAfter("=")
    return sessionManager.validate(sidCookie)
}
