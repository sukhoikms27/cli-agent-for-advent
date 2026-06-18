# Задача 02. Рендер `about` в PromptBuilder

## Цель
Поле `about` отображается в блоке профиля system prompt.

## Файл (правка)
`src/main/kotlin/com/cliagent/agent/PromptBuilder.kt` — функция `UserProfile.renderBlock()`.

## Что изменить
Добавить строку после `Format:`, перед `Constraints:`:
```kotlin
about?.let { lines.add("  About: $it") }
```

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `PromptBuilder(base, LongTermMemory(profile=UserProfile(about="backend dev")), null).build().content` содержит `About: backend dev`.
- Существующие тесты `PromptBuilderTest` не сломаны (там профиль без about — рендерится как прежде).

## Зависимости
Задача 01.
