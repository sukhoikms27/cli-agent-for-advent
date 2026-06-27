# 03 — Регистрация tools + wiring

> 4 новых MCP-tools (`search_wikipedia` / `format_report` / `save_to_file` / `list_notes`),
> точечная правка `DataPaths.kt` (`+notesDir`) и `McpServerFactory.kt` (регистрация). Кульминация
> Day 19: сервер становится 11-tool, клиент **не трогается** (кроме лога — см. [`04`](./04-agent-tool-logging.md)).

## Файлы

| Файл | Изменение |
|---|---|
| `mcp-server/.../tools/WikipediaTools.kt` | **NEW** — `search_wikipedia` |
| `mcp-server/.../tools/NotesTools.kt` | **NEW** — `format_report` + `save_to_file` + `list_notes` |
| `mcp-server/.../util/DataPaths.kt` | **+1 строка** — `notesDir` |
| `mcp-server/.../McpServerFactory.kt` | **MODIFY** — 2 параметра + 2 строки регистрации |
| `mcp-server/.../McpServerApp.kt` | **MODIFY** — создание singleton'ов + проброс в runStdio/runHttp |

## tool #1: `search_wikipedia` (search-этап)

```kotlin
server.addTool(
    name = "search_wikipedia",
    description = "Найти статью в Wikipedia по теме/фразе и вернуть краткое описание (extract) и ссылку...",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("query") { put("type","string"); put("description","Тема или поисковая фраза...") }
            putJsonObject("language") {
                put("type","string"); put("default","en")
                put("enum", buildJsonArray { add(JsonPrimitive("en")); add(JsonPrimitive("ru")) })
            }
        },
        required = listOf("query"),
    ),
) { req -> handleSearchWikipedia(req, client) }
```

> **Подводный камень kotlinx.serialization:** `put("enum", "en", "ru")` НЕ работает — `put(key, JsonElement)`
> принимает ровно одно `JsonElement`, а не vararg. Правильно: `put("enum", buildJsonArray { add(JsonPrimitive(...)); ... })`.
> Решено в ходе реализации (первая попытка компиляции упала именно на этом).

Ответ: `formatArticle(article)` → текст, который LLM читает и передаёт в `format_report`.

## tool #2: `format_report` (process-этап, детерминированный)

```kotlin
server.addTool(
    name = "format_report",
    description = "Собрать структурированный Markdown-отчёт из заголовка и набора текстовых блоков...",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("title") { put("type","string"); ... }
            putJsonObject("sections") {
                put("type","array"); put("description","Текстовые блоки отчёта...")
                putJsonObject("items") { put("type","string") }
            }
        },
        required = listOf("title", "sections"),
    ),
) { req -> handleFormatReport(req) }
```

**Чистая функция `buildReport`** (вынесена из handler'а для unit-теста — аналог `aggregate()` Day 18):
```kotlin
internal fun buildReport(title: String, sections: List<String>, date: LocalDate): String {
    require(sections.isNotEmpty()) { "buildReport requires non-empty sections" }
    return buildString {
        appendLine("# $title"); appendLine(); appendLine("_${date.format(DATE_FMT)}_"); appendLine()
        sections.forEachIndexed { i, block ->
            appendLine("## Раздел ${i + 1}"); appendLine(); appendLine(block.trim()); appendLine()
        }
    }.trimEnd()
}
```
Дата — **параметр** (не `LocalDate.now()` в теле), чтобы тест был детерминированным. **Не LLM-суммаризация**
(по уточнению куратора) — сервер не зависит от LLM.

## tool #3: `save_to_file` (save-этап)

```kotlin
server.addTool(
    name = "save_to_file",
    description = "Сохранить текстовый результат (например, отчёт из format_report) в файл в каталоге заметок...",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("filename") { put("type","string"); put("description","Имя без расширения, напр. 'kotlin-digest'") }
            putJsonObject("content") { put("type","string"); put("description","Текст для сохранения") }
        },
        required = listOf("filename", "content"),
    ),
) { req -> handleSaveToFile(req, store) }
```
Handler делегирует в `NotesStore.save` (atomic write + slugify). Возвращает путь для tool-ответа.

## tool #4: `list_notes` (доп., персистентность)

```kotlin
server.addTool(
    name = "list_notes",
    description = "Показать список сохранённых заметок/отчётов (имя, размер, дата изменения)...",
    inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
) { _ -> handleListNotes(store) }
```
Демонстрирует, что данные пережили перезапуск сервера (`notes/` персистентна в XDG).

## DataPaths.kt — +1 строка

```kotlin
/** Markdown-заметки/отчёты из пайплайна (Day 19 — save_to_file). */
val notesDir: Path get() = dataDir.resolve("notes")
```
Путь: `$XDG_DATA_HOME/cli-agent/notes/` (default `~/.local/share/cli-agent/notes/`).

## McpServerFactory.kt — регистрация

Фабрика получила 2 новых параметра и 2 строки регистрации:
```kotlin
internal fun buildServer(
    githubToken: String?,
    weatherClient: WeatherClient,
    weatherStore: WeatherStore,
    weatherScheduler: WeatherScheduler,
    wikipediaClient: WikipediaClient,   // NEW
    notesStore: NotesStore,             // NEW
): Server {
    ...
    registerGitHubTools(server, githubToken)
    registerWeatherTools(server, weatherClient, weatherStore, weatherScheduler)
    registerWikipediaTools(server, wikipediaClient)   // NEW
    registerNotesTools(server, notesStore)             // NEW
    return server
}
```

## McpServerApp.kt — wiring singleton'ов

В `main()` создаются shared singleton'ы на процесс (как погодные в Day 18) и пробрасываются в `runStdio`/`runHttp`:
```kotlin
val wikipediaClient = WikipediaClient()
val notesStore = NotesStore()
```
Оба режима (stdio/http) получают их через обновлённые сигнатуры. В http-режиме `buildServer` строит
свежий `Server` на соединение, но клиент/store — shared (один каталог `notes/` на процесс).

## Критерии готовности

- [x] `./gradlew :mcp-server:compileKotlin` green (первая попытка упала на `put("enum",...)` — исправлено).
- [x] Raw JSON-RPC `tools/list` → **11 tools** (7 Day 17–18 + 4 новых).
- [x] Schema новых tools корректна (см. дамп `tools/list` в [`06-verification.md`](./06-verification.md)).
