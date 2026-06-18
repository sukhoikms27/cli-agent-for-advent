# Задача 08. Таб-completion для /task

## Цель
Tab-дополнение подкоманд `/task` и имён стадий.

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ReplEngine.kt`

## Что изменить

### 1. В `buildCompleter()` — добавить `/task` в `top` StringsCompleter
```kotlin
val top = StringsCompleter(
    "/exit", "/help", "/history", "/chats", "/stats", "/cost",
    "/summary", "/compress", "/facts", "/reset",
    "/strategy", "/branch", "/memory", "/profile", "/task"
)
```

### 2. Добавить `task` ArgumentCompleter (рядом с `profile`)
```kotlin
val task = ArgumentCompleter(
    StringsCompleter("/task"),
    StringsCompleter("show", "start", "next", "set", "step", "expect", "plan", "back", "done", "reset"),
    StringsCompleter("clarify", "planning", "execution", "validation", "done")
)
```

### 3. Включить в AggregateCompleter
```kotlin
return AggregateCompleter(top, strategy, branch, memory, profile, task)
```

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- Tab после `/task ` дополняет подкоманды; после `/task set ` — имена стадий.

## Зависимости
Задача 07.
