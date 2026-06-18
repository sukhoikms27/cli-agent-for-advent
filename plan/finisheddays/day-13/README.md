# День 13. Состояние задачи (Task State Machine) — задачи

## Задание курса (`plan/newdays/day13.md`)
Реализовать состояние задачи как конечный автомат: этап задачи, текущий шаг, ожидаемое действие.
Пример состояний: `planning → execution → validation → done`. Проверить: паузу на любом этапе;
продолжение без повторных объяснений. Артефакт: агент с формализованным состоянием задачи.

## Что уже есть после Day 12 (фон)
- `WorkingMemory(currentTask, plan, scratchNotes, taskDecisions)` — несёт placeholder-комментарий
  «Day 13: `val taskState: TaskState? = null`» (`memory/MemoryLayer.kt`).
- `WorkingMemory` per-chat, персистится через `saveWorkingMemory`/`loadWorkingMemory`, очищается
  при `/reset` (вместе с `clearWorkingMemory`).
- `PromptBuilder` рендерит слои памяти: `WorkingMemory.renderBlock()` — точка рендера task state.
- `ContextAwareAgent`: готовые шаблоны — `getProfile/setProfile` (делегирование через copy),
  hook в `chat()` после `assistantMsg` (Day 12 `turnCount % N`), `ensureLoaded()` грузит
  `workingMemory`, `reset()` чистит его.
- `ChatCommand.handleProfile` — образец CLI-хендлера (parts-split + when + AppTerminal).
- Тесты: `storeMock`/`fakeResponse` (`ContextAwareAgentProfileTest`), schema-evolution
  (`ChatDataSchemaEvolutionTest`, `UserProfileSchemaTest`).

## Принятые решения (по ответам пользователя)
1. **Охват — только стейт-машина.** InvariantChecker и StatefulAgent (полная сборка Week 3) —
   отдельным днём. Не реализуем.
2. **Стадии — с CLARIFY:** `clarify → planning → execution → validation → done`.
3. **Продвижение — только ручное** через `/task`-команды. Никаких доп. LLM-вызовов, без
   `--auto-task`/`TaskStateExtractor`.

## Ключевая идея
`taskState` живёт внутри `WorkingMemory` (per-chat, уже персистится, чистится на `/reset`). Это
даёт «паузу/возобновление» **бесплатно**: выход из REPL = пауза; `cli-agent chat -c <chatId>` →
`ensureLoaded()` грузит `workingMemory.taskState` → `PromptBuilder` инжектит блок состояния в
каждый запрос → агент продолжается без повторных объяснений. Отдельной кодовой трассы resume НЕ
нужно.

## Архитектура
- `state/` (новый пакет): `TaskStage` (enum), `TaskState` + `StageTransition` (data classes),
  `TaskStateMachine` (чистая логика). Зависимость `memory → state` однонаправленная.
- `WorkingMemory.taskState: TaskState? = null` — слот данных.
- `WorkingMemory.renderBlock()` — рендер блока `Task state:` в system prompt.
- `ContextAwareAgent.getTaskState/setTaskState/advanceTaskState/revertTaskState` — аксессоры.
- `/task`-семейство команд + таб-completion + help.

## Декомпозиция (выполнять последовательно, по подтверждению пользователя)

| # | Файл | Задача | Завис. |
|---|---|---|---|
| 01 | `state/TaskStage.kt` (новый) | `@Serializable enum class TaskStage { CLARIFY, PLANNING, EXECUTION, VALIDATION, DONE }` + doc с переходами | — |
| 02 | `state/TaskState.kt` (новый) | `StageTransition` + `TaskState(stage, currentStep, expectedAction, approvedPlan, stageHistory)` | 01 |
| 03 | `state/TaskStateMachine.kt` (новый) | `object`: `isAllowed`, `next`, `transition` (строгий), `forceSet`, `back` | 01, 02 |
| 04 | `memory/MemoryLayer.kt` (правка) | `val taskState: TaskState? = null` в `WorkingMemory` + обновить `isEmpty()` | 02 |
| 05 | `agent/PromptBuilder.kt` (правка) | рендер блока `Task state:` в `WorkingMemory.renderBlock()` | 04 |
| 06 | `agent/ContextAwareAgent.kt` (правка) | аксессоры `getTaskState/setTaskState/advanceTaskState/revertTaskState` | 03, 04 |
| 07 | `cli/ChatCommand.kt` (правка) | диспетч + `handleTask` (по образцу `handleProfile`) + `printHelp` + версия v0.6 | 06 |
| 08 | `cli/ReplEngine.kt` (правка) | `/task` в `top` + `ArgumentCompleter` для подкоманд и стадий | 07 |
| 09 | тесты (4 файла) | `TaskStateMachineTest`, расширение `ChatDataSchemaEvolutionTest`, `ContextAwareAgentTaskStateTest`, расширение `PromptBuilderTest` | 03, 05, 06 |
| 10 | верификация | `./gradlew test`, `./gradlew build`, manual smoke (start → next → set → restart → resume) | 09 |

## Риски
1. **`memory → state` зависимость** — однонаправленная, допустима.
2. **`/task start` двойная запись** — ставить `currentTask` и `taskState` одним
   `setWorkingMemory(w.copy(...))`, не двумя вызовами.
3. **`/task back` целостность history** — self-transitions НЕ добавлять в history (guard в
   `transition`/`forceSet`), иначе `back` делает no-op после `/task set` на ту же стадию.
4. **`isEmpty()` дрейф** — после `/task start` `WorkingMemory.isEmpty()` = false даже без
   `currentTask` → блок рендерится. Желаемо. Существующие тесты `WorkingMemory().isEmpty()`
   остаются зелёными (taskState=null).
5. **Кейсинг стадии в промпте** — `stage.name.lowercase()` → `execution` (консистентно с
   существующими render-блоками).
6. **`coerceInputValues`** на AppJson для неизвестного enum — старый чат с несуществующей стадией
   coercion’нется к первой (CLARIFY). Низкий риск для нового поля.

## Точки расширения
- Day 13+: `InvariantChecker` — hook в `chat()` после `assistantMsg` (фильтр ответа по constraints
  из профиля). Constructor param `invariantChecker: InvariantChecker? = null`.
- Day 13+: LLM авто-продвижение `--auto-task`/`TaskStateExtractor` — зеркало `--auto-profile`,
  hook `turnCount % autoTaskEvery == 0`.
- `StatefulAgent` — полная сборка profile + state + invariants.

## Критерии готовности (соответствие заданию)
- ✅ Состояние задачи как конечный автомат (`TaskStateMachine`, `TaskStage`).
- ✅ Этап / текущий шаг / ожидаемое действие (`TaskState`: stage / currentStep / expectedAction).
- ✅ Переходы `clarify → planning → execution → validation → done` с allowed-transitions.
- ✅ Пауза на любом этапе (выход из REPL, состояние персистится).
- ✅ Продолжение без повторных объяснений (resume через `-c <chatId>` + инъекция в prompt).
- ✅ Профиль Day 12 не затронут; инвариант совместимости (без новых флагов поведение = Day 12).

---

## Доработка по комментариям автора курса (Вариант 2)

Базовая реализация Day 13 (задачи 01–10) — ручная FSM: переходы безусловные, поведение по стадии
не форсируется. Автор курса (Алексей Гладков) дал рамку: **Вариант 1** (оркестратор + под-агенты на
отдельных сессиях) vs **Вариант 2** (детерминированные переходы по артефактам — «родился артефакт,
поехали дальше», проще и надёжнее). Эта доработка приводит Day 13 к **Варианту 2** + stage-enforcing
промпты (чтобы single-session агент вёл себя per stage). Без InvariantChecker и без под-агентов.

**Решения:** stage-промпт заменяет base; gating hard-block; escape через `/task set` (force).

| # | Файл | Задача | Завис. |
|---|---|---|---|
| 12 | `llm/model/StagePromptTemplates.kt` (новый) | `buildSystemMessage(stage)`, `when` по 5 стадиям | — |
| 13 | `state`/`agent`/`cli` (правки) | артефакт-поля `implementation`/`verdict`; `canAdvance`; рендер; stage-prompt precedence; `/task next` hard-block + `/task impl`/`verdict`; completion | 12 |
| 14 | тесты | `StagePromptTemplatesTest`, `canAdvance`, schema-evolution, stage-prompt injection, render | 12, 13 |
| 15 | верификация | `./gradlew test`/`build` + manual smoke (stage-prompts + gated next + escape force) | 14 |

Артефакты: `12-stage-prompts.md`, `13-artifact-gating.md`, `14-tests.md`, `15-verification.md`.
