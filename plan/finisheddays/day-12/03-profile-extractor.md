# Задача 03. ProfileExtractor — LLM авто-извлечение

## Цель
LLM-извлечение `UserProfile` из диалога по паттерну `StickyFactsStrategy.updateFacts`. On-demand и для авто-режима (задача 04).

## Файл (новый)
`src/main/kotlin/com/cliagent/agent/ProfileExtractor.kt`

## Что реализовать
```kotlin
class ProfileExtractor(private val llmClient: LlmClient, private val model: String) {
    suspend fun extract(history: List<ChatMessage>, current: UserProfile?): UserProfile
    fun mergeProfile(current: UserProfile?, inferred: UserProfile): UserProfile
}
```
- `extract`: по recent history (last ~10) — промпт asks вывести стиль ответов, формат, контекст пользователя/цель, ограничения. `ChatRequest(model, listOf(user msg), temperature = 0.0)`. На `LlmResult.Success` — `parseProfile(content)`. На `LlmResult.Error` — вернуть `current` (как StickyFacts «keep as-is»).
- Формат вывода LLM:
  ```
  style: кратко
  format: с примерами кода
  about: backend dev, Ktor
  constraints:
  - no RxJava
  - Kotlin only
  ```
- `parseProfile(text): UserProfile` — построчный парсинг (как `parseFacts`): `style:`/`format:`/`about:` → поля; блок `constraints:` → строки `- ...` в список.
- `mergeProfile(current, inferred)`:
  ```kotlin
  UserProfile(
      style = inferred.style ?: current?.style,
      format = inferred.format ?: current?.format,
      about = inferred.about ?: current?.about,
      constraints = ((current?.constraints ?: emptyList()) + inferred.constraints)
          .filter { it.isNotBlank() }.distinct()
  )
  ```
  Аккумулирует constraints, не затирает существующие поля если inferred пуст.

## Конвенции
- `suspend`, `withContext` не нужен (llmClient сам IO).
- `temperature = 0.0` для детерминизма.
- На ошибку LLM — graceful (вернуть current).

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `mergeProfile(UserProfile(style="a"), UserProfile(about="b", constraints=listOf("c")))` → `style="a", about="b", constraints=["c"]`.

## Зависимости
Задача 01.
