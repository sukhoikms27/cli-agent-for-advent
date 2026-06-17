# День 7. Сохранение контекста

## Задание курса

Добавьте агенту сохранение контекста:
- храните историю диалога (messages) в JSON или SQLite
- при перезапуске агента загружайте историю обратно
- продолжайте диалог так, как будто агент не выключался

Проверьте:
- начните диалог → перезапустите приложение → продолжите диалог

**Результат:** Агент, который сохраняет и восстанавливает контекст между запусками

---

## Что уже есть (после дней 1–6)

```
✅ Agent интерфейс + SimpleAgent (история в памяти)
✅ clikt REPL: /help, /history, /reset, /exit
✅ ChatMessage, ChatRequest, ChatResponse
✅ LlmClient + OpenAiCompatibleClient
✅ AppConfig (env-переменные)
```

## Что меняется в этот день

**Второй крупный архитектурный шаг:** вводим персистентность через SQLite.
SimpleAgent хранил историю в `MutableList` — при перезапуске она терялась.
Теперь:
1. `MemoryStore` — интерфейс хранилища (абстракция над SQLite)
2. `SqliteMemoryStore` — реализация
3. `ContextAwareAgent` — агент, который использует MemoryStore
4. Идентификаторы сессий — агент помнит, какую сессию продолжать

---

## Что реализуем

### 1. Интерфейс MemoryStore

**Файл:** `src/.../memory/MemoryStore.kt` (новый)

```kotlin
interface MemoryStore {
    // История диалога
    suspend fun saveMessage(chatId: String, message: ChatMessage)
    suspend fun loadHistory(chatId: String): List<ChatMessage>
    suspend fun clearHistory(chatId: String)

    // Управление чатами
    suspend fun listChats(): List<Chat>
    suspend fun createChat(): Chat    // возвращает новый Chat с UUID
    suspend fun deleteChat(chatId: String)
}

data class Chat(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String
)
```

> `chatId` — уникальный идентификатор чата (UUID).
> Это позволяет иметь несколько независимых диалогов.
> Каждый чат имеет `title` (генерируется из первого сообщения или вручную).

### 2. SqliteMemoryStore

**Файл:** `src/.../memory/SqliteMemoryStore.kt` (новый)

```kotlin
class SqliteMemoryStore(dbPath: String) : MemoryStore {

    private val connection: Connection

    init {
        // Создать директорию если не существует
        File(dbPath).parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        createTables()
    }

    private fun createTables() {
        connection.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chats (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT 'New Chat',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    chat_id TEXT NOT NULL,
                    parent_id TEXT,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (chat_id) REFERENCES chats(id)
                )
            """)
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_messages_chat
                ON messages(chat_id, created_at)
            """)
        }
    }

    override suspend fun saveMessage(sessionId: String, message: ChatMessage) {
        // INSERT INTO messages (session_id, role, content, created_at)
        // Обновить sessions.updated_at
    }

    override suspend fun loadHistory(sessionId: String): List<ChatMessage> {
        // SELECT role, content FROM messages WHERE session_id = ? ORDER BY created_at
    }

    // ... остальные методы
}
```

> **JDBC вместо Exposed** — для минимума зависимостей JDBC достаточно.
> Exposed добавит DSL-прослойку, но для простых CRUD это оверхед.
> Если позже понадобится — рефакторинг в Exposed не сложен.

> **Путь к БД** — по умолчанию `~/.cli-agent/data.db`. Настраивается через
> `CLI_AGENT_DB_PATH` env-переменную.

> **Thread safety** — JDBC `Connection` не потокобезопасна. Для CLI-приложения
> (один поток) это ОК. Если понадобятся корутины — использовать
> `Mutex` или пул соединений.

### 3. ContextAwareAgent

**Файл:** `src/.../agent/ContextAwareAgent.kt` (новый)

```kotlin
class ContextAwareAgent(
    private val llmClient: LlmClient,
    private val memoryStore: MemoryStore,
    private val model: String,
    private val chatId: String,
    private val systemPrompt: ChatMessage = SystemPrompts.default,
    private val reasoningStrategy: ReasoningStrategy? = null
) : Agent {

    private var history = mutableListOf<ChatMessage>()
    private var loaded = false

    private suspend fun ensureLoaded() {
        if (!loaded) {
            history = memoryStore.loadHistory(chatId).toMutableList()
            loaded = true
        }
    }

    override suspend fun chat(userMessage: String): String {
        ensureLoaded()

        val lastMsgId = history.lastOrNull()?.id
        val userMsg = ChatMessage(
            role = "user",
            content = userMessage,
            parentId = lastMsgId  // связь с предыдущим сообщением
        )
        history.add(userMsg)
        memoryStore.saveMessage(chatId, userMsg)

        val messages = buildMessages()
        val request = ChatRequest(model = model, messages = messages)
        val result = llmClient.chat(request)

        val responseText = when (result) {
            is LlmResult.Success -> {
                val assistantMsg = ChatMessage(
                    role = "assistant",
                    content = result.data.choices.first().message.content,
                    parentId = userMsg.id  // ответ ссылается на запрос
                )
                history.add(assistantMsg)
                memoryStore.saveMessage(chatId, assistantMsg)
                assistantMsg.content
            }
            is LlmResult.Error -> "Error: ${result.code} — ${result.message}"
        }

        return responseText
    }

    private fun buildMessages(): List<ChatMessage> {
        val system = if (reasoningStrategy != null) {
            PromptTemplates.buildSystemMessage(reasoningStrategy)
        } else {
            systemPrompt
        }
        return listOf(system) + history.toList()
    }

    override fun getHistory(): List<ChatMessage> = history.toList()

    override suspend fun reset() {
        history.clear()
        memoryStore.clearHistory(chatId)
        loaded = true
    }
}
```

> **Ключевое отличие от SimpleAgent:**
> - Каждый `chat()` вызывает `memoryStore.saveMessage()`
> - При создании агента история загружается через `ensureLoaded()`
> - `reset()` очищает и память, и SQLite

> **Lazy loading** — `ensureLoaded()` загружает историю при первом обращении.
> Это важно: сессия может иметь сотни сообщений, и мы не хотим грузить их
> при каждом создании объекта.

### 4. Управление сессиями в CLI

**Файл:** `src/.../cli/ChatCommand.kt` — обновить

Добавить флаги для работы с сессиями:

```kotlin
class ChatCommand : CliktCommand(name = "chat") {
    private val model by option("-m", "--model").default("glm-5.1")
    private val temperature by option("-t", "--temperature").double().default(0.7)
    private val chat by option("-c", "--chat", help = "Chat ID (or 'new')").default("default")
    private val strategy by option("-r", "--strategy").default("direct")

    override fun run() = runBlocking {
        val config = ConfigRepository().load()
        val client = OpenAiCompatibleClient(config.baseUrl, config.apiKey)
        val dbPath = System.getenv("CLI_AGENT_DB_PATH")
            ?: "${System.getProperty("user.home")}/.cli-agent/data.db"
        val memoryStore = SqliteMemoryStore(dbPath)

        val chatId = if (chat == "new") {
            memoryStore.createChat().id
        } else {
            chat
        }

        val agent = ContextAwareAgent(
            llmClient = client,
            memoryStore = memoryStore,
            model = model,
            chatId = chatId,
            reasoningStrategy = ReasoningStrategy.entries.find { it.label == strategy }
        )

        println("CLI Agent v0.2 | Chat: $chatId | Model: $model")
        println("Type /help for commands, /exit to quit\n")

        // REPL-цикл (как в день 6)
        // ...
    }
}
```

### 5. Новые REPL-команды

Добавить в REPL-цикл:

```
/chats      — список сохранённых чатов
/save       — явно сохранить текущий чат (автосохранение уже есть)
```

### 6. AppConfig — расширение

**Файл:** `src/.../config/AppConfig.kt` — обновить

```kotlin
data class AppConfig(
    val apiKey: String,
    val model: String = "glm-5.1",
    val baseUrl: String = "https://api.z.ai/api/paas/v4",
    val dbPath: String = "${System.getProperty("user.home")}/.cli-agent/data.db"
)
```

### 7. Зависимость SQLite JDBC

**Файл:** `build.gradle.kts` — добавить

```kotlin
dependencies {
    implementation("org.xerial:sqlite-jdbc:<version>")
    // ... остальные
}
```

---

## Изменения в существующих файлах

| Файл | Изменение |
|---|---|
| `build.gradle.kts` | Добавить sqlite-jdbc |
| `config/AppConfig.kt` | Добавить `dbPath` |
| `cli/ChatCommand.kt` | Использовать ContextAwareAgent вместо SimpleAgent, добавить --session |
| `agent/SimpleAgent.kt` | Оставить как есть (может использоваться для тестов без БД) |

## Новые файлы

| Файл | Описание |
|---|---|
| `memory/MemoryStore.kt` | Интерфейс хранилища |
| `memory/SqliteMemoryStore.kt` | SQLite-реализация |
| `agent/ContextAwareAgent.kt` | Агент с персистентной историей |

---

## На что обратить внимание

1. **SimpleAgent остаётся** — не удалять. Он полезен для тестов и сценариев,
   где персистентность не нужна. ContextAwareAgent — развитие, не замена.

2. **JDBC + корутины** — JDBC блокирующий. В CLI-приложении это ОК,
   но формально нужно `withContext(Dispatchers.IO)`. Добавить ко всем
   методам SqliteMemoryStore:

   ```kotlin
   override suspend fun saveMessage(sessionId: String, message: ChatMessage) =
       withContext(Dispatchers.IO) {
           // JDBC calls
       }
   ```

3. **Создание директории БД** — `~/.cli-agent/` может не существовать.
   Создавать в `init` SqliteMemoryStore.

4. **Формат дат в SQLite** — использовать ISO 8601 (`Instant.toString()`).
   Это позволяет сортировать по `created_at` корректно.

5. **Обратная совместимость REPL** — после замены SimpleAgent на
   ContextAwareAgent все команды (/help, /history, /reset, /exit) должны
   работать как раньше. `/reset` теперь очищает и SQLite.

---

## Критерии проверки

- [ ] Начать диалог → написать несколько сообщений → `/exit`
- [ ] Перезапустить `./gradlew run --args="chat"` → агент помнит прошлые сообщения
- [ ] `--chat new` создаёт новый чат
- [ ] `--chat <id>` продолжает конкретный чат
- [ ] `/chats` показывает список сохранённых чатов
- [ ] `/reset` очищает историю в SQLite
- [ ] При первом запуске (нет БД) — создаётся автоматически

---

## Состояние проекта после дня 7

```
✅ Всё из дней 1–6
✅ MemoryStore интерфейс + SqliteMemoryStore
✅ ContextAwareAgent — агент с персистентной историей
✅ Управление сессиями (create/list/resume)
✅ Автосохранение при каждом сообщении
✅ Восстановление контекста при перезапуске
❌ Подсчёт токенов (день 8)
❌ Сжатие истории (день 9)
```

---

## Что изменилось после сравнения с Android-реализацией

> Подробности: `plan/changelog-android-diff.md`

| Изменение | Тип | Описание |
|---|---|---|
| `parentId` в модели сообщения | NEW | Android использует `parentId` для формирования дерева сообщений. **Критично для BranchingStrategy (день 10)** — без parentId ветвление будет хрупким |
| Система миграций БД | NEW | Android прошёл через 7 миграций Room. CLI нужен механизм миграций, т.к. схема расширяется каждый день |
| `sessions` → `chats` | CHANGED | Android называет таблицу `chats` и хранит title, createdAt, updatedAt. Это удобнее для UI и CLI |
| UUID вместо AUTOINCREMENT | CHANGED | Android использует текстовые UUID для ID сообщений. Нужно для parentId-ссылок между сообщениями |
| `id` в ChatMessage | NEW | Каждое сообщение должно иметь уникальный id (UUID) для parentId-ссылок |

### parentId в модели сообщения (добавить)

```kotlin
@Serializable
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),  // [NEW] UUID
    val role: String,
    val content: String,
    val parentId: String? = null  // [NEW] для дерева сообщений (день 10 — Branching)
)
```

SQL-схема `messages`:
```sql
CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,           -- [CHANGED] UUID вместо AUTOINCREMENT
    session_id TEXT NOT NULL,
    parent_id TEXT,                -- [NEW] ссылка на родительское сообщение
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES chats(id)
)
```

> **Почему parentId уже в день 7:** Без этого поля в день 10 придётся писать
> миграцию, которая не сможет корректно заполнить parentId для существующих
> сообщений. Добавив сразу, мы гарантируем, что все сообщения ссылаются
> правильно: первое сообщение = parentId null, каждое последующее = parentId предыдущего.

### Модель чата (sessions → chats)

```kotlin
// [CHANGED] sessions → chats с расширенными полями
data class Chat(
    val id: String,           // UUID
    val title: String,        // тема чата (генерируется из первого сообщения или вручную)
    val createdAt: String,    // ISO 8601
    val updatedAt: String     // ISO 8601, обновляется при каждом сообщении
)
```

SQL:
```sql
CREATE TABLE IF NOT EXISTS chats (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL DEFAULT 'New Chat',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
)
```

### Система миграций БД (добавить)

**Файл:** `src/.../memory/DatabaseMigrations.kt` (новый)

```kotlin
/**
 * Инкрементальные миграции БД.
 * [ANDROID-DIFF] Аналог Migrations.kt в Android (7 миграций Room).
 * Каждое расширение схемы — новая миграция.
 */
object DatabaseMigrations {
    private val migrations = sortedMapOf<Int, (java.sql.Connection) -> Unit>()

    fun migrate(connection: java.sql.Connection, fromVersion: Int, toVersion: Int) {
        for (version in (fromVersion + 1)..toVersion) {
            migrations[version]?.invoke(connection)
        }
    }

    init {
        // Пример будущих миграций:
        // migrations[2] = { db -> db.createStatement().execute("ALTER TABLE messages ADD COLUMN usage_json TEXT") }
        // migrations[3] = { db -> db.createStatement().execute("CREATE TABLE IF NOT EXISTS summaries (...)") }
    }
}
```

> Хранить версию БД в `PRAGMA user_version`. При запуске проверять и применять
> миграции последовательно. Это стандартный подход для SQLite.
