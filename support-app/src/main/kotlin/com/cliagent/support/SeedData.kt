package com.cliagent.support

import com.cliagent.rag.DocumentLoader
import com.cliagent.rag.JsonRagStore
import com.cliagent.rag.RagIndexer
import com.cliagent.rag.chunk.StructuralChunker
import com.cliagent.rag.embedding.OllamaEmbeddingClient
import com.cliagent.support.tickets.Ticket
import com.cliagent.support.tickets.TicketEvent
import com.cliagent.support.tickets.TicketStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * День 33 — seed-скрипт: наполняет [TicketStore] демо-тикетами и разворачивает FAQ-документы
 * для RAG-индексации.
 *
 * **Продукт:** выдуманный мессенджер **MAX**. Корпус: 11 FAQ-файлов (65 тем) про аккаунт,
 * безопасность, сообщения, медиа, звонки, группы, биллинг, уведомления, синхронизацию,
 * платформы и решение проблем.
 *
 * **Тикеты:** 20 штук, 5 пользователей (Alice / Bob / Carol / Dmitri / Eva), разные статусы
 * и приоритеты. Покрывают основные сценарии + edge-cases:
 *  - вне FAQ (агент должен сказать «не знаю», а не галлюцинировать) — #14, #15
 *  - мультиязычный запрос (русский запрос → ответ из английского корпуса) — #17
 *  - связанные тикеты одного пользователя (#10 → #18 — продолжение) — Dmitri
 *  - эскалация с длинной историей смены статусов — #4
 *
 * Запуск:
 *  - через [supportMain] диспетчер: `./gradlew :support-app:run --args="seed"`
 *  - напрямую main: `java -cp support-app-all.jar com.cliagent.support.SeedDataKt seed`
 *
 * Аргументы (optional): `--docs-dir <path>` — переопределить директорию FAQ
 * (default: `~/.local/share/cli-agent/support/docs`). `--tickets-file <path>` — путь к файлу
 * тикетов. `--reset` — удалить существующие docs перед записью.
 *
 * Идемпотент: upsert по id (тикетов), CREATE_OR_REPLACE (файлов FAQ). Безопасно повторять.
 */
suspend fun main(args: Array<String>) {
    val opts = parseSeedArgs(args)
    seedAll(opts)
}

/** Опции seed-скрипта (парсятся из CLI args). */
data class SeedOptions(
    val docsDir: Path = TicketStore.defaultTicketsFile().parent.resolve("docs"),
    val ticketsFile: Path = TicketStore.defaultTicketsFile(),
    val resetDocs: Boolean = false,
)

/** Разбор аргументов seed-режима. */
fun parseSeedArgs(args: Array<String>): SeedOptions {
    var opts = SeedOptions()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--docs-dir" -> {
                opts = opts.copy(docsDir = Path.of(args.getOrElse(i + 1) { "" }))
                i += 2
            }
            "--tickets-file" -> {
                opts = opts.copy(ticketsFile = Path.of(args.getOrElse(i + 1) { "" }))
                i += 2
            }
            "--reset" -> {
                opts = opts.copy(resetDocs = true)
                i += 1
            }
            else -> i += 1
        }
    }
    return opts
}

/** Точка входа для вызова из [supportMain] (без повторного разбора args). */
suspend fun seedAll(opts: SeedOptions) {
    val ticketStore = TicketStore(file = opts.ticketsFile)
    seedTickets(ticketStore)
    seedFaq(opts.docsDir, opts.resetDocs)
    println()
    println("✓ Seed complete. Для индексации RAG выполните в CLI-агенте:")
    println("  /rag index  (с corpusRoots, указывающим на ${opts.docsDir})")
}

/** Написать тикеты в хранилище. */
private suspend fun seedTickets(ticketStore: TicketStore) {
    val now = Instant.now().toString()
    val yesterday = Instant.now().minusSeconds(86_400).toString()
    val weekAgo = Instant.now().minusSeconds(7 * 86_400).toString()

    val tickets = buildList {
        // ── Alice (alice@example.com) — 4 тикета ──
        add(Ticket(
            id = 1,
            subject = "Не могу войти в аккаунт",
            status = "open", priority = "high",
            customerEmail = "alice@example.com", customerName = "Алиса",
            description = "Ввожу правильный пароль, но получаю «Неверные учётные данные». " +
                "Сброс пароля через SMS не помог — код приходит, ввожу, но снова отказ.",
            history = listOf(
                TicketEvent("customer", "Срочно нужно для работы!", now),
                TicketEvent("support", "Проверили — аккаунт активен, не заблокирован. " +
                    "Возможно включена 2FA, спросили скриншот ошибки.", now),
            ),
        ))
        add(Ticket(
            id = 2,
            subject = "Не приходит чек после оплаты Premium",
            status = "in_progress", priority = "medium",
            customerEmail = "alice@example.com", customerName = "Алиса",
            description = "Оплатила Premium 3 дня назад (399 ₽, 14 июля). Чек на email не пришёл. " +
                "Папку Спам проверила, в профиле email указан верно.",
            history = listOf(
                TicketEvent("customer", "Где чек? Нужен для бухгалтерии.", now),
                TicketEvent("support", "Запросили у платёжного провайдера, ожидаем ответ.", now),
            ),
        ))
        add(Ticket(
            id = 3,
            subject = "Как экспортировать переписку?",
            status = "resolved", priority = "low",
            customerEmail = "alice@example.com", customerName = "Алиса",
            description = "Хочу скачать всю переписку с одним контактом для архива.",
            history = listOf(
                TicketEvent("support", "Откройте чат → три точки → Экспорт чата. " +
                    "Доступны JSON и PDF.", now),
                TicketEvent("customer", "Спасибо, получилось!", now),
            ),
        ))
        add(Ticket(
            id = 4,
            subject = "Эскалация: потеря доступа после смены номера",
            status = "open", priority = "urgent",
            customerEmail = "alice@example.com", customerName = "Алиса",
            description = "Сменила номер телефона, попыталась перенести аккаунт через " +
                "«Сменить номер», но новый номер уже использовался в другом (старом) аккаунте. " +
                "Теперь не могу войти ни со старого, ни с нового номера.",
            history = listOf(
                TicketEvent("customer", "Срочно! Потеряла доступ ко всем рабочим чатам.", weekAgo),
                TicketEvent("support", "Запросили фото с документом и новым номером.", weekAgo),
                TicketEvent("customer", "Прикрепила фото в тикет.", yesterday.replaceAfter("T", "10:00:00Z")),
                TicketEvent("support", "Эскалировано второму уровню (L2). Проверяем принадлежность аккаунта.", yesterday),
                TicketEvent("customer", "Когда решите? Сегодня пятница, рабочая неделя потеряна.", now),
            ),
        ))

        // ── Bob (bob@example.com) — 4 тикета ──
        add(Ticket(
            id = 5,
            subject = "Как включить двухфакторную аутентификацию?",
            status = "resolved", priority = "low",
            customerEmail = "bob@example.com", customerName = "Борис",
            description = "Хочу дополнительно защитить аккаунт 2FA.",
            history = listOf(
                TicketEvent("support", "Настройки → Конфиденциальность → 2FA → Включить. " +
                    "Придумайте пароль и резервный email.", now),
                TicketEvent("customer", "Готово, спасибо.", now),
            ),
        ))
        add(Ticket(
            id = 6,
            subject = "Удалил сообщение, но оно видно у собеседника",
            status = "in_progress", priority = "medium",
            customerEmail = "bob@example.com", customerName = "Борис",
            description = "Отправил не туда сообщение, удалил «у всех», но собеседник говорит, " +
                "что текст всё ещё виден у него. Прошло больше 48 часов — может в этом дело?",
            history = listOf(
                TicketEvent("support", "Удаление у всех работает только 48 часов после отправки. " +
                    "После этого срока сообщение удаляется только у вас.", now),
            ),
        ))
        add(Ticket(
            id = 7,
            subject = "Видео грузится очень медленно",
            status = "open", priority = "low",
            customerEmail = "bob@example.com", customerName = "Борис",
            description = "Отправляю видео 150 МБ, загрузка идёт 5+ минут. Канал 100 Мбит/с, " +
                "по тесту скорости всё ок.",
        ))
        add(Ticket(
            id = 8,
            subject = "Группе более 200 участников — что делать?",
            status = "open", priority = "medium",
            customerEmail = "bob@example.com", customerName = "Борис",
            description = "Создал рабочую группу, сейчас 195 человек, скоро будет больше 200. " +
                "Как продолжить расширяться?",
        ))

        // ── Carol (carol@example.com) — 3 тикета ──
        add(Ticket(
            id = 9,
            subject = "Перестали приходить уведомления на Android",
            status = "in_progress", priority = "high",
            customerEmail = "carol@example.com", customerName = "Каролина",
            description = "Xiaomi Redmi Note 12. Уведомления перестали приходить в фоновом режиме. " +
                "При открытии приложения — все сообщения на месте, но пока не откроешь, не знаешь.",
            history = listOf(
                TicketEvent("support", "На Xiaomi нужно добавить MAX в исключения экономии заряда " +
                    "(Настройки телефона → Приложения → MAX → Контроль активности → Без ограничений).", now),
                TicketEvent("customer", "Попробую, но почему это вообще нужно делать вручную?", now),
            ),
        ))
        add(Ticket(
            id = 10,
            subject = "Как отключить превью текста в уведомлении",
            status = "resolved", priority = "low",
            customerEmail = "carol@example.com", customerName = "Каролина",
            description = "Не хочу, чтобы в уведомлении был виден текст сообщения — конфиденциально.",
            history = listOf(
                TicketEvent("support", "Настройки → Уведомления → Показывать превью → Никогда.", now),
            ),
        ))
        add(Ticket(
            id = 11,
            subject = "Как перенести чаты с iPhone на Android?",
            status = "open", priority = "medium",
            customerEmail = "carol@example.com", customerName = "Каролина",
            description = "Перехожу с iPhone 13 на Samsung S24. Хочу перенести все чаты, медиа, " +
                "секретные чаты не критичны.",
        ))

        // ── Dmitri (dmitri@example.com) — 4 тикета, включая связанные (#18 → #10) ──
        add(Ticket(
            id = 12,
            subject = "Не приходит код подтверждения по SMS",
            status = "open", priority = "high",
            customerEmail = "dmitri@example.com", customerName = "Дмитрий",
            description = "Пытаюсь войти с нового устройства. Код по SMS не приходит уже 10 минут. " +
                "Номер российский, баланс положительный.",
        ))
        add(Ticket(
            id = 13,
            subject = "MAX Premium не активировался после оплаты",
            status = "in_progress", priority = "high",
            customerEmail = "dmitri@example.com", customerName = "Дмитрий",
            description = "Оплатил Premium вчера через СБП, деньги списались (399 ₽), но функции " +
                "не активировались. В профиле статус «Базовый».",
            history = listOf(
                TicketEvent("support", "Проверяем платеж по СБП — иногда задержка до 24 часов.", now),
            ),
        ))
        add(Ticket(
            id = 14,
            subject = "Как сделать интеграцию с Notion через webhook?",
            status = "open", priority = "low",
            customerEmail = "dmitri@example.com", customerName = "Дмитрий",
            // EDGE-CASE A: вопрос вне FAQ. У MAX нет публичного API/webhook.
            // Агент должен честно сказать «не знаю / не поддерживается», а не выдумывать инструкцию.
            description = "Хочу, чтобы новые сообщения в определённой группе автоматически " +
                "создавали задачу в Notion. Есть ли webhook или API для этого?",
        ))
        add(Ticket(
            id = 15,
            subject = "Поддерживается ли end-to-end шифрование в группах?",
            status = "open", priority = "medium",
            customerEmail = "dmitri@example.com", customerName = "Дмитрий",
            // EDGE-CASE A (частично): в FAQ есть инфо про секретные чаты (только 1-на-1),
            // но прямой ответ про группы — нет. Агент должен либо дать точный ответ на основе
            // факта «секретные чаты 1-на-1», либо честно обозначить ограничение.
            description = "Нужна защищённая переписка в рабочей группе из 5 человек. " +
                "В секретных чатах можно только 1-на-1 — это ограничение или я что-то упускаю?",
        ))

        // ── Eva (eva@example.com) — 5 тикетов, включая мультиязычный и связанный с #10 ──
        add(Ticket(
            id = 16,
            subject = "MAX вылетает при открытии настроек",
            status = "in_progress", priority = "high",
            customerEmail = "eva@example.com", customerName = "Ева",
            description = "iPhone 12, iOS 17.5. При тапе на «Настройки» приложение вылетает " +
                "мгновенно. Переустановка не помогла, другие разделы работают.",
            history = listOf(
                TicketEvent("support", "Проверьте обновление iOS до 17.5.1 — там исправлен баг " +
                    "с очисткой кэша приложений. Если не поможет, пришлите лог-файл " +
                    "(Настройки → Помощь → Отправить лог).", now),
            ),
        ))
        add(Ticket(
            id = 17,
            subject = "How do I transfer chats to a new device?",
            status = "open", priority = "medium",
            customerEmail = "eva@example.com", customerName = "Eva",
            // EDGE-CASE Б: мультиязычный запрос. Тикет на английском, корпус FAQ на русском.
            // Агент должен либо перевести и ответить по сути (опираясь на faq-sync.md),
            // либо прямо ответить на английском, но используя факты из русского FAQ.
            description = "I just got a new phone and want to move all my MAX chats. " +
                "Is there a transfer feature? The FAQ seems to be in Russian only.",
        ))
        add(Ticket(
            id = 18,
            subject = "Продолжение тикета #10 — превью всё ещё видно",
            status = "open", priority = "medium",
            customerEmail = "eva@example.com", customerName = "Ева",
            // EDGE-CASE В: связанный тикет. Тикет ссылается на тикет #10 того же пользователя.
            // Здесь же — #10 от Carol. Агент должен понять, что это другой пользователь,
            // и корректно объяснить. (Используем этот паттерн как проверку контекста.)
            description = "Я настроила «Никогда» для превью по совету из тикета #10, но " +
                "на Apple Watch текст сообщения всё равно виден. Как отключить превью на часах?",
            history = listOf(
                TicketEvent("customer", "Это продолжение тикета #10.", now),
            ),
        ))
        add(Ticket(
            id = 19,
            subject = "Как удалить аккаунт?",
            status = "resolved", priority = "low",
            customerEmail = "eva@example.com", customerName = "Ева",
            description = "Хочу удалить аккаунт полностью. Что произойдёт с моими сообщениями " +
                "у других людей?",
            history = listOf(
                TicketEvent("support", "Настройки → Аккаунт → Удалить аккаунт. Все ваши чаты и " +
                    "медиа удалятся в течение 24 часов. У других пользователей ваши сообщения " +
                    "сохранятся, но будут помечены как «удалённый аккаунт».", now),
                TicketEvent("customer", "Понятно, спасибо.", now),
            ),
        ))
        add(Ticket(
            id = 20,
            subject = "Не приходит код подтверждения (Международный номер)",
            status = "open", priority = "medium",
            customerEmail = "eva@example.com", customerName = "Ева",
            description = "Пытаюсь войти с европейским номером (+49). Код не приходит. В роуминге, " +
                "до Германии доходило раньше.",
        ))
    }

    tickets.forEach { ticketStore.upsert(it) }
    println("✓ Seeded ${tickets.size} tickets:")
    println("    Пользователи: ${tickets.map { it.customerName }.distinct().size}")
    println("    Статусы: ${tickets.groupBy { it.status }.mapValues { it.value.size }}")
    println("    Приоритеты: ${tickets.groupBy { it.priority }.mapValues { it.value.size }}")
}

/**
 * Развёртывание FAQ-документов из ресурсов в [docsDir].
 *
 * Файлы лежат в `src/main/resources/seed-faq/faq-*.md` и упаковываются в JAR. При [resetDocs]
 * существующий [docsDir] удаляется и создаётся заново. Иначе файлы CREATE_OR_REPLACE —
 * повторяемо и безопасно.
 */
private fun seedFaq(docsDir: Path, resetDocs: Boolean) {
    if (resetDocs && Files.exists(docsDir)) {
        Files.walk(docsDir)
            .sorted(Comparator.reverseOrder())
            .forEach { runCatching { Files.deleteIfExists(it) } }
    }
    Files.createDirectories(docsDir)

    val cl = Thread.currentThread().contextClassLoader
    val faqNames = SEED_FAQ_FILES
    var written = 0
    faqNames.forEach { name ->
        val content = cl.getResourceAsStream("seed-faq/$name")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: run {
            System.err.println("⚠️ FAQ resource not found: seed-faq/$name (пропуск)")
            return@forEach
        }
        val target = docsDir.resolve(name)
        Files.writeString(target, content, Charsets.UTF_8)
        written++
    }
    println("✓ Wrote $written FAQ docs to $docsDir (из ${faqNames.size} ресурсов)")
}

/** Список FAQ-файлов в ресурсах (sync с src/main/resources/seed-faq/). */
private val SEED_FAQ_FILES = listOf(
    "faq-account.md",
    "faq-security.md",
    "faq-messages.md",
    "faq-media.md",
    "faq-calls.md",
    "faq-groups.md",
    "faq-billing.md",
    "faq-notifications.md",
    "faq-sync.md",
    "faq-troubleshooting.md",
    "faq-platforms.md",
)

/**
 * Индексация FAQ-корпуса → support-specific RAG-индекс.
 *
 * Изолированный путь: `~/.local/share/cli-agent/support/rag/index.json` (НЕ общий
 * `cli-agent/rag/index.json`, который над кодом проекта dev-assistant'а — он не содержит FAQ).
 *
 * Переиспользует пайплайн корневого cli-agent: [DocumentLoader] → [StructuralChunker] →
 * [OllamaEmbeddingClient] → [RagIndexer] → [JsonRagStore]. Это один и тот же проверенный
 * код, что и в `/rag index`, просто с другим корпусом и другим файлом индекса.
 *
 * @param docsDir директория с FAQ-файлами (default: рядом с tickets.json, в `docs/`)
 * @param ragIndexFile путь выходного индекса (default: `docsDir/../rag/index.json`)
 * @param embedder embedder (default: создаётся из env `SUPPORT_RAG_EMBEDDING_*` / `OLLAMA_BASE_URL`)
 */
suspend fun indexRag(
    docsDir: Path = TicketStore.defaultTicketsFile().parent.resolve("docs"),
    ragIndexFile: Path = TicketStore.defaultTicketsFile().parent.resolve("rag").resolve("index.json"),
    embedder: OllamaEmbeddingClient = defaultSupportEmbedder(),
) {
    require(Files.exists(docsDir)) {
        "FAQ docs dir не существует: $docsDir.\nСначала запустите: ./gradlew :support-app:run --args=\"seed\""
    }

    println("▸ Индексация FAQ из $docsDir")
    println("  → индекс: $ragIndexFile")
    println("  → embedder: ${embedder.modelName}")

    val documents = DocumentLoader(corpusRoots = listOf(docsDir.toString())).load()
    if (documents.isEmpty()) {
        System.err.println("❌ В $docsDir нет .md файлов. Запустите seed: --args=\"seed --reset\"")
        return
    }
    println("  → документов: ${documents.size}")

    val chunker = StructuralChunker()
    val store = JsonRagStore(file = ragIndexFile)
    val indexer = RagIndexer(chunker = chunker, embedder = embedder, store = store)

    val startTime = System.currentTimeMillis()
    val index = indexer.index(documents) { done, total ->
        print("\r  → эмбеддинги: $done / $total")
    }
    println()
    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0

    if (index == null) {
        System.err.println("❌ Ошибка эмбеддинга — индекс не сохранён. Проверьте Ollama (${embedder.modelName}).")
        return
    }
    println("✓ Индекс создан за ${"%.1f".format(elapsed)}с: ${index.chunks.size} чанков, " +
        "${index.documents.size} документов, модель=${index.embeddingModel}")
}

/** Создать embedder из env (для [indexRag] по умолчанию). */
private fun defaultSupportEmbedder(): OllamaEmbeddingClient {
    val embeddingUrl = System.getenv("SUPPORT_RAG_EMBEDDING_URL")
        ?: System.getenv("OLLAMA_BASE_URL")
        ?: "http://127.0.0.1:11434"
    val embeddingModel = System.getenv("SUPPORT_RAG_EMBEDDING_MODEL") ?: "nomic-embed-text"
    return OllamaEmbeddingClient(baseUrl = embeddingUrl, model = embeddingModel)
}
