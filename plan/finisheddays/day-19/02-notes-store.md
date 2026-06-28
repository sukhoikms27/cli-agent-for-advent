# 02 — NotesStore (save-этап)

> Файловое хранилище заметок/отчётов: один Markdown-файл на заметку в каталоге `notes/`. **Atomic write**
> (temp + rename), slugified filename как path-injection guard. По образцу `WeatherStore` из Day 18.

## Файл

`mcp-server/src/main/kotlin/com/cliagent/mcp/server/notes/NotesStore.kt`

## Почему JSON-независимое хранение (а не JSON-обёртка как WeatherStore)?

`save_to_file` сохраняет **произвольный текст** (markdown-отчёт из `format_report`) — оборачивать его
в `{"content":"..."}` бессмысленно и мешает пользователю читать файл напрямую. Каждый `.md` — готовый
Markdown-документ. Метаданные (размер, mtime) берутся из файловой системы, без sidecar-JSON.

## Ключевые элементы

### Atomic write (temp + rename) — AGENTS.md
```kotlin
fun save(filename: String, content: String): Path = synchronized(LOCK) {
    dir.createDirectories()
    val target = fileFor(filename)
    val tmp = target.resolveSibling(".${target.fileName}.tmp")
    tmp.writeText(content)
    Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
    target
}
```
Прерывание на любом шаге не калечит рабочий файл — атомарный rename. Тот же паттерн, что `WeatherStore.writeAtomic`.

### slugify — path-injection guard
```kotlin
private fun slugify(filename: String): String =
    filename.lowercase().trim()
        .removeSuffix(".md")
        .replace(Regex("[^a-z0-9-]+"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
        .ifBlank { "note" }
```
- `Kotlin Digest 2026!` → `kotlin-digest-2026`
- `../etc/passwd` → `etc-passwd` (внутри `notes/`, не выходит за пределы)
- `Отчёт Питер` (кириллица) → `note`-подобный safe-имя (схлопывается в дефисы)
- Пустое/blank → `note`

Тот же подход, что `slugify(city)` в `WeatherStore` Day 18 — защита от path-injection перед записью.

### Потокобезопасность
MCP-tools могут вызваться конкурентно в разных http-сессиях → `synchronized(LOCK)` на public-методах
(каталог общий). Гранулярность — весь каталог: заметки мелкие, запись редкая, блокировка копеечная.

### Injected dir (для тестов)
```kotlin
internal class NotesStore(private val dir: Path = DataPaths.notesDir)
```
В тестах — `@TempDir` (как `WeatherStore(dir)` Day 18), без реального XDG-пути.

## API

| Метод | Назначение |
|---|---|
| `save(filename, content): Path` | atomic write в `notes/{slug}.md`; возвращает путь (для tool-ответа) |
| `list(): List<NoteEntry>` | метаданные сохранённых заметок (имя, размер, mtime), отсортировано по новизне |
| `read(filename): String?` | содержимое заметки по имени (с/без `.md`); null если нет |

## Критерии готовности

- [x] `./gradlew :mcp-server:compileKotlin` green.
- [x] `NotesStoreTest` (8 тестов): save/overwrite, slugify, path-injection guard (`../etc/passwd`→safe),
      blank→`note`, list sorted desc, read by name, кириллица → safe — все green (см. [`05-tests.md`](./05-tests.md)).
