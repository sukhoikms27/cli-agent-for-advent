# Задача 19. Команда `/mode` + completer + `/help` (п.3)

## Цель
Команда `/mode [manual|plan|auto]` для просмотра/переключения режима. Геттер/сеттер через
`agent.getWorkingMemory()`. Completer и `/help` обновить.

## Зависимости
18 (`InteractionMode`), 15 (`StatefulAgent.contextAware` для аксессоров).

## Файлы (правка)
1. `src/main/kotlin/com/cliagent/cli/ChatCommand.kt` — `handleMode` (новая функция), `when`-диспетч,
   completer wiring, `/help`, заголовок с режимом.
2. `src/main/kotlin/com/cliagent/cli/ReplEngine.kt` — completer `/mode`.

## Что изменить

### 1. Диспетч в REPL-цикле (строка ~149, рядом с `/task`)

```kotlin
input.startsWith("/mode") -> handleMode(input, agent)
```

### 2. Новая функция `handleMode`

По образцу `handleTask`/`handleProfile` (parts-split + when + AppTerminal):

```kotlin
private fun handleMode(input: String, agent: StatefulAgent) {
    val parts = input.split(Regex("\\s+"))
    val base = agent.contextAware
    val w = base.getWorkingMemory() ?: WorkingMemory()
    when {
        parts.size == 1 || parts[1] == "show" -> {
            val mode = w.interactionMode
            AppTerminal.println("Interaction mode: ${mode.name.lowercase()}")
            AppTerminal.println("  manual — свободный чат; FSM только через /task")
            AppTerminal.println("  plan   — stage-поток с подтверждением переходов (default)")
            AppTerminal.println("  auto   — полная автоматизация; переходы без подтверждения")
        }
        parts[1] in listOf("manual", "plan", "auto") -> {
            val newMode = InteractionMode.valueOf(parts[1].uppercase())
            base.setWorkingMemory(w.copy(interactionMode = newMode))
            AppTerminal.ok("Interaction mode set to: ${newMode.name.lowercase()}")
        }
        else -> {
            AppTerminal.println("Usage: /mode [manual|plan|auto]")
            AppTerminal.println("Unknown mode: ${parts[1]}. Use: manual, plan, auto")
        }
    }
}
```

### 3. Completer в `ReplEngine.buildCompleter` (строки 50–85)

Добавить `ArgumentCompleter`:
```kotlin
val mode = ArgumentCompleter(
    StringsCompleter("/mode"),
    StringsCompleter("manual", "plan", "auto", "show")
)
```
И добавить `/mode` в `top` StringsCompleter (строка 51–55), и `mode` в `AggregateCompleter` (строка 85).

### 4. `/help` (printHelp)

Добавить строку:
```
/mode [manual|plan|auto]      Show or set interaction mode (FSM automation level)
```

### 5. Заголовок (задача 15, строка 125) — `Mode: $modeLabel`

Подключается теперь (с полем `interactionMode`):
```kotlin
val modeLabel = (agent.contextAware.getWorkingMemory()?.interactionMode ?: InteractionMode.PLAN).name.lowercase()
AppTerminal.println("CLI Agent v0.8 | Chat: $chatId | Model: $model | Context: ${contextManager.getStrategy().getName()} | Compress: $compressLabel | Invariants: $invariantsLabel | Mode: $modeLabel")
```

## Логика
- **`/mode` без аргумента = show** — показывает текущий режим + справку по всем трём. Удобно для
  discovery (пользователь видит варианты, не читая доку).
- **Геттер/сеттер через `WorkingMemory`** — режим хранится per-chat (задача 18). `getWorkingMemory` →
  `copy(interactionMode = ...)` → `setWorkingMemory`. Паттерн дня 13 (как `currentTask`/`taskState`).
- **`agent.contextAware`** — доступ к `ContextAwareAgent`-аксессорам (`getWorkingMemory`/
  `setWorkingMemory`) через `StatefulAgent.contextAware` (задача 12). Это НЕ интерфейс `Agent`, а
  специфичный accessor.
- **`valueOf(parts[1].uppercase())`** — маппинг `manual`→`MANUAL` (case-insensitive через pre-check
  `in listOf`). Безопасно: неизвестное значение → usage-сообщение, не исключение.

## Ключевые инварианты
- **Default `PLAN`** — `WorkingMemory().interactionMode == PLAN`; `/mode` показывает `plan`.
- **Персистентность** — `setWorkingMemory` пишет в JSON чата; режим переживает рестарт (как `taskState`).
- **`/mode show`** — явная подкоманда для display (как `/task show`); `/mode` без аргумента = алиас.
- **Не требует LLM** — переключение мгновенное, без LLM-вызова (это настройка, не задача).

## Решения
- **Подкоманды `manual|plan|auto` напрямую**, не через enum-парсинг сначала — явная валидация
  (`parts[1] in listOf(...)`) даёт понятное сообщение об ошибке для опечаток (`/mode atom` → usage).
- **`show` как подкоманда** — консистентно с `/task show`/`/invariants show`. Алиас без аргумента —
  удобство (частый случай — посмотреть текущий режим).
- **Справка в show** — пользователь сразу видит все варианты + их смысл, не нужен отдельный `/help`.
- **`Mode: $modeLabel` в заголовке** — видимая обратная связь; пользователь знает текущий режим без
  `/mode` (важно для AUTO — иначе непонятно, почему переходы происходят автоматически).

## Критерии готовности
- `/mode` → показывает текущий режим + справку.
- `/mode auto` → `Interaction mode set to: auto`, персистится.
- `/mode foo` → usage с подсказкой.
- Completer предлагает `manual`/`plan`/`auto`/`show`.
- `/help` содержит `/mode`.
- Заголовок показывает `Mode: <mode>`.
- После рестарта режим восстанавливается (schema-evolution + персистентность).

## Зависимости (задачи)
18. Используется в 20 (orchestrator mode-aware). Демо D в 25.
