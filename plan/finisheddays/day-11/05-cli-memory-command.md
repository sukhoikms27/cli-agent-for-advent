# Задача 05. CLI-команда /memory

## Цель
Сделать «явный выбор что и куда сохраняется» буквальным и проверяемым: slash-команда для инспекции и сохранения по слоям.

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ChatCommand.kt`

## Что изменить

### 1. Диспетч `when` (рядом с `/branch`, ~строка 106)
```kotlin
input.startsWith("/memory") -> handleMemory(input, agent)
```

### 2. `handleMemory(input, agent)` — новый suspend-метод
Парсит `parts = input.trim().split("\\s+".toRegex())`, работает через аксессоры агента (задача 04). Поверхность:
```
/memory                            — сводка по всем слоям
/memory show short                 — текущая история (agent.getHistory())
/memory show working               — поля WorkingMemory
/memory show long                  — knowledge, decisions, profile
/memory save working task <text>   — set currentTask
/memory save working plan <text>   — set plan
/memory save working note <text>   — append to scratchNotes
/memory save working decision <text> — append to taskDecisions
/memory save long knowledge <key> <text>  — add/update knowledge[key]; "" → remove
/memory save long decision <key> <text>   — add/update decisions[key]; "" → remove
/memory clear working              — очистить рабочую память этого чата
/memory clear long                 — очистить ВСЮ долгосрочную память (warning: global)
```
Реализация save: прочитать текущий слой через `agent.getWorkingMemory()/getLongTermMemory()`, мутировать копию, записать через `agent.setWorkingMemory()/setLongTermMemory()`. `<text>` — всё после ключевого слова (не один токен).

### 3. `printHelp()` — секция `/memory`
Добавить блок с перечисленными подкомандами.

### 4. `/reset` сообщение (~строка 109)
```kotlin
echo("History, summary, facts, branches, working memory cleared.")
```

### 5. Баннер (~строка 85) — опционально
Добавить счётчики long-term (`| LT: N knowledge, M decisions`) для верификации global слоя.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `/memory` без аргументов показывает сводку.
- `/memory save long knowledge stack Kotlin` → `/memory show long` показывает запись; `loadLongTermMemory()` возвращает её.
- `/memory clear long` печатает warning и очищает.
- `/reset` сообщение упоминает working memory.

## Зависимости
Задача 04 (аксессоры агента).
