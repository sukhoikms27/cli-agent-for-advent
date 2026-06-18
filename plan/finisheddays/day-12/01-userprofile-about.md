# Задача 01. Поле `about` в UserProfile

## Цель
Добавить поле «контекст» (кто пользователь, цель) — третья группа данных лекции (стиль / constraints / контекст). Schema-safe.

## Файл (правка)
`src/main/kotlin/com/cliagent/memory/MemoryLayer.kt`

## Что изменить
```kotlin
@Serializable
data class UserProfile(
    val style: String? = null,
    val format: String? = null,
    val about: String? = null,        // день 12: контекст — кто пользователь, цель
    val constraints: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        style == null && format == null && about == null && constraints.isEmpty()
}
```
Обновить doc-комментарий (убрать «stub под Day 12» — теперь полноценная модель).

## Конвенции
- `@Serializable`, defaulted/nullable → старый JSON без `about` грузится (`about == null`).
- Эволюция схемы: только добавление с default.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `UserProfile().isEmpty() == true`; `UserProfile(about = "x").isEmpty() == false`.

## Зависимости
Нет.
