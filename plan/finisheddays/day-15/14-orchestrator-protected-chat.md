# Задача 14. Защищённый `chat`-провайдер в `TaskOrchestrator` (Этап B)

## Цель
Закрыть gap №6: stage-LLM-вызовы (`StageAgent.run(ctx) { msg -> agent.chat(msg) }`) сейчас идут через
голый `ContextAwareAgent.chat`, минуя `InvariantGuard`. Передать в оркестратор `chat`-провайдер от
`StatefulAgent` (через wiring в задаче 15), чтобы инварианты проверялись и на stage-потоке.

## Зависимости
12 (`StatefulAgent`). Существующий `TaskOrchestrator` (день 13, доработка).

## Файл (правка)
`src/main/kotlin/com/cliagent/agent/stage/TaskOrchestrator.kt`

## Что изменить

**Сейчас** (конструктор, строки 38–44):
```kotlin
class TaskOrchestrator(
    private val agent: ContextAwareAgent,
    llmClient: LlmClient,
    model: String,
    private val classifier: EntryStageClassifier = EntryStageClassifier(llmClient, model),
    agents: Map<TaskStage, StageAgent> = defaultAgents()
) {
```

`StageAgent.run(ctx) { userMsg -> agent.chat(userMsg) }` (строка 136) вызывает `agent.chat`
напрямую — это голый `ContextAwareAgent`, инварианты не проверяются.

**После:** добавить параметр `chat`-провайдера (default — делегирование в `agent.chat`, обратно
совместимо; wiring в задаче 15 подставит защищённый провайдер от `StatefulAgent`):

```kotlin
class TaskOrchestrator(
    private val agent: ContextAwareAgent,
    llmClient: LlmClient,
    model: String,
    private val classifier: EntryStageClassifier = EntryStageClassifier(llmClient, model),
    agents: Map<TaskStage, StageAgent> = defaultAgents(),
    /** День 15: провайдер LLM-чата для stage-вызовов. Default — agent.chat (текущее поведение).
     *  Wiring (ChatCommand) подставляет StatefulAgent.chat → инварианты покрывают stage-flow. */
    private val chat: suspend (String) -> String = { userMsg -> agent.chat(userMsg) }
) {
    private val agents: Map<TaskStage, StageAgent> = agents
```

И в `runStageAndDisplay` (строка 136) заменить `agent.chat` на `chat`:
```kotlin
val result = agentImpl.run(ctx) { userMsg -> chat(userMsg) }
```

## Логика
- **Default `{ msg -> agent.chat(msg) }`** — обратно совместимо: существующие тесты/вызовы
  `TaskOrchestrator(agent, client, model)` без `chat` работают как прежде (стадийный поток через
  голый `agent.chat`, без инвариантов — текущее поведение).
- **Wiring подставляет защищённый провайдер** (задача 15): `TaskOrchestrator(base, client, model,
  chat = { msg -> statefulAgent.chat(msg) })`. Тогда `StageAgent.run(ctx) { chat(it) }` →
  `statefulAgent.chat(it)` → `InvariantGuard` (если включён) → инварианты проверяются на stage-потоке.
- **`agent` остаётся для аксессоров** — оркестратор всё ещё вызывает `agent.getTaskState`/
  `setTaskState`/`getProfile` (строки 121, 122, 141, 145 и др.). Эти аксессоры не должны идти через
  инвариант-проверку (это управление состоянием, не LLM-чат). Поэтому `agent` и `chat` разделены:
  `agent` — для state/profile accessors, `chat` — для LLM-вызовов.
- **`agent.chat` всё ещё доступен** — если какой-то внутренний путь оркестратора хочет
  «незащищённый» чат (сомнительно, но возможно), он может вызвать `agent.chat` напрямую. В день 15
  все stage-вызовы переведены на `chat`-провайдер.

## Поток (после wiring)
```
runStageAndDisplay(stage, ...):
  ctx = StageContext(...)                           [из agent.getTaskState/getProfile]
  agentImpl.run(ctx) { userMsg -> chat(userMsg) }   [chat = statefulAgent.chat]
                                                     → InvariantGuard.chat(userMsg)
                                                       ├─ request Violated → ⛔ без LLM
                                                       ├─ response Valid → ответ
                                                       └─ response Violated → retry×3
  storeArtifact(agent.getTaskState()!!, ...)         [agent для аксессоров]
  agent.setTaskState(updated)
```

## Влияние на stage-агентов
`StageAgent.run(ctx, chat: suspend (String) -> String)` — интерфейс НЕ меняется (строка 27 в
`StageAgent.kt`). Меняется только то, **что** передаётся в `chat` на call-site оркестратора: раньше
`agent.chat`, теперь `chat`-провайдер. Все 5 stage-агентов (`Clarify`/`Planning`/`Execution`/
`Validation`/`Done`) остаются без изменений — они работают с абстрактным `chat`.

## Ключевые инварианты
- **Default обратно совместим** — `{ msg -> agent.chat(msg) }` = текущее поведение; нулевая регрессия.
- **`agent` для аксессоров, `chat` для LLM** — чёткое разделение: управление состоянием не должно
  проходить инвариант-проверку (иначе `setTaskState` блокировался бы правилами контента — бессмысленно).
- **Gap №6 закрыт** — с wiring (задача 15) инварианты покрывают stage-flow: запрос «напиши на Compose»
  внутри execution-стадии будет заблокирован инвариантом `no-compose`, даже если пользователь явно
  его не запрашивал (стадийный агент мог предложить Compose в реализации).

## Решения
- **Провайдер через лямбду, не через ссылку на `StatefulAgent`** — развязка: оркестратор не зависит
  от `StatefulAgent`, только от `ContextAwareAgent` (для аксессоров) и `(String)->String` (для чата).
  Можно передать любой чат-провайдер (mock в тестах, StatefulAgent в проде).
- **Default в конструкторе** — публичный API оркестратора обратно совместим; wiring (задача 15)
  использует именованный аргумент `chat = ...`.
- **Не вводит зависимость `agent/stage → agent/StatefulAgent`** — сохраняется слоистость. Оркестратор
  знает только `ContextAwareAgent` + интерфейс `Agent` (через лямбду).

## Критерии готовности
- `TaskOrchestrator` принимает опц. `chat: suspend (String) -> String` (default `agent.chat`).
- `runStageAndDisplay` вызывает stage-агента через `chat`-провайдер, не через `agent.chat` напрямую.
- Без wiring (default) поведение идентично текущему (тесты, если есть, зелёные).
- С wiring `statefulAgent.chat` — инварианты применяются к stage-вызовам (проверяется в демо H, задача 25).

## Зависимости (задачи)
12. Wiring в 15. Демо H в 25.
