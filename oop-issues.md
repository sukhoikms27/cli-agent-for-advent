# OOP / Design Issues — cli-agent

ООП-замечания, выявленные агентом ООП-дизайна и **перепроверенные вручную** по исходникам.
Гипотезы, не подтверждённые кодом, опущены. Kotlin-idioms (data class с `var` для сериализации,
sealed Result-паттерны) отмечены отдельно и **не считаются** проблемой.

---

## КРИТИЧЕСКОЕ

### 1. God-класс `ChatCommand` — 11 ответственностей [высокая]
**Файл:** `src/main/kotlin/com/cliagent/cli/ChatCommand.kt:44-1118`
SRP-нарушение в крайней степени: флаги, wiring, REPL, диспетчеризация, рендер, бизнес-логика
команд, фабрика стратегий, обработка исключений — всё в одном классе. Подробно в
[`arch-issues.md`](arch-issues.md) §1.

### 2. `MemoryStore` — толстый интерфейс, нарушающий ISP [высокая]
**Файл:** `src/main/kotlin/com/cliagent/memory/MemoryStore.kt:5-37`
Один интерфейс смешивает 5–6 обязанностей: messages (6-9), summary (15-17), facts (20-21),
branches (24-26), working memory (29-31), long-term memory (34-36) — ~19 методов. Клиент,
использующий одну группу (например, `ContextManager` для facts), зависит от всех 19.
Тестовый мок вынужден реализовывать всё. Нужно сегрегировать на `ChatMessageStore`,
`SummaryStore`, `FactsStore`, `BranchStore`, `WorkingMemoryStore`, `LongTermMemoryStore`.

### 3. `JsonChatStore` — SRP: god-store + менеджер корутин + сериализатор [высокая]
**Файл:** `src/main/kotlin/com/cliagent/memory/JsonChatStore.kt:21-272`
Реализует все 19 методов `MemoryStore` + управляет writer-актором
(`CoroutineScope`, `Channel<WriteOp>`, `init{ launch }`, `close()` — 41-77) + JSON-сериализацией
(`atomicWrite` 259-271). Шаблон read-modify-write (`loadChatInternal + copy + atomicWrite`)
повторяется 8 раз (83-91, 97-105, 147-155, 161-169, 171-179, 185-201, 207-215, 218-226, 232-240).
Writer-актор стоит вынести в `ChatFileRepository`; RMW — в обобщённый `update(chatId) { it -> it }`.

---

## ВЫСОКАЯ

### 4. `ContextAwareAgent` — SRP: 9 групп аксессоров поверх чата [высокая]
**Файл:** `src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt:32-368`
Класс реализует `Agent.chat` (74-165), но экспортирует аксессоры для 5 независимых доменов:
history/summary/compress (207-242), context manager (244-259), working memory (263-277),
profile (281-285), invariants (289-309), task state (313-367). 25+ public методов на одном классе.
`StatefulAgent.contextAware` expose (`StatefulAgent.kt:58`) — паллиатив, признающий проблему
(«без expose пришлось бы дублировать … или расширять интерфейс Agent»). Память/профиль/
инварианты/FSM — разные агрегаты; их аксессоры должны быть на отдельных репозиториях.

### 5. `switchStrategy` не переключает стратегию — нарушение инварианта класса [высокая]
**Файл:** `src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt:249-259`
Метод называется `switchStrategy`, возвращает «Switched to X», но не меняет `contextManager`
(`private val`, строка 42). Метод лжёт о своём эффекте — нарушение инварианта класса. См.
[`critical-issues.md`](critical-issues.md) §1.

### 6. `BranchingStrategy.switchBranch(name)` — контракт name/id не сходится [высокая]
**Файл:** `src/main/kotlin/com/cliagent/context/strategy/BranchingStrategy.kt:48-55`
Параметр назван `branchId`, метод ищет по `branches` (keyed by id), а caller передаёт **имя**
(`ChatCommand.kt:568`). `createBranch` генерирует `id = "branch-<uuid>"` (`JsonChatStore.kt:188`)
— id ≠ name. Метод не специфицирует, что name≠id, и не ищет по name. И баг, и OOP-контрактный
дефект. См. [`critical-issues.md`](critical-issues.md) §2.

---

## СРЕДНЯЯ

### 7. `TaskStage` — enum вместо sealed; ~15 разрозненных `when` по нему [средняя]
**Файл:** `src/main/kotlin/com/cliagent/state/TaskStage.kt:29`
`enum class TaskStage { CLARIFY, PLANNING, EXECUTION, VALIDATION, DONE }` — у стадии нет ни
имени, ни emoji, ни имени артефакта, ни канонического next, ни prompt. Все ассоциации размазаны
по `when` в `StageAnnouncer` (3 метода), `StagePromptTemplates.kt:26`, `SwarmPrompts`
(6 мест), `TaskStateMachine.kt:50,64`, `TransitionGuard.kt:72`, `TaskOrchestrator.kt:329,337`
— всего ~15 точек правки на новую стадию. OCP-нарушение. `sealed interface StageBehaviour` с
одним `object` на стадию (name/emoji/artifactName/next/prompt/storeArtifact) сделал бы добавление
стадии локальным изменением. (Текущая `@Serializable enum` + `coerceInputValues` — осознанный
idiom для миграций, но sealed+serializable тоже сериализуется по имени.)

### 8. `TaskState` — анемичная модель + feature envy в `TaskStateMachine` [средняя]
**Файлы:** `state/TaskState.kt:45-56`, `state/TaskStateMachine.kt`, `state/TransitionGuard.kt`,
`agent/stage/TaskOrchestrator.kt:325-342`
`TaskState` — чистый data-контейнер (12 полей, 0 методов). Вся FSM-логика размазана:
переходы — `object TaskStateMachine` (принимает `TaskState` параметром — feature envy),
арбитраж — `object TransitionGuard`, запись артефакта по стадии — приватный `when` в
`TaskOrchestrator.storeArtifact` (329-335, знает структуру `TaskState`), подсказки —
`missingArtifactHint` (337-342) и `TransitionGuard.gateHint` (72-77). Для сериализуемого
агрегата data-модель оправдана, но `transition`/`canAdvance`/`withArtifact` принадлежат
состоянию (как extension/методы), а не god-объектам.

### 9. `as?`-касты к конкретным стратегиям — LSP-симптом [средняя]
**Файлы:** `agent/ContextAwareAgent.kt:65-70,149-151,252-257`; `cli/ChatCommand.kt:489-490,528-529`
7 мест каста `ContextStrategy` к `StickyFactsStrategy`/`BranchingStrategy` (подтверждено grep),
чтобы достать state-specific поведение, которого нет в интерфейсе. Каждая новая stateful-стратегия
потребует новых кастов в 4+ местах. Симптом ISP-нарушения `ContextStrategy`. См. [`arch-issues.md`](arch-issues.md) §4.

### 10. `TokenCounter.SessionTokens` — `var` поля + утекает живая ссылка [средняя]
**Файл:** `src/main/kotlin/com/cliagent/llm/token/TokenCounter.kt:8-31`
`data class SessionTokens` с 6 `var` полями (9-14), мутируется напрямую `stats.totalPromptTokens +=`
(22). `getSessionStats` (30-31) возвращает сам объект из `mutableMapOf`, а не копию — caller
(`ChatCommand.printStats/printCost`) может мутировать агрегаты. Должно быть `val` + `copy` в
`recordUsage`, либо приватный accumulator + immutable view наружу.

### 11. `Pricing` — object-синглтон с захардкоженным стейтом [средняя]
**Файл:** `src/main/kotlin/com/cliagent/llm/pricing/Pricing.kt:9-29`
`object Pricing` хранит `private val prices = mapOf(...)` (5 моделей GLM). Данные + логика
смешаны, конфигурация неинъектируема — добавить модель = правка кода (OCP-нарушение). Лучше
`class Pricing(prices: Map<String, Price>)` или `PriceTable` (data) + `object PriceCalculator` (без стейта).

### 12. `AppTerminal` — object-синглтон с мутабельным `var t` [средняя]
**Файл:** `src/main/kotlin/com/cliagent/cli/AppTerminal.kt:21-105`
`object AppTerminal { var t: Terminal = Terminal(); private set; fun disableColor() { t = Terminal(AnsiLevel.NONE) } }`
— глобальный мутабельный singleton терминала + 8 UI-методов (god-singleton). `disableColor()`
мутирует глобал не потокобезопасно. Лучше `class AppTerminal` с инъекцией.

### 13. `ContextManager` — `private var strategy` + анемичный делегат [средняя]
**Файл:** `src/main/kotlin/com/cliagent/context/ContextManager.kt:6-35`
`private var strategy: ContextStrategy` (7), меняемый через `switchStrategy` (11-16). Сам класс —
тонкий делегат к `ContextStrategy` (все 7 методов проксируют без логики). Либо лишний слой, либо
value object без `var`. Усугубляется тем, что агентский `switchStrategy` (`ContextAwareAgent.kt:249`)
вообще не вызывает этот метод и не обновляет менеджер (§5).

### 14. `TaskOrchestrator` — смешанная ответственность + функциональная инъекция [средняя]
**Файл:** `src/main/kotlin/com/cliagent/agent/stage/TaskOrchestrator.kt:47-370`
Оркестрирует stage-поток, но также: реализует auto-continue LLM-вызовов `chatWithContinue`
(298-322) — это инфраструктура LLM-клиента; маппит артефакт→поле `TaskState` через `storeArtifact`
(325-335, `when` по стадиям, feature envy); держит `isYes` (140-147, утилита). Параметр
`chat: suspend (String) -> String` (67) — лямбда вместо `Agent`, скрывает зависимость и нарушает
DIP. `chatWithContinue` принадлежит LLM-декоратору, `storeArtifact` — `TaskState.withArtifact`.

---

## НЕ проблемы (проверено, опровергнуто — Kotlin-idioms)
- `LlmResult<T>`, `TransitionOutcome`, `InvariantResult` — sealed Result-паттерны, exhaustive
  `when`, без `if/else` на типах. Отличный дизайн.
- `StageAgent` интерфейс + 5 реализаций + `SwarmStageAgent` — OCP соблюдён, новый stage-агент
  без правки оркестратора.
- `InvariantChecker` + `LlmInvariantChecker` + `InvariantGuard` (decorator) — DIP/стратегия/декоратор корректны.
- `Agent` интерфейс + decoration (`StatefulAgent`, `InvariantGuard`) — ISP соблюдён (3 метода).
- `ChatMessage`/`ChatRequest`/`ChatResponse`/`Usage` — сериализационные DTO, анемичность уместна.
- `PlanParser`, `OutputBudget`, `ArtifactLimits`, `AppPaths` — stateless `object`-утилиты/константы,
  не синглтоны со стейтом.
