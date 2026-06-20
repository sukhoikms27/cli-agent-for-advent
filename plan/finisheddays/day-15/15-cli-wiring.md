# Задача 15. Wiring `StatefulAgent` в `ChatCommand` (Этап B)

## Цель
Заменить анонимный wiring `chatAgent = if (invariantsEnabled) InvariantGuard(...) else agent` на
`StatefulAgent`, и подключить защищённый `chat`-провайдер к оркестратору. Бамп версии v0.7→v0.8.

## Зависимости
10 (`/task set --force`), 12 (`StatefulAgent`), 14 (orchestrator `chat`-провайдер).

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ChatCommand.kt` — блок wiring (строки 97–125) + заголовок (125).

## Что изменить

**Сейчас** (строки 97–121):
```kotlin
val agent = ContextAwareAgent(...)

val orchestrator = TaskOrchestrator(agent, client, model)

val chatAgent: Agent = if (invariantsEnabled) {
    InvariantGuard(agent, LlmInvariantChecker(client, model)) { agent.getInvariants() }
} else {
    agent
}
```

**После:**
```kotlin
// День 15 (B): StatefulAgent — полная сборка stateful-агента (профиль + стейт + инварианты,
// global-plan 4.5). Заменяет анонимный chatAgent-wiring. checker опционален (opt-in --invariants).
val base = ContextAwareAgent(
    llmClient = client,
    memoryStore = memoryStore,
    model = model,
    chatId = chatId,
    reasoningStrategy = reasoningStrategy,
    historyCompressor = historyCompressor,
    contextManager = contextManager,
    profileExtractor = profileExtractor,
    autoProfileEvery = if (autoProfile) 5 else 0
)

val checker = if (invariantsEnabled) LlmInvariantChecker(client, model) else null
val agent = StatefulAgent(base, checker) { base.getInvariants() }

// День 15 (B, gap №6): оркестратор использует защищённый chat-провайдер от StatefulAgent →
// инварианты (если включены) покрывают и stage-поток, а не только свободный чат.
val orchestrator = TaskOrchestrator(base, client, model, chat = { msg -> agent.chat(msg) })

// chatAgent больше не нужен — единая точка чата это agent (StatefulAgent). else-ветка REPL
// использует agent.chat напрямую (см. задачу 03/20).
```

### Else-ветка REPL (строки 164–171) — упрощение

**Сейчас:** использует `chatAgent.chat(input)`.

**После:** `chatAgent` убран; else-ветка использует `agent.chat(input)` (это `StatefulAgent.chat`,
с инвариантами если включены). См. задачу 03/20 для полного контекста else-ветки (роутинг + режимы).

```kotlin
} else {
    // День 14/15: свободный чат через StatefulAgent (инварианты opt-in --invariants).
    val response = AppTerminal.withSpinner({ spinnerLabel(agent) }) { agent.chat(input) }
    AppTerminal.println()
    AppTerminal.markdown(response)
    AppTerminal.println()
}
```

### Версия (строка 125) — бамп

**Сейчас:** `CLI Agent v0.7 | ...`

**После:** `CLI Agent v0.8 | ...` + добавить индикатор режима:
```kotlin
val modeLabel = (agent.contextAware.getWorkingMemory()?.interactionMode ?: InteractionMode.PLAN).name.lowercase()
AppTerminal.println("CLI Agent v0.8 | Chat: $chatId | Model: $model | Context: ${contextManager.getStrategy().getName()} | Compress: $compressLabel | Invariants: $invariantsLabel | Mode: $modeLabel")
```
(`interactionMode` появляется в задаче 18; до неё — убрать `Mode: $modeLabel` из строки, добавить в
задаче 19.)

### Импорты
Добавить:
```kotlin
import com.cliagent.agent.StatefulAgent
```
Убрать (если больше не используется напрямую): `import com.cliagent.agent.InvariantGuard` — НЕТ,
`StatefulAgent` использует его внутренне через композицию, но `ChatCommand` напрямую больше не
ссылается. Проверить и убрать неиспользуемый импорт.

### `/help` (printHelp) — обновить

Добавить строки про новые возможности дня 15:
```
/task set <stage> [--force]   Set stage; --force overrides illegal/artifact-gate
/mode [manual|plan|auto]      Show/set interaction mode (задача 19)
```
(режимы — задача 19; `--force` — задача 10. Здесь обновляется `/help` целиком после всех этапов.)

## Логика
- **`base` + `agent` = `StatefulAgent`** — `base` это `ContextAwareAgent` (аксессоры), `agent` это
  `StatefulAgent` (интерфейс `Agent` + инварианты). Оркестратор получает `base` для аксессоров и
  `{ msg -> agent.chat(msg) }` для защищённого чата.
- **`chatAgent` убран** — единая точка чата теперь `agent` (StatefulAgent). Устраняет дублирование
  «когда использовать защищённый, когда голый агент» — теперь всегда защищённый (если checker задан).
- **`checker` как `?`** — opt-in через `--invariants`; `null` без флага (текущее поведение, ноль
  накладных). Логика та же, что в старом ternary, но инкапсулирована в `StatefulAgent`.

## Поток wiring (итог)
```
base = ContextAwareAgent(...)
agent = StatefulAgent(base, checker?, { base.getInvariants() })     [интерфейс Agent + инварианты]
orchestrator = TaskOrchestrator(base, client, model, chat = { agent.chat(it) })  [gap №6 закрыт]

REPL else-ветка:
  agent.chat(input)                                                   [через StatefulAgent → guard если включён]
/task set ...: agent.contextAware.attemptTransition(...)              [аксессоры через base]
/task next:     agent.contextAware.attemptTransition(...)
оркестратор:    base.getTaskState()/setTaskState() + chat()           [аксессоры + защищённый чат]
```

## Ключевые инварианты
- **Единая точка чата** — `agent.chat` (StatefulAgent) везде в REPL. Устраняет путаницу «какой агент
  для какого случая».
- **Оркестратор: `base` для аксессоров, `chat`-провайдер для LLM** — разделение ответственности (см.
  задачу 14).
- **Обратная совместимость** — без `--invariants` `StatefulAgent` прозрачный делегат (поведение =
  день 14 без флага). С `--invariants` — guard активен, теперь покрывает и stage-flow.
- **Версия v0.8** — отражает день 15 (v0.6 = день 13, v0.7 = день 14).

## Решения
- **`StatefulAgent` вместо inline ternary** — именованная, тестируемая сущность. Закрывает extension
  point global-plan 4.5 («StatefulAgent — полная сборка»).
- **`checker` вычисляется до `agent`** — `val checker = if (...) ... else null`; передаётся в
  конструктор `StatefulAgent`. Чище, чем ternary в аргументе.
- **`Mode: $modeLabel` в заголовке** — пользователь видит текущий режим (видимая обратная связь).
  Появляется в задаче 19 (с `interactionMode` полем).

## Критерии готовности
- `StatefulAgent` инстанцируется в `ChatCommand`, `agent: StatefulAgent`.
- `TaskOrchestrator` получает `chat = { agent.chat(it) }` (gap №6 закрыт).
- `chatAgent` убран; else-ветка использует `agent.chat`.
- Без `--invariants` поведение = день 14 (прозрачный делегат).
- С `--invariants` инварианты применяются и к свободному чату, и к stage-потоку (демо H).
- Версия v0.8 в заголовке.
- `./gradlew test build` зелёный (все существующие тесты + новые).

## Зависимости (задачи)
10, 12, 14. `/help` финализируется в 19 (с `/mode`). Режимы — 18/19/20.
