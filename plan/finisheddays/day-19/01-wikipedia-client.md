# 01 — WikipediaClient (search-этап)

> Ktor-клиент к Wikipedia REST API: произвольная фраза → статья (extract + URL). **Без API-ключа**,
> read-only — безопасен для remote-сервера (как Open-Meteo в Day 18). Источник энциклопедических
> данных для пайплайна tech-дайджеста.

## Файл

`mcp-server/src/main/kotlin/com/cliagent/mcp/server/wikipedia/WikipediaClient.kt`

## Архитектурное решение: двухшаговый search

Произвольный запрос пользователя («kotlin», «microservices») **≠** точное название статьи. Поэтому:

1. **`resolveTitle`** — MediaWiki `opensearch`: по поисковой фразе находит ближайшее валидное название
   статьи (с резолвом редиректов). Возвращает первый title или null.
2. **`summary`** — REST `page/summary/{title}`: plain-text extract + URL + описание.

Композиция в `search(query, language)`: один вызов «фраза → статья».

> Зачем два шага, а не сразу REST summary по фразе? REST `page/summary/{title}` требует **точное**
> название (с подчёркиваниями вместо пробелов). opensearch резолвит «kotlin» → «Kotlin (programming language)».

## Ключевые элементы

### Allowlist path-injection guard (до подстановки в URL)
```kotlin
private val QUERY_REGEX = Regex("^[A-Za-zА-Яа-яЁё0-9 .,()\\-']+$")
```
Отсекает `../`, `?`, `&`, `=`, `#` — path/параметр-инъекции. Тот же паттерн, что `CITY_REGEX` в Day 18.

### Разрешённые языки (allowlist)
```kotlin
private val LANGS = mapOf("en" to "en", "ru" to "ru", "de" to "de", "fr" to "fr")
```
Неизвестный язык → fallback на `en`. Поддомен берётся из map, а не прямо из пользовательского ввода.

### Обработка ошибок (конвенция проекта)
- Ошибки сети/парсинга/HTTP → `null` (handler в `WikipediaTools` решает, вернуть tool-error или fallback).
- `CancellationException` — **re-throw** перед generic `catch (e: Exception)` (AGENTS.md: никогда не глотать).

### Injected HttpClient (для тестов)
```kotlin
internal class WikipediaClient(private val http: HttpClient = defaultClient())
```
В тестах подставляется `HttpClient(MockEngine { ... })` — без реальной сети (как `WeatherClient` Day 18).

## @Serializable модель ответа

Только нужные поля, остальное игнорируется через `ignoreUnknownKeys = true` (forward-compat):
```kotlin
@Serializable
private data class SummaryResponse(
    val title: String? = null,
    val description: String? = null,
    val extract: String? = null,
    @SerialName("content_urls") val contentUrls: ContentUrls? = null,
)
```
Nullable-поля с defaults — любые поля могут отсутствовать для disambiguation/mediawiki-страниц (schema evolution).

## Результат

```kotlin
internal data class WikiArticle(
    val title: String,
    val description: String?,
    val extract: String,
    val url: String?,
)
```
Используется tool-ответом (`formatArticle`) и (через текст) передаётся в `format_report` — звено
«передача данных между инструментами».

## Критерии готовности

- [x] `./gradlew :mcp-server:compileKotlin` green.
- [x] `WikipediaClientTest` (7 тестов): opensearch→summary, не найдено→null, HTTP-ошибка→null, ru-язык, regex-guard, fallback языка — все green (см. [`05-tests.md`](./05-tests.md)).
