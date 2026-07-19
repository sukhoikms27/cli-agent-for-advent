package com.cliagent.support.tools

import com.cliagent.agent.ToolExecutor
import com.cliagent.llm.model.FunctionDef
import com.cliagent.llm.model.ToolDefinition
import com.cliagent.support.tickets.Ticket
import com.cliagent.support.tickets.TicketEvent
import com.cliagent.support.tickets.TicketStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * День 33 — in-process [ToolExecutor], дающий support-агенту инструмент `create_ticket` для
 * автоматического заведения тикетов в [TicketStore].
 *
 * **Мотивация.** Цель дня 33 — максимально автоматизировать саппорт. Если ответа на вопрос
 * пользователя нет в FAQ (RAG ничего не нашёл) и проблема требует эскалации — агент **сам**
 * заводит тикет и сообщает пользователю номер. Это убирает ручной шаг оператора.
 *
 * **In-process vs MCP.** Этот executor — НЕ MCP-сервер, а прямая реализация [ToolExecutor].
 * Причина: `TicketStore` живёт в том же процессе, что и агент (передаётся через DI), запускать
 * отдельный subprocess MCP-сервер ради одной тулзы — избыточно. Минус: нет `confirmCreate`
 * callback'а к пользователю (как у FileToolExecutor.confirmWrite), но это сознательный выбор
 * — full autonomy (см. AGENTS.md, день 33 plan: «полная автономия, без подтверждений»).
 *
 * **Side-effect:** [call] пишет в [TicketStore] через [TicketStore.upsert]. Это необратимая
 * операция — тикет реально создаётся. В демо это нормально; в production стоит добавить
 * confirm-callback (как FileToolExecutor.confirmWrite) и/или rate-limit.
 *
 * **Stability contract:**
 * - Любая ошибка (отсутствует обязательный аргумент, невалидный priority, store-ошибка) →
 *   return строки с `Error: ...` (не exception). LLM видит ошибку и может самокорректироваться
 *   или сообщить пользователю.
 * - `CancellationException` пробрасывается (конвенция AGENTS.md).
 *
 * @param store целевое хранилище тикетов (общий с [com.cliagent.support.SupportAgentFactory]).
 */
class TicketToolExecutor(
    private val store: TicketStore,
) : ToolExecutor {

    override suspend fun definitions(): List<ToolDefinition> = listOf(createTicketDef())

    override suspend fun call(name: String, args: Map<String, Any?>): String = when (name) {
        "create_ticket" -> createTicket(args)
        else -> "Unknown tool: $name. Available: create_ticket."
    }

    /** No-op: [TicketStore] управляется снаружи (через [com.cliagent.support.SupportAgentFactory]). */
    override suspend fun close() = Unit

    /**
     * Создать тикет. Обязателен только `subject`; остальные поля — опциональны с defaults.
     *
     * @return строка для LLM: либо «✓ Создан тикет #N» (LLM сообщит номер пользователю),
     *   либо «Error: ...» для самокоррекции.
     */
    private suspend fun createTicket(args: Map<String, Any?>): String {
        val subject = args.strArg("subject")?.takeIf { it.isNotBlank() }
            ?: return "Error: subject required (non-empty). Cannot create ticket without a subject."
        val description = args.strArg("description").orEmpty()
        val priority = args.strArg("priority")?.let { normalizePriority(it) } ?: "medium"
        val customerEmail = args.strArg("customerEmail").orEmpty()

        // Id генерится как max(existing)+1. Race-condition на параллельных запросах игнорируем
        // (см. plan: «для MVP acceptable, низкая нагрузка в демо»).
        val nextId = (store.all().maxOfOrNull { it.id } ?: 0) + 1
        val now = Instant.now().toString()
        val ticket = Ticket(
            id = nextId,
            subject = subject,
            status = "open",
            priority = priority,
            customerEmail = customerEmail,
            description = description,
            history = listOf(
                TicketEvent(
                    author = "agent",
                    text = "Тикет создан автоматически support-агентом: не найден ответ в FAQ.",
                    at = now,
                ),
            ),
        )
        return try {
            store.upsert(ticket)
            // Сообщение для LLM — модель сама включит номер в свой ответ пользователю.
            "✓ Создан тикет #$nextId: \"$subject\" (status=open, priority=$priority). " +
                "Сообщи пользователю номер тикета и что специалист свяжется с ним."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            "Error: не удалось создать тикет: ${e.message}. Предложи пользователю написать позже."
        }
    }

    /** Нормализация priority: принимает low/medium/high/urgent (case-insensitive), иначе → medium. */
    private fun normalizePriority(s: String): String? {
        val lower = s.trim().lowercase()
        return if (lower in VALID_PRIORITIES) lower else null
    }

    /** Строковый аргумент с trim'ом и null'ом для пустых значений. */
    private fun Map<String, Any?>.strArg(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }?.trim()

    private fun createTicketDef(): ToolDefinition = ToolDefinition(
        function = FunctionDef(
            name = "create_ticket",
            description = (
                "Создать новый тикет поддержки в системе. ВЫЗЫВАЙ КОГДА:\n" +
                    "- ответа нет в retrieved context (FAQ/документация не помогли)\n" +
                    "- проблема требует эскалации специалисту (платёжные, индивидуальные настройки, баги)\n" +
                    "- пользователь явно просит «передать специалисту» / «создать обращение» / «пожаловаться»\n" +
                    "НЕ ВЫЗЫВАЙ если есть готовый ответ в retrieved context — в этом случае просто ответь пользователю.\n" +
                    "После создания тулзы обязательно сообщи пользователю номер тикета."
                ),
            parameters = buildJsonObject {
                put("type", "object")
                // ВАЖНО: поля должны быть внутри "properties", не на верхнем уровне!
                // Иначе z.ai возвращает 1210 "Invalid API parameter" — JSON Schema требует
                // именно {type:object, properties:{...}, required:[...]}.
                putJsonObject("properties") {
                    putJsonObject("subject") {
                        put("type", "string")
                        put("description", "Краткое описание проблемы (1 строка, 5-80 символов). Обязательное поле. " +
                            "Сформулируй сами на основе сообщения пользователя, не используй слова пользователя дословно.")
                    }
                    putJsonObject("description") {
                        put("type", "string")
                        put("description", "Полное описание проблемы со всеми деталями из сообщения пользователя.")
                    }
                    putJsonObject("priority") {
                        put("type", "string")
                        put("description", "Приоритет: low, medium, high, urgent. Default: medium.")
                    }
                    putJsonObject("customerEmail") {
                        put("type", "string")
                        put("description", "Email пользователя (если упомянут в диалоге).")
                    }
                }
                // required явно — модель не должна вызывать тулзу без subject.
                putJsonArray("required") { add("subject") }
            } as JsonElement,
        )
    )

    private companion object {
        val VALID_PRIORITIES = setOf("low", "medium", "high", "urgent")
    }
}
