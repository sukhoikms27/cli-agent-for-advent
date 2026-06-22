# Architectural Issues — cli-agent

Архитектурные замечания, выявленные архитектурным агентом и **перепроверенные вручную**
(ast-index usages/callers + чтение исходников). Разрыв с заявленной в `CLAUDE.md` /
`global-plan.md` архитектурой отмечен явно.

---

## 1. `ChatCommand` — god-объект, концентрирующий всю CLI-логику [высокая]
**Файл:** `src/main/kotlin/com/cliagent/cli/ChatCommand.kt:44-1118` (1118 строк, ~30 методов)

Один класс совмещает 11 ответственностей: clikt-флаги (45-62), wiring всех зависимостей
(83-153), REPL-цикл (164-203), диспетчеризацию 14+ slash-команд (168-188), рендер таблиц
и help (303-468), парсинг + бизнес-логику memory/profile/invariants/task (506-1116),
фабрику context-strategy (279-301), обработку LLM-исключений (268-274), стейт спиннера (69).

32 cross-layer импорта покрывают **все** слои: agent, context, llm, memory, state, config.
CLI-слой знает конкретные реализации стратегий (`StickyFactsStrategy`, `BranchingStrategy`),
конкретные классы memory (`JsonChatStore`, `UserProfile`, `WorkingMemory`), state-детали.

**Разрыв с CLAUDE.md:** заявлены отдельные `ConfigCommand.kt` и `CliAgentCommand` —
`CliAgentCommand` пустая заглушка, `ConfigCommand` отсутствует.

**Решение:** распилить на `ChatCommandRouter` (флаги+wiring), `ReplLoop`, `SlashCommandDispatcher`,
`ChatFormatter` и по handler-классу на группу команд.

## 2. Циклическая зависимость memory ↔ state ↔ llm [средняя]
Архитектурная диаграмма `global-plan.md` указывает однонаправленные слои
CLI → Agent → {LLM, Context, Memory+State}. Реально:

- **memory → state:** `memory/MemoryLayer.kt:3-5` → `InteractionMode`, `TaskState`, `Invariant`
  (`WorkingMemory` содержит `taskState: TaskState?`, `interactionMode: InteractionMode`).
- **state → llm:** `state/invariant/LlmInvariantChecker.kt:3-6` → `LlmClient`, `LlmResult`,
  `ChatMessage`, `ChatRequest`.
- **llm → state:** `llm/model/StagePromptTemplates.kt:3-4` → `TaskKind`, `TaskStage`.

Цикл `state → llm → state` + двусторонняя связь `memory ↔ state`. `global-plan.md` §3 явно
говорит: `state/` и `memory/` зависят от «—» (ничего).

## 3. `LlmInvariantChecker` живёт в `state/`, но зависит от `llm/` — нарушение слоёв [средняя]
**Файл:** `src/main/kotlin/com/cliagent/state/invariant/LlmInvariantChecker.kt:27-127`

Интерфейс `InvariantChecker` (state-слой) абстрагирован правильно, но его единственная
реализация `LlmInvariantChecker` лежит в `state/invariant/`, при этом импортируя 4 класса из
`llm/` и напрямую конструируя `ChatRequest`, делая HTTP-вызовы через `LlmClient.chat()`.
Контраст внутри пакета: `TaskStateMachine` и `TransitionGuard` (state/) — чистая логика,
0 внешних импортов кроме `java.time`. Реализация, зависящая от LLM-клиента, должна быть в
`agent/` (consumer-слой), а не в нижнем `state/`.

## 4. Утечка знаний о конкретных стратегиях в Agent и CLI — обход полиморфизма [средняя]
**Файлы:** `agent/ContextAwareAgent.kt:65,68,149,252,255`; `cli/ChatCommand.kt:490,529`

Код кастует `ContextStrategy` к конкретным `StickyFactsStrategy`/`BranchingStrategy`
(7 мест, подтверждено `grep "as? StickyFactsStrategy|as? BranchingStrategy"`), чтобы достать
state-specific поведение (load/save facts, load branches), которого нет в интерфейсе.
`SlidingWindowStrategy`/`SummaryStrategy` кастов не требуют → асимметрия подтверждает, что
интерфейс `ContextStrategy` неполон. Persistence state-specific данных вынесена наружу из
стратегии в consumer. Нужно: `interface StatefulStrategy : ContextStrategy { load/save }`
или вынести facts/branches в отдельные репозитории.

## 5. Доменный слой пишет в stdout через сырой `println` [средняя]
**Файл:** `src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt:91, 96, 105`

`println("🔄 Compressing history...")` и т.п. прямо в агенте. CLAUDE.md предписывает вывод через
mordant (`AppTerminal`), который существует и используется в CLI-слое. Прочие agent-классы
(`PromptBuilder`, `StatefulAgent`, `InvariantGuard`) чистые — подтверждено grep.

## 6. `ContextAwareAgent` — широкая связанность домена с инфраструктурой [средняя]
**Файл:** `src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt:11-20`

Агент импортирует 8 сущностей из `llm/` (`PromptTemplates`, `ReasoningStrategy`,
`StagePromptTemplates`, `SystemPrompts`, `ArtifactLimits`, `OutputBudget`, `TokenCounter`,
`truncateToTokens`) и сам считает токены, строит промпты по стратегиям, усекает артефакты.
28 функций на одном классе. Агент должен оркестрировать chat → response, а не управлять
prompt-templates/token-budgeting/artifact-truncation. Для сравнения мёртвый `SimpleAgent` —
4 функции, 0 token-импортов.

## 7. Мёртвая архитектура (классы-зомби) [низкая]
Подтверждено `ast-index usages` + `grep`:

- **`SimpleAgent`** (`agent/SimpleAgent.kt:11`) — 0 usages в продакшене. Заменён
  `ContextAwareAgent` (создаётся напрямую в `ChatCommand.kt:122`). CLAUDE.md описывает его
  как «basic agent (day 6)» — день давно пройден.
- **`BenchmarkRunner`** (`llm/BenchmarkRunner.kt:10`) — 0 usages, нет CLI-команды. Заявлен в
  `global-plan.md` §9. `ModelInfo` (нужный ему) также не используется вне декларации.
- **`ContextStrategyType`** enum (`context/strategy/ContextStrategy.kt:5`) — 0 usages,
  2 мёртвых импорта (`ContextAwareAgent.kt:6`, `ChatCommand.kt:14`). `global-plan.md` §9
  явно отвергает его, но он не удалён.

## 8. Разрыв документации и кода: `AgentResult`/`ExitCodes` заявлены, отсутствуют [средняя]
CLAUDE.md (раздел «Обработка ошибок: sealed class + exit codes») детально описывает
`sealed class AgentResult<out T>` и `object ExitCodes` (POSIX sysexits). В коде 0 деклараций
(только комментарий в `InvariantResult.kt:11`). Ошибки LLM бросаются как `LlmCallException`
(`ContextAwareAgent.kt:124,163`), ловятся в `TaskOrchestrator` и `ChatCommand.kt:270` —
**исключения используются для flow control**, что противоречит заявленной конвенции
CLAUDE.md («no exceptions for flow control»).

## 9. `switchStrategy` в агенте не делегирует в `ContextManager.switchStrategy` [высокая]
**Файл:** `src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt:249-259`

`ContextManager.switchStrategy` (`context/ContextManager.kt:11`) умеет менять стратегию
(мутирует `private var strategy`). Но `ContextAwareAgent.switchStrategy` не вызывает его и не
присваивает `contextManager = newManager` (поле `private val`, строка 42). Метод создаёт иллюзию
переключения. См. [`critical-issues.md`](critical-issues.md) §1.

## 10. DIP-нарушение: инфраструктура создаётся `new` в CLI-команде [средняя]
**Файл:** `src/main/kotlin/com/cliagent/cli/ChatCommand.kt:84, 95`

`ConfigRepository().load()` и `JsonChatStore()` инстанцируются прямо в `ChatCommand.run()`.
`ChatCommand` зависит от конкретных классов, а не абстракций; `ChatCommand` непротестируем без
файловой системы. Для entry-point частично оправдано (composition root), но `ConfigRepository`/
`JsonChatStore` стоит получать через фабрику.

---

## Что НЕ является проблемой (опровергнуто проверкой)
- `ContextStrategy` интерфейс — живой, 4 реализации, все используются.
- `MemoryStore` интерфейс — живой (1 реализация), агент работает через абстракцию без кастов.
- `LlmClient` интерфейс — живой, 12 потребителей через интерфейс.
- `StatefulAgent`, `SwarmStageAgent`, `TaskOrchestrator`, `TaskStateMachine`, `TransitionGuard` —
  живые, активно используются из main/REPL.
- `JsonChatStore` writer-актор — архитектурно корректное решение для concurrency.
