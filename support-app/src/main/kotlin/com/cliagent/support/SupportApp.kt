package com.cliagent.support

import com.cliagent.llm.LlmCallException
import com.cliagent.support.tickets.Ticket
import com.cliagent.support.tickets.TicketStore
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
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
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
import java.nio.file.Path

/**
 * День 33 — точка входа веб-приложения «AI Support Agent».
 *
 * Ktor Server (CIO) + cli-agent ([ContextAwareAgent] через [SupportAgentFactory] с RAG + tickets).
 * Зеркало web-app/MotivatorApp.kt по архитектуре, но с поддержк-специфичной логикой:
 *  - `/api/chat` принимает опциональные `ticketId` / `customerEmail` → контекст тикета в промпте.
 *  - `/api/tickets` — список тикетов (CRUD-минимум: GET list, POST create, GET by id).
 *
 * **Диспетчер режимов** (первый CLI-аргумент):
 *  - `seed [--reset] [--docs-dir <path>]` — заполнить демо-данные (20 тикетов + 11 FAQ),
 *    см. [seedAll]. Выход после завершения.
 *  - `index` — индексировать FAQ-корпус в support-specific RAG-индекс (см. [indexRag]).
 *    Требует локальную Ollama с `nomic-embed-text`. Выход после завершения.
 *  - `server` или без аргументов — старт веб-сервера (по умолчанию).
 *
 * env:
 *  - SUPPORT_PORT (default 8081) — порт (отличается от motivator 8080 для совместного деплоя)
 *  - SUPPORT_HOST (default 0.0.0.0)
 *  - SUPPORT_COOKIE_SECRET — HMAC-секрет (задать на VPS)
 *  - см. [SupportAgentFactory.fromEnv] для LLM/RAG env-vars
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()?.lowercase()) {
        "seed" -> kotlinx.coroutines.runBlocking { seedAll(parseSeedArgs(args.drop(1).toTypedArray())) }
        "index" -> kotlinx.coroutines.runBlocking { indexRag() }
        else -> startServer() // `server` или без аргументов
    }
}

/** Запуск Ktor-сервера (вынесено из [main] для читаемости). */
private fun startServer() {
    val port = (System.getenv("SUPPORT_PORT") ?: "8081").toInt()
    val host = System.getenv("SUPPORT_HOST") ?: "0.0.0.0"
    val sessionManager = SessionManager()
    val agentFactory = SupportAgentFactory.fromEnv()
    val ticketStore = TicketStore()

    embeddedServer(CIO, port = port, host = host) {
        supportModule(sessionManager, agentFactory, ticketStore)
    }.start(wait = true)
}

/** Все плагины + роуты. */
fun Application.supportModule(
    sessionManager: SessionManager,
    agentFactory: SupportAgentFactory,
    ticketStore: TicketStore,
) {
    install(DefaultHeaders)
    install(Compression)
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
        // Главная — простой HTML (inline, без отдельного static-каталога для MVP дня 33).
        get("/") {
            call.respondText(
                text = supportIndexHtml(),
                contentType = ContentType.Text.Html.withCharset(StandardCharsets.UTF_8),
            )
        }

        route("/api") {
            // Создать анонимную сессию → Set-Cookie.
            post("/session") {
                val cookieVal = sessionManager.newSessionCookieValue()
                call.response.headers.append(
                    HttpHeaders.SetCookie, sessionManager.setCookieHeader(cookieVal)
                )
                call.respond(SessionResponse(sessionId = cookieVal.substringBefore(".")))
            }

            // История чата сессии.
            get("/history") {
                val sid = call.requireSession(sessionManager) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("No valid session"))
                    return@get
                }
                call.respond(agentFactory.historyFor(sid))
            }

            // Чат: POST {message, ticketId?, customerEmail?} → SSE-стрим токенов.
            //
            // День 33: ticketId/email могут быть переданы ЯВНО (в JSON-теле, для API-клиентов)
            // либо извлечены из текста сообщения через TicketFieldExtractor (для UI-юзеров,
            // которые пишут «что с моим тикетом 5?»). Явные поля в приоритете.
            post("/chat") {
                val req = call.receive<SupportRequest>()
                val sid = call.requireSession(sessionManager) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("No valid session"))
                    return@post
                }

                // LLM-extraction: если явные поля не заданы — пробуем достать из текста.
                // Soft-degradation: при ошибке/пустом результате → null, агент ответит без контекста.
                val effectiveTicketId: Int?
                val effectiveEmail: String?
                if (req.ticketId != null || req.customerEmail != null) {
                    // Явные поля в приоритете — extraction не нужен (экономим LLM-вызов).
                    effectiveTicketId = req.ticketId
                    effectiveEmail = req.customerEmail
                } else {
                    val extracted = agentFactory.extractor.extract(req.message)
                    effectiveTicketId = extracted.ticketId
                    effectiveEmail = extracted.customerEmail
                }

                val agent = agentFactory.createFor(sid, effectiveTicketId, effectiveEmail)

                val tokens = Channel<String>(capacity = 128)
                val generationJob = launch {
                    try {
                        agent.chatStreamed(
                            userMessage = req.message,
                            onToken = { delta -> tokens.send(delta) },
                        )
                        tokens.close()
                    } catch (e: Throwable) {
                        tokens.close(e)
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

            // ── Tickets CRUD-минимум (день 33: агент учитывает контекст тикета) ──

            // Список всех тикетов (краткий — id/subject/status/priority).
            get("/tickets") {
                val tickets = ticketStore.all().map {
                    TicketSummary(it.id, it.subject, it.status, it.priority)
                }
                call.respond(tickets)
            }

            // Детали тикета по id (полная информация + история).
            get("/tickets/{id}") {
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid ticket id"))
                    return@get
                }
                val ticket = ticketStore.find(id)
                if (ticket == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("Ticket $id not found"))
                } else {
                    call.respond(ticket)
                }
            }

            // Создать тикет (seed для демо; в проде — из формы обращения).
            post("/tickets") {
                val ticket = call.receive<Ticket>()
                ticketStore.upsert(ticket)
                call.respond(ticket)
            }
        }
    }
}

// ── Локальный SSE-writer (копия из web-app/MotivatorApp.kt) ──
private fun java.io.Writer.writeSse(event: String, data: String) {
    write("event: $event\n")
    data.split("\n").forEach { line -> write("data: $line\n") }
    write("\n")
    flush()
}

// ── HTTP-хелперы (копия из web-app) ──
private fun ApplicationCall.requireSession(sessionManager: SessionManager): String? {
    val cookie = request.headers[HttpHeaders.Cookie] ?: return null
    val sidCookie = cookie.split(";")
        .map { it.trim() }
        .firstOrNull { it.startsWith("${SessionManager.COOKIE_NAME}=") }
        ?.substringAfter("=")
    return sessionManager.validate(sidCookie)
}

/** Минимальный HTML-интерфейс support-app (inline, без отдельной статики для MVP). */
private fun supportIndexHtml(): String = """
    <!DOCTYPE html>
    <html lang="ru">
    <head>
        <meta charset="UTF-8">
        <title>AI Support Agent</title>
        <style>
            body { font-family: system-ui, sans-serif; max-width: 800px; margin: 2rem auto; padding: 0 1rem; }
            .chat { border: 1px solid #ddd; border-radius: 8px; padding: 1rem; height: 400px; overflow-y: auto; margin: 1rem 0; }
            .msg { margin: 0.5rem 0; padding: 0.5rem; border-radius: 4px; }
            .user { background: #e3f2fd; text-align: right; }
            .bot { background: #f5f5f5; }
            input[type=text] { width: 70%; padding: 0.5rem; }
            button { padding: 0.5rem 1rem; cursor: pointer; }
        </style>
    </head>
    <body>
        <h1>🛟 AI Support Agent</h1>
        <p>Задайте вопрос в свободной форме. Агент сам поймёт, если вы упомянете тикет или email,
           и при необходимости заведёт новый тикет автоматически.</p>
        <div class="chat" id="chat"></div>
        <input type="text" id="msg" placeholder="Например: что с моим тикетом 5?" onkeydown="if(event.key==='Enter')send()">
        <button onclick="send()">Отправить</button>

        <script>
            let sid = null;
            async function init() {
                const r = await fetch('/api/session', { method: 'POST' });
                const data = await r.json();
                sid = data.sessionId;
            }
            async function send() {
                const msg = document.getElementById('msg').value.trim();
                if (!msg) return;
                document.getElementById('msg').value = '';
                addMsg('user', msg);
                const botDiv = addMsg('bot', '');
                const body = { message: msg };
                const resp = await fetch('/api/chat', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const reader = resp.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop();
                    for (const line of lines) {
                        if (line.startsWith('data: ')) {
                            try {
                                const data = JSON.parse(line.slice(6));
                                if (data.token) botDiv.textContent += data.token;
                            } catch (e) {}
                        }
                    }
                }
            }
            function addMsg(role, text) {
                const div = document.createElement('div');
                div.className = 'msg ' + role;
                div.textContent = text;
                document.getElementById('chat').appendChild(div);
                document.getElementById('chat').scrollTop = 999999;
                return div;
            }
            init();
        </script>
    </body>
    </html>
""".trimIndent()
