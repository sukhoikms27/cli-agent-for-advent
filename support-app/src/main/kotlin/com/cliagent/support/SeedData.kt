package com.cliagent.support

import com.cliagent.support.tickets.Ticket
import com.cliagent.support.tickets.TicketEvent
import com.cliagent.support.tickets.TicketStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * День 33 — seed-скрипт: наполняет [TicketStore] демо-тикетами и создаёт FAQ-документы для RAG.
 *
 * Запуск: `./gradlew :support-app:run --args="seed"` (через [SupportAppMain] dispatcher) или
 * напрямую: `java -cp support-app-all.jar com.cliagent.support.SeedDataKt`.
 *
 * Создаёт:
 *  - 5 демо-тикетов (разные статусы/приоритеты) — для тестирования ticket-context.
 *  - 3 FAQ-документа в `${XDG_DATA_HOME}/cli-agent/support/docs/` — для индексации RAG.
 *
 * Идемпотент: upsert по id (тикетов) и CREATE_OR_REPLACE (файлов). Безопасно повторять.
 */
suspend fun main() {
    val ticketStore = TicketStore()
    val docsDir = TicketStore.defaultTicketsFile().parent.resolve("docs")
    Files.createDirectories(docsDir)

    // ── Демо-тиケット (демо-данные для support-agent) ──
    val now = Instant.now().toString()
    val tickets = listOf(
        Ticket(
            id = 1,
            subject = "Не могу войти в аккаунт",
            status = "open",
            priority = "high",
            customerEmail = "alice@example.com",
            customerName = "Алиса",
            description = "Ввожу правильный пароль, но получаю «неверные учётные данные». Сброс пароля не помог.",
            history = listOf(
                TicketEvent("customer", "Не могу войти, срочно нужно для работы!", now),
                TicketEvent("support", "Проверили — аккаунт активен. Запросили скриншот ошибки.", now),
            ),
        ),
        Ticket(
            id = 2,
            subject = "Не приходит чек после оплаты",
            status = "in_progress",
            priority = "medium",
            customerEmail = "bob@example.com",
            customerName = "Борис",
            description = "Оплатил подписку 3 дня назад, чек на email не пришёл. Деньги списаны.",
        ),
        Ticket(
            id = 3,
            subject = "Как экспортировать данные?",
            status = "resolved",
            priority = "low",
            customerEmail = "carol@example.com",
            customerName = "Каролина",
            description = "Хочу скачать все свои данные из системы.",
            history = listOf(
                TicketEvent("support", "Настройки → Экспорт данных → Скачать архив.", now),
                TicketEvent("customer", "Спасибо, получилось!", now),
            ),
        ),
    )
    tickets.forEach { ticketStore.upsert(it) }
    println("✓ Seeded ${tickets.size} tickets")

    // ── FAQ-документы для RAG-индексации ──
    val faqDocs = listOf(
        "faq-auth.md" to """
            # FAQ: Авторизация и вход

            ## Как восстановить пароль?
            На странице входа нажмите «Забыли пароль?». Введите email — придёт ссылка для сброса.
            Ссылка действительна 24 часа. Если письмо не пришло — проверьте папку «Спам».

            ## «Неверные учётные данные» хотя пароль правильный
            Возможные причины:
            1. Включена двухфакторная аутентификация — нужен код из приложения-аутентификатора.
            2. Аккаунт временно заблокирован после 5 неудачных попыток (разблокируется через 15 минут).
            3. Сменился домён — проверьте, что входите на правильный сайт.

            ## Как включить двухфакторную аутентификацию?
            Настройки → Безопасность → Двухфакторная аутентификация → Включить. Отсканируйте QR-код
            приложением (Google Authenticator, Authy). Сохраните резервные коды.
        """.trimIndent(),
        "faq-payments.md" to """
            # FAQ: Оплаты и биллинг

            ## Не пришёл чек после оплаты
            Чеки отправляются в течение 1 часа на email, указанный при оплате. Если чека нет дольше:
            1. Проверьте папку «Спам».
            2. Проверьте email в Настройки → Профиль.
            3. Если не помогло — создайте тикет, мы переотправим вручную.

            ## Как вернуть деньги?
            Возврат возможен в течение 14 дней с момента оплаты, если услуга не использовалась.
            Создайте тикет с темой «Возврат», укажите номер транзакции. Возврат занимает 5–10 рабочих дней.

            ## Способы оплаты
            Принимаем: банковские карты (Visa, Mastercard, МИР), СБП, ЮMoney.
            Для юридических лиц — безналичный расчёт по счёту (через отдел продаж).
        """.trimIndent(),
        "faq-data.md" to """
            # FAQ: Данные и экспорт

            ## Как экспортировать свои данные?
            Настройки → Экспорт данных → Скачать архив. Формат — JSON (совместим с GDPR).
            Архив содержит: профиль, историю заказов, загруженные файлы, настройки.

            ## Как удалить аккаунт?
            Настройки → Безопасность → Удалить аккаунт. Действие необратимо — все данные удаляются
            в течение 30 дней. Перед удалением рекомендуем экспортировать данные.

            ## Где хранятся мои данные?
            Данные хранятся на серверах в РФ (соответствие 152-ФЗ). Резервные копии — ежедневно,
            шифрованные. Доступ к данным имеют только вы и уполномоченные сотрудники поддержки.
        """.trimIndent(),
    )
    faqDocs.forEach { (name, content) ->
        Files.writeString(docsDir.resolve(name), content, Charsets.UTF_8)
    }
    println("✓ Wrote ${faqDocs.size} FAQ docs to $docsDir")
    println()
    println("Для индексации RAG выполните в CLI-агенте:")
    println("  /rag index  (после указания docsDir в config.rag.corpusRoots)")
}
