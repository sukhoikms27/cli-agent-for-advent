# День 1. Первый запрос к LLM через API

## Задание курса

Напишите минимальный код, который:
- отправляет запрос в LLM через API
- получает ответ
- выводит его в консоль или простой интерфейс (CLI / Web)

**Результат:** Код, который отправляет запрос в LLM через API и получает ответ

---

## Контекст проекта

Это первый день — проект инициализируется с нуля. Все последующие дни будут
надстраиваться поверх фундамента, заложенного здесь.

**Референс предыдущей реализации:** [llm-chat-demo-app](https://github.com/sukhoikms27/llm-chat-demo-app) — Android-клиент с аналогичной функциональностью.

---

## Что реализуем

### 1. Инициализация проекта

Создать Gradle-проект с Kotlin DSL со следующей структурой:

```
cli-agent/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── src/
    └── main/
        └── kotlin/
            └── com/
                └── cliagent/
                    └── Main.kt
```

**Зависимости (build.gradle.kts):**

```kotlin
dependencies {
    // Ktor HTTP client
    implementation("io.ktor:ktor-client-core:<version>")
    implementation("io.ktor:ktor-client-cio:<version>")
    implementation("io.ktor:ktor-client-content-negotiation:<version>")

    // kotlinx.serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:<version>")

    // kotlinx.coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:<version>")
}
```

> Версии подобрать актуальные на момент реализации. Ktor CIO engine —
> нативный неблокирующий движок, не требует дополнительных зависимостей.

### 2. Модели данных LLM

**Файлы:** `src/main/kotlin/com/cliagent/llm/model/`

#### ChatMessage.kt
```kotlin
@Serializable
data class ChatMessage(
    val role: String,      // "system" | "user" | "assistant"
    val content: String
)
```

#### ChatRequest.kt
```kotlin
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>
)
```

> На этом этапе параметры temperature, max_tokens, top_p НЕ добавляются —
> они появятся в день 4. Держим ChatRequest минимальным.

#### ChatResponse.kt
```kotlin
@Serializable
data class ChatResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: ChatMessage,
    val finishReason: String? = null
)

@Serializable
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cachedTokens: Int? = null   // [ANDROID-DIFF] z.ai возвращает cached_tokens для кешированных промптов
)
```

> Имена полей в JSON — snake_case, в Kotlin — camelCase.
> Использовать @SerialName для маппинга:
> `@SerialName("finish_reason") val finishReason: String?`
> `@SerialName("prompt_tokens") val promptTokens: Int` и т.д.

### 3. LLM-клиент

**Файлы:** `src/main/kotlin/com/cliagent/llm/`

#### LlmClient.kt
```kotlin
interface LlmClient {
    suspend fun chat(request: ChatRequest): LlmResult<ChatResponse>

    // [ANDROID-DIFF] Placeholder для Phase 2 (streaming).
    // Android реализовал SSE-стриминг через Retrofit @Streaming.
    // В CLI добавим в Phase 2, но интерфейс закладываем уже сейчас:
    // fun chatStream(request: ChatRequest): Flow<StreamChunk>
}
```

> Интерфейс обязателен — это позволяет подменять реализацию в тестах
> и добавлять новых провайдеров без изменения бизнес-логики.
> `chatStream()` будет добавлен в Phase 2 вместе с SSE-парсингом.

#### OpenAiCompatibleClient.kt

Реализация для z.ai через Ktor:

```kotlin
class OpenAiCompatibleClient(
    private val baseUrl: String,    // https://api.z.ai/api/paas/v4
    private val apiKey: String
) : LlmClient {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
        // POST $baseUrl/chat/completions
        // Header: Authorization: Bearer token
        // Header: Content-Type: application/json
        // Body: ChatRequest (serialized)
        // Return: ChatResponse (deserialized)
    }
}
```

**Важные детали реализации:**
- Endpoint: `{baseUrl}/chat/completions` (не `/v1/` — z.ai использует `/v4/`)
- API key передаётся в заголовке `Authorization: Bearer token`
- Использовать Ktor `ContentNegotiation` с `kotlinx.serialization` для автоматической сериализации/десериализации
- Обработать HTTP-ошибки: 401 (неверный ключ), 429 (rate limit), 500 (серверная ошибка)
- Для ошибок использовать sealed class результат, а не исключения:

```kotlin
sealed class LlmResult<out T> {
    data class Success<T>(val data: T) : LlmResult<T>()
    data class Error(val code: Int, val message: String) : LlmResult<Nothing>()
}
```

> **Решение:** LlmClient.chat() возвращает `LlmResult<ChatResponse>`, а не `ChatResponse`.
> Это позволит вызывающему коду обрабатывать ошибки без try/catch.

### 4. Конфигурация

**Файлы:** `src/main/kotlin/com/cliagent/config/`

#### AppConfig.kt
```kotlin
data class AppConfig(
    val apiKey: String,
    val model: String = "glm-5.1",
    val baseUrl: String = "https://api.z.ai/api/paas/v4"
)
```

#### ConfigRepository.kt

Загрузка конфигурации из переменных окружения:

```kotlin
class ConfigRepository {
    fun load(): AppConfig {
        val apiKey = System.getenv("CLI_AGENT_API_KEY")
            ?: error("CLI_AGENT_API_KEY environment variable is required")
        val model = System.getenv("CLI_AGENT_MODEL") ?: "glm-5.1"
        val baseUrl = System.getenv("CLI_AGENT_BASE_URL")
            ?: "https://api.z.ai/api/paas/v4"
        return AppConfig(apiKey = apiKey, model = model, baseUrl = baseUrl)
    }
}
```

> Пока загружаем только из env-переменных. Файловый конфиг (~/.cli-agent/config.json)
> добавим позже, когда появится потребность в более сложной конфигурации.

### 5. Точка входа

**Файл:** `src/main/kotlin/com/cliagent/Main.kt`

```kotlin
fun main() = runBlocking {
    val config = ConfigRepository().load()
    val client = OpenAiCompatibleClient(
        baseUrl = config.baseUrl,
        apiKey = config.apiKey
    )
    val request = ChatRequest(
        model = config.model,
        messages = listOf(
            ChatMessage(role = "user", content = "Привет! Представься.")
        )
    )
    when (val result = client.chat(request)) {
        is LlmResult.Success -> println(result.data.choices.first().message.content)
        is LlmResult.Error -> println("Error: ${result.code} — ${result.message}")
    }
}
```

> Это минимальный Proof-of-Concept. REPL-режим и clikt появятся в день 6.
> Пока — один запрос, один ответ, вывод в консоль.

---

## Файлы дня (итог)

| Файл | Действие | Описание |
|---|---|---|
| `build.gradle.kts` | создать | Gradle-конфигурация с зависимостями |
| `settings.gradle.kts` | создать | Имя проекта: `cli-agent` |
| `gradle.properties` | создать | Kotlin/JVM настройки |
| `src/.../Main.kt` | создать | Точка входа (PoC) |
| `src/.../llm/model/ChatMessage.kt` | создать | Модель сообщения |
| `src/.../llm/model/ChatRequest.kt` | создать | Модель запроса |
| `src/.../llm/model/ChatResponse.kt` | создать | Модель ответа + Choice + Usage |
| `src/.../llm/LlmClient.kt` | создать | Интерфейс LLM-клиента |
| `src/.../llm/OpenAiCompatibleClient.kt` | создать | Реализация для z.ai |
| `src/.../llm/LlmResult.kt` | создать | Sealed class для результатов |
| `src/.../config/AppConfig.kt` | создать | Конфигурация приложения |
| `src/.../config/ConfigRepository.kt` | создать | Загрузка конфига |

---

## На что обратить внимание

1. **@SerialName** — все JSON-поля в ответе z.ai приходят в snake_case
   (`finish_reason`, `prompt_tokens`), а в Kotlin мы используем camelCase.
   Без @SerialName десериализация упадёт.

2. **API key безопасность** — не хардкодить ключ в код. Только env-переменная.
   При запуске: `CLI_AGENT_API_KEY=xxx ./gradlew run`

3. **Ktor CIO vs Apache** — CIO легче и не тянет Apache-зависимости.
   Для CLI-инструмента это предпочтительнее.

4. **runBlocking в main** — на этом этапе допустимо. Когда появятся корутинные
   абстракции (день 6), заменим на структурированную concurrency.

5. **LlmResult вместо исключений** — с первого дня закладываем паттерн,
   который будет использоваться во всём проекте. Никаких try/catch для flow control.

---

## Критерии проверки

- [ ] `./gradlew build` собирается без ошибок
- [ ] `CLI_AGENT_API_KEY=xxx ./gradlew run` отправляет запрос к z.ai и выводит ответ
- [ ] При неверном API key выводится понятная ошибка (не stacktrace)
- [ ] Модели данных сериализуются/десериализуются корректно
- [ ] Код разделён по пакетам: `llm/model/`, `llm/`, `config/`

---

## Состояние проекта после дня 1

```
✅ Gradle-проект инициализирован
✅ Ktor HTTP-клиент работает
✅ z.ai API вызывается через OpenAI-совместимый формат
✅ Модели данных ChatMessage/ChatRequest/ChatResponse готовы
✅ LlmResult sealed class для обработки ошибок
✅ Конфигурация из env-переменных
❌ REPL-режим (день 6)
❌ Параметры LLM (день 4)
❌ История диалога (день 7)
```

---

## Что изменилось после сравнения с Android-реализацией

> Изменения на основе сравнения с [llm-chat-demo-app](https://github.com/sukhoikms27/llm-chat-demo-app).
> Подробности: `plan/changelog-android-diff.md`

| Изменение | Тип | Описание |
|---|---|---|
| `chatStream()` placeholder | NEW | Интерфейс LlmClient заложен с учётом стриминга (Phase 2). Android уже реализовал SSE через Retrofit `@Streaming` |
| `cachedTokens` в Usage | NEW | z.ai API возвращает `cached_tokens` в `prompt_tokens_details`. Android учитывает это при расчёте стоимости |
