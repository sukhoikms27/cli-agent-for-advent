# День 15. Контролируемые переходы состояний + StatefulAgent — задачи

## Задание курса (`plan/newdays/day15.md`)
Реализуйте явные переходы между состояниями задачи: допустимые состояния, разрешённые переходы,
запрет «перепрыгнуть» этап (пример: нельзя реализацию до плана; нельзя финал без валидации).
Проверить: попытки недопустимого перехода; реакцию ассистента; продолжение после паузы.
Результат: ассистент с контролируемым жизненным циклом задачи.

Полный текст задания + дополнительные требования пользователя — `00-task.md`.

## Что уже есть после Day 14 (фон)
Доработка Day 13 (Вариант 2 автора курса) **частично** закрыла задание 15:
- `state/TaskStage` (5 стадий), `state/TaskState` (`StageTransition`, артефакт-поля
  `approvedPlan`/`implementation`/`verdict`/`requirements`, `awaitingAdvance`, `stageHistory`).
- `state/TaskStateMachine`: `ALLOWED`/`isAllowed`, `next`, `transition` (кидает исключение),
  `forceSet`, `back`, `canAdvance` (artifact-gate).
- `cli/ChatCommand.handleTask`: `/task`-семейство (12 подкоманд), hard-block `/task next`/`done`.
- `agent/stage/`: `TaskOrchestrator` (авто-поток), `EntryStageClassifier`, `StageAgent`/
  `StageResult`, по агенту на стадию (`Clarify`/`Planning`/`Execution`/`Validation`/`Done`),
  `PlanParser`, `StepAgent`.
- `llm/model/StagePromptTemplates.buildSystemMessage(stage)` — stage-enforcing промпты.
- День 14: `state/invariant/` (`Invariant`, `InvariantChecker`, `InvariantResult`,
  `LlmInvariantChecker`), `agent/InvariantGuard` (decorator, checkRequest→отказ без LLM,
  checkResponse→retry×3), `--invariants` opt-in, `/invariants`-команды.
- `memory/`: `WorkingMemory.taskState` (per-chat, персистится), `LongTermMemory.invariants`
  (global). `AppTerminal.withSpinner(label: String)`. REPL: `/help`, completer.

## Принятые решения (по ответам пользователя)
1. **Строгость `/task set`: жёсткий режим + `--force`.** По умолчанию нелегальные/неподготовленные
   переходы блокируются через новый `TransitionGuard`; escape — осознанный `--force` (note="forced").
2. **Объём дня:** переходы + `StatefulAgent` + доппункты 1 (авто вопрос/задача), 2 (уведомления по
   стадиям), 3 (режимы manual/plan/auto), 4 (динам. спиннер). Пункты 5–8 → `plan/extensions/`.
3. **Режимы:** `MANUAL` (только чат, FSM через `/task`), `PLAN` (текущее поведение — подтверждение
   переходов), `AUTO` (полная автоматизация, без подтверждения).
4. **Механизм авто-роутинга:** LLM-классификатор (`IntentClassifier`, образец
   `EntryStageClassifier`), fallback `QUESTION`.

## Ключевая идея
День 13/14 дали переходы и инварианты как **разрозненные** механизмы: `transition()` кидает
исключение (нет машиночитаемой причины), `forceSet` даёт неявную лазейку перепрыгнуть этап,
инварианты не покрывают stage-поток. День 15 вводит **единый арбитр переходов** (`TransitionGuard` →
sealed `TransitionOutcome`) и **полноценную сборку stateful-агента** (`StatefulAgent`), через
которую проводят все переходы и все LLM-вызовы (включая stage-flow). Сверху — авто-роутинг интента,
структурированные уведомления, режимы автоматизации и динамический спиннер: агент сам решает, когда
завести задачу, сам ведёт по стадиям, сам не даёт перепрыгнуть, и информирует пользователя на каждом
шаге.

## Архитектура

```
agent/stage/
  IntentClassifier.kt       (НОВОЙ, п.1) ── LLM: QUESTION|TASK
  TaskOrchestrator.kt       (правка) ─── chat-провайдер (защищённый), режимы, колбэк уведомлений
state/
  TransitionOutcome.kt      (НОВОЙ) ──── sealed Allowed/Illegal/ArtifactMissing
  TransitionGuard.kt        (НОВОЙ) ──── единый арбитр переходов
  InteractionMode.kt        (НОВОЙ, п.3) enum MANUAL/PLAN/AUTO
  TaskStateMachine.kt       (правка) ─── allowedTargets(from)
agent/
  StatefulAgent.kt          (НОВОЙ) ──── decorator: profile+state+invariants (gap №6 закрыт)
  ContextAwareAgent.kt      (правка) ─── attemptTransition(to, force)
cli/
  StageAnnouncer.kt         (НОВОЙ, п.2) форматирование уведомлений из StageResult/переходов
  ChatCommand.kt            (правка) ─── wiring StatefulAgent, /mode, авто-роутинг, динам. спиннер
  ReplEngine.kt             (правка) ─── completer /mode, /task set --force
  AppTerminal.kt            (правка) ─── withSpinner(() -> String) overload (п.4)
memory/MemoryLayer.kt       (правка) ─── WorkingMemory.interactionMode
```

**Поток (после wiring):**
```
свободный текст при taskState==null и режиме≠MANUAL
  → IntentClassifier.classify
    → QUESTION → обычный чат через StatefulAgent (инварианты)
    → TASK     → orchestrator.startTask (автостарт FSM)

активная задача:
  → orchestrator.handleUserInput
    → TransitionGuard.attempt (единственный путь перехода)
      Illegal / ArtifactMissing → уведомление пользователю, стадия не меняется
      Allowed → переход + StageAnnouncer + (AUTO: авто-advance без подтверждения)
  → StageAgent.run(ctx) { msg -> statefulAgent.chat(msg) }  ← инварианты ПОКРЫВАЮТ stage-flow
```

## Декомпозиция (по этапам, выполнять последовательно)

| Этап | # | Артефакт | Задача | Завис. |
|---|---|---|---|---|
| | 00 | `00-task.md` | задание курса + доптребования | — |
| | 01 | `README.md` | этот обзор | — |
| **0** | 02 | `02-intent-classifier.md` | `agent/stage/IntentClassifier.kt` (LLM, QUESTION\|TASK, fallback QUESTION) | — |
| **0** | 03 | `03-intent-routing.md` | правка `ChatCommand` else-ветка: авто-роутинг при `taskState==null` & режим≠MANUAL | 02 |
| **0** | 04 | `04-intent-tests.md` | `IntentClassifierTest` | 02 |
| **A** | 05 | `05-transition-outcome.md` | `state/TransitionOutcome.kt` (sealed) | — |
| **A** | 06 | `06-allowed-targets.md` | правка `TaskStateMachine.allowedTargets(from)` + тест | — |
| **A** | 07 | `07-transition-guard.md` | `state/TransitionGuard.kt` (единый арбитр) | 05, 06 |
| **A** | 08 | `08-transition-guard-tests.md` | `TransitionGuardTest` (~12 кейсов) | 07 |
| **A** | 09 | `09-agent-attempt-transition.md` | правка `ContextAwareAgent.attemptTransition(to, force)` | 07 |
| **A** | 10 | `10-cli-controlled-set.md` | правка `handleTask`: `/task set … --force`, `/task next`/`done` через guard | 09 |
| **A** | 11 | `11-repl-force-completion.md` | правка `ReplEngine` completer `--force` | 10 |
| **B** | 12 | `12-stateful-agent.md` | `agent/StatefulAgent.kt` (decorator, gap №6) | — |
| **B** | 13 | `13-stateful-agent-tests.md` | `StatefulAgentTest` | 12 |
| **B** | 14 | `14-orchestrator-protected-chat.md` | правка `TaskOrchestrator`: `chat`-провайдер | 12 |
| **B** | 15 | `15-cli-wiring.md` | wiring `StatefulAgent`, бамп v0.7→v0.8, `/help` | 10, 12, 14 |
| **C** | 16 | `16-stage-announcer.md` | `cli/StageAnnouncer.kt` (п.2) | — |
| **C** | 17 | `17-orchestrator-announcements.md` | правка orchestrator + рендеринг announcer | 16 |
| **D** | 18 | `18-interaction-mode.md` | `state/InteractionMode.kt` + `WorkingMemory.interactionMode` (п.3) | — |
| **D** | 19 | `19-mode-command.md` | правка `ChatCommand` `/mode`, completer, help | 18 |
| **D** | 20 | `20-orchestrator-mode-aware.md` | правка orchestrator + ChatCommand: MANUAL/PLAN/AUTO | 03, 18 |
| **E** | 21 | `21-dynamic-spinner.md` | правка `AppTerminal.withSpinner(() -> String)` (п.4) | — |
| **E** | 22 | `22-stage-status-provider.md` | правка call-sites спиннера: stageLabel() | 21 |
| | 23 | `23-tests-summary.md` | сборный обзор тестов | 02–22 |
| | 24 | `24-verification.md` | `./gradlew test build installDist` + manual REPL + чек-лист | 23 |
| | 25 | `25-demo-scenario.md` | демо: сценарии A–H | 24 |
| **EXT** | EXT-1..4 | `plan/extensions/05..08-*.md` | спецификации п.5–8 | — |

## Риски
1. **Большой объём (~25 артефактов)** → строгая поэтапность (0→A→B→C→D→E→EXT), `./gradlew test`
   после каждого этапа; каждый артефакт атомарен и независимо тестируется.
2. **Регрессия `/task set`** (раньше warn+force, теперь блок) → intentional (решение пользователя);
   `--force` сохраняет escape-hatch и обратную совместимость; документируем в `/help` и README.
3. **`InteractionMode` default=PLAN** → текущее поведение сохраняется, нулевая регрессия существующих
   тестов и UX.
4. **canAdvance только для forward-canonical** (`to == next(from)`). Боковые переходы (rework/replan/
   new task) — легальны без gate; иначе блокировали бы нормальный rework-цикл.
5. **AUTO-режим** → уважает `TransitionGuard` (перепрыгивание всё равно заблокировано); авто-advance
   только канонический forward после готовности артефакта.
6. **Динам. спиннер на non-TTY** → mordant `textAnimation` не пишет в pipe (уже так), overload через
   делегирование сохраняет поведение.
7. **`StatefulAgent` поглощает `InvariantGuard` через композицию** → 9 тестов `InvariantGuard`
   остаются зелёными (guard не трогается, используется как внутренний компонент).
8. **`WorkingMemory` schema-evolution** → `interactionMode` имеет default `PLAN`; AppJson
   (`ignoreUnknownKeys`, `explicitNulls=false`, enum coerce) → старые чаты грузятся без миграций.

## Точки расширения (Day 15+)
- `CompositeTransitionGuard` — несколько правил перехода (как `CompositeInvariantChecker` из
  day-14 TODO).
- Связь переходов с инвариантами: gate «нельзя войти в EXECUTION, если stack-invariant нарушен».
- Инварианты per-chat (не только global).
- Реализация extensions EXT-1..4 (спецификации создаются в этом дне, реализация — будущие дни).
- `--auto-task`/`TaskStateExtractor` — зеркало `--auto-profile` для stateless-извлечения состояния.

## Критерии готовности (соответствие заданию курса + доптребованиям)
- ✅ Допустимые состояния (`TaskStage`) + `allowedTargets(from)` (новое).
- ✅ Разрешённые переходы (`ALLOWED`/`isAllowed` + единый `TransitionGuard`).
- ✅ «Не мог перепрыгнуть этап»: `TransitionGuard.attempt` → `Illegal`/`ArtifactMissing`, жёсткий
  блок без `--force`.
- ✅ «Нельзя реализация без плана»: `ArtifactMissing` при `planning→execution` без `approvedPlan`.
- ✅ «Нельзя финал без валидации»: `Illegal`/`ArtifactMissing` при попытке `→done` вне VALIDATION.
- ✅ Реакция ассистента на недопустимый переход: `⛔ Blocked` с причиной и подсказкой.
- ✅ Продолжение после паузы: персистентность `taskState` + демо restart (сценарий G).
- ✅ (п.1) Авто-роутинг «простой вопрос vs задача» через `IntentClassifier`.
- ✅ (п.2) Структурированные уведомления по стадиям через `StageAnnouncer`.
- ✅ (п.3) Режимы `MANUAL`/`PLAN`/`AUTO` через `InteractionMode` + `/mode`.
- ✅ (п.4) Динамический текст спиннера по стадии через `withSpinner(() -> String)`.
- ✅ `StatefulAgent` (global-plan 4.5): полная сборка профиль+стейт+инварианты, gap №6 закрыт.
- ✅ Инвариант совместимости: без новых флагов/команд поведение = Day 14 (`PLAN` по умолчанию).
