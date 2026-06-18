# Задача 02. AppPaths.replHistoryFile

## Цель
Путь для персистентной истории JLine (между сессиями).

## Файл (правка)
`src/main/kotlin/com/cliagent/config/AppPaths.kt`

## Что добавить
```kotlin
val replHistoryFile: Path get() = dataDir.resolve("repl-history")
```

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- Путь XDG-compliant (`$dataDir/repl-history`).

## Зависимости
Задача 01.
