# Задача 17 (авто-поток Day 13). Кейс тестирования — автоматизация стадий + профиль-инварианты

Пошаговый кейс для верификации **автоматического стадийного потока** Day 13 (доработка, расширяет
базовый Day 13). Привязан к реальной реализации: `TaskOrchestrator`, `EntryStageClassifier`,
`StageAgent` + 5 реализаций (`Clarify/Planning/Execution/Validation/Done`), `StepAgent`,
`PlanParser`, `StageContext.profileBlock`, `TaskState.awaitingAdvance/requirements`, блок `else`
в `ChatCommand` REPL.

## Ключевое отличие от `11`/`16`
`11-test-cases.md` и `16-test-cases-dorabotka.md` проверяют **ручной** флоу (`/task set`,
`/task plan <text>`, `/task next`). Этот кейс проверяет **автоматизацию**: `/task start` сам
(через `EntryStageClassifier`) выбирает стартовую стадию и генерирует её артефакт через LLM,
пользователь только подтверждает («да» → переход) или уточняет (произвольный текст →
перегенерация артефакта с feedback). Плюс — профиль-инварианты (constraints) кормят каждый
stage-промпт.

---

## Контекст

Сениор Android-разработчик, View-based стек. Профиль (предусловие) фиксирует инварианты:
`ViewBinding` для доступа к view, `Kotlin only`, запрет на Jetpack Compose и XML.

Архитектура авто-потока:
- **Один `StageAgent` на каждую стадию FSM** (clarify / planning / execution / validation / done).
- **Внутри execution — гибрид:** `PlanParser` разбивает план на шаги, на каждый шаг запускается
  свой `StepAgent` (заголовки `Шаг 1/N`).
- **LLM-вызовы делегируются в `ContextAwareAgent.chat()`** — он подставляет stage system-промпт
  (`StagePromptTemplates`) и профиль (`StageContext.profileBlock`).

**Задача-пример:**
> Экран списка задач — RecyclerView со списком Todo, FAB «+», удаление свайпом. ViewBinding для
> доступа к view, MVI + ViewModel, unit-тесты Reducer.

## Предусловие — профиль (воспроизводимо через REPL)

```text
cli-agent> /profile set style concise
cli-agent> /profile set format "programmatic view layout + Kotlin"
cli-agent> /profile set about "Android dev, View-based стек (Activities/Fragments/Conductor, custom ViewBinding, RecyclerView)"
cli-agent> /profile add constraint "no Jetpack Compose, no XML"
cli-agent> /profile add constraint "ViewBinding для доступа к view"
cli-agent> /profile add constraint "Kotlin only"
cli-agent> /profile
```

Ожидание `/profile`:
```text
👤 User profile:
  Style: concise
  Format: programmatic view layout + Kotlin
  About: Android dev, View-based стек (Activities/Fragments/Conductor, custom ViewBinding, RecyclerView)
  Constraints:
    - no Jetpack Compose, no XML
    - ViewBinding для доступа к view
    - Kotlin only
```
Эти 3 constraints попадают в `StageContext.profileBlock` и видны **каждой** стадии — основа
контрастной проверки (Сценарий D).

## Подготовка
```bash
export XDG_DATA_HOME=/tmp/cliagent-day13-auto
rm -rf $XDG_DATA_HOME
./gradlew test build          # 61 тест зелёный (вкл. 61 в stage-пакете)
./gradlew installDist
ALIAS="build/install/cli-agent/bin/cli-agent chat"
```
> FSM-логика, `awaitingAdvance`-флаг и профиль проверяются **без** LLM. Stage-генерация артефактов
> (план/реализация/вердикт) требует рабочего API-ключа.

## Что проверяем (маппинг на доработку)
1. `/task start` → авто-выбор стартовой стадии (CLARIFY/PLANNING) через `EntryStageClassifier`.
2. `StageAgent` генерирует артефакт стадии **без** ручного `/task plan|impl|verdict`.
3. Подтверждение «да» → авто-advance + артефакт следующей стадии; иной текст → перегенерация с feedback.
4. Профиль-инварианты (ViewBinding/Kotlin-only/no XML/Compose) присутствуют в артефактах всех стадий.
5. `awaitingAdvance`-флаг: `/task show` показывает «⏳ awaiting confirmation to advance».
6. Execution — гибрид: `PlanParser` разбивает план, `StepAgent` на каждый пункт (`Шаг i/N`).
7. Терминальная DONE: «да» → summary, `awaitingAdvance=false`.
8. Пауза/resume: авто-состояние (`awaitingAdvance` + артефакты) переживает restart.
9. Escape hatch: ручные `/task next|set` работают поверх авто-флоу.

---

## Сценарий A. Полный авто-прогон CLARIFY → DONE

### A1. Старт → классификатор выбирает CLARIFY (размытое описание)
```text
cli-agent> /task start Сделай экран списка задач с Todo.
```
Ожидание:
- `EntryStageClassifier` → стадия **CLARIFY** (короткое размытое описание).
- `ClarifyStageAgent` генерирует **вопросы** (`[ASK]`): какие операции? какой источник данных?
  куда сохранять? — без кода.
- `taskState(stage=CLARIFY, currentStep=..., awaitingAdvance=false)` — ждёт ответы пользователя.

### A2. Ответы пользователя → `[CLEAR]` → авто-advance на PLANNING
```text
cli-agent> RecyclerView со списком Todo, FAB «+», удаление свайпом. ViewBinding, MVI+ViewModel, unit-тесты Reducer.
```
Ожидание:
- `ClarifyStageAgent.run(feedback=<ответы>)` → `[CLEAR]` → `readyToAdvance=true`.
- **Авто-advance** CLARIFY→PLANNING в том же ходе: `PlanningStageAgent` генерирует план.
- План — нумерованный список; в нём есть `ViewBinding`/`RecyclerView`/Kotlin (из профиля).
- `taskState.requirements` сохранён (артефакт clarify).

### A3. Подтверждение → PLANNING готов, awaiting
```text
cli-agent> /task show
```
Ожидание: `Stage: planning`, `Approved plan: 1) ...`, `⏳ awaiting confirmation to advance`.
```text
cli-agent> да
```
Ожидание: авто-advance PLANNING→EXECUTION, `ExecutionStageAgent` генерирует реализацию.

### A4. Execution — гибрид (по StepAgent на пункт плана)
Ожидание: реализация содержит заголовки `Шаг 1/N`, `Шаг 2/N`... (по числу пунктов плана),
каждый шаг — код на Kotlin через ViewBinding, **без** XML/Compose.

### A5. → VALIDATION (PASS) → DONE
```text
cli-agent> да        → execution → validation (ValidationStageAgent, PASS)
cli-agent> да        → validation → done (DoneStageAgent, summary)
```
Ожидание: summary, `awaitingAdvance=false` (DONE терминальная).

---

## Сценарий B. Прямой старт на PLANNING (детальное описание)

```text
cli-agent> /task start Экран списка задач — RecyclerView со списком Todo, FAB «+», удаление свайпом. ViewBinding для доступа к view, MVI + ViewModel, unit-тесты Reducer.
```
Ожидание: `EntryStageClassifier` → **PLANNING** (детальное описание). CLARIFY минован, сразу план
выводится. `taskState.stage=PLANNING` (не CLARIFY). Это позитив-контраст к A1.

---

## Сценарий C. Уточнение артефакта текстом (feedback → перегенерация)

```text
cli-agent> /task start Экран списка задач, RecyclerView, FAB, свайп, ViewBinding, MVI, тесты.
cli-agent> /task show          → Stage: planning, ⏳ awaiting
cli-agent> добавь шаг про ItemTouchHelper для свайпа
```
Ожидание:
- Не «да» → `PlanningStageAgent.run(feedback="добавь шаг про ItemTouchHelper...")`.
- План перегенерирован, содержит `ItemTouchHelper`. **Стадия та же** (PLANNING),
  `awaitingAdvance=true`.
```text
cli-agent> нет            ← тоже трактуется как «не подтверждение» → уточнение/перезапрос
```
Ожидание: стадия не сменилась (не advance). Подтверждает правило «всё кроме да = feedback».

---

## Сценарий D. Профиль-инварианты в каждой стадии (контрастная проверка)

Один и тот же авто-прогон; проверяем артефакт каждой стадии на 4 оси:

| Стадия | ViewBinding/RecyclerView | Kotlin | Compose | XML |
|---|---|---|---|---|
| planning (план) | ✅ упомянут | ✅ | ❌ отсутствует | ❌ отсутствует |
| execution (код) | ✅ `.bind(...)`/Adapter | ✅ | ❌ | ❌ |
| validation (вердикт) | ✅ проверено | ✅ | ❌ «нет Compose» | ❌ «нет XML» |
| done (summary) | ✅ | ✅ | ❌ | ❌ |

> Негативный сигнал: если в коде исполнения появился `setContent { }` (Compose) или
> `R.layout.*` (XML) → `profileBlock` не доходит до `StageAgent`. Проверить, что constraints
> реально в `StageContext.profileBlock` (см. `PlanningStageAgentTest.profile block with constraints`).

---

## Сценарий E. Гибрид execution — число шагов = число пунктов плана

```text
cli-agent> /task plan 1) TodoAdapter (RecyclerView) 2) TodoIntent/State/Reducer 3) TodoViewModel 4) Fragment wire + ItemTouchHelper 5) Reducer unit-тесты
cli-agent> /task next    → execution
cli-agent> /task show
```
Ожидание: implementation содержит ровно заголовки `Шаг 1/5`...`Шаг 5/5` — `PlanParser.parse`
разбил план на 5 шагов, `StepAgent` отработал по разу на каждый. Если план — prose (без
нумерации) → `parseOrWhole` отдаёт весь план одним шагом.

---

## Сценарий F. Escape hatch поверх авто-флоу

```text
cli-agent> /task start экран списка задач
cli-agent> /task set execution
```
Ожидание: `✓ Stage set to: execution` (force, **без** `canAdvance`-проверки) — ручной override
поверх авто-потока работает. `/task next`/`/task plan|impl|verdict` также доступны как запасной
ручной путь (см. `11-test-cases.md`).

---

## Сценарий G. Пауза + resume (авто-состояние переживает restart)

```text
cli-agent> /task start экран списка задач, ViewBinding, MVI
cli-agent> /task show    → Stage: planning, ⏳ awaiting, Approved plan: 1) ...
/exit                    ← ПАУЗА
```
```bash
CHATID=$(ls $XDG_DATA_HOME/chats/*.json | head -1 | xargs basename | sed 's/.json//')
$ALIAS -c "$CHATID"      ← RESUME
```
```text
cli-agent> /task show
```
Ожидание: `Stage: planning`, `⏳ awaiting confirmation to advance`, `Approved plan: ...` —
новые поля `awaitingAdvance`/`requirements` пережили restart (schema-evolution, дефолты).
```text
cli-agent> да            → execution (авто-флоу продолжается после restart)
```

---

## Сценарий H. Граничные/негативные кейсы авто-потока

| # | Ввод | Ожидание |
|---|---|---|
| H1 | `/task start` (без описания) | `Usage: /task start <description>` |
| H2 | `да` без активной задачи | обычный чат (`handleUserInput` → null → `agent.chat`) |
| H3 | DONE → `да` | `🏁 Задача уже завершена. Начни новую через /task start ...` |
| H4 | `нет` при awaiting | стадия НЕ меняется → перегенерация артефакта (feedback) |
| H5 | Произвольный текст при awaiting | уточнение артефакта (feedback), стадия та же |
| H6 | classifier LLM-ошибка/мусор | fallback → PLANNING (`EntryStageClassifier`, не застреваем) |
| H7 | `/task reset` посреди авто-флоу | `taskState=nil`, но `currentTask`/профиль сохранены |
| H8 | blank description → classifier | PLANNING без LLM-вызова (short-circuit) |

---

## Сценарий I. Автоматизированные тесты (`./gradlew test`)

| Тест-класс | Что проверяет | Пример данных |
|---|---|---|
| `TaskOrchestratorTest` (10) | `startTask` PLANNING→план+awaiting; CLARIFY+CLEAR→авто PLANNING; CLARIFY+ASK→ждёт; «да»→advance+артефакт; произвольный текст→feedback; clarify-loop до CLEAR; полный pipeline planning→done; null без задачи; DONE терминальная | `routingLlm("PLANNING","1) Setup")` → `approvedPlan` сохранён, `awaitingAdvance=true` |
| `EntryStageClassifierTest` (7) | CLARIFY/PLANNING по ответу LLM; case-insensitive; мусор→PLANNING; LLM-error→PLANNING; blank→PLANNING без вызова; оба слова→CLARIFY | `llm("CLARIFY")` → `TaskStage.CLARIFY` |
| `ClarifyStageAgentTest` (7) | `[CLEAR]`→requirements+ready; `[ASK]`→вопросы+не ready; без маркера→вопросы; case-insensitive; blank CLEAR→описание; feedback в промпте | `chat("[CLEAR] стек Kotlin")` → `artifact="стек Kotlin"` |
| `PlanningStageAgentTest` (6) | весь ответ=план; requirements в промпте; feedback в промпте; blank→не ready; profile constraints в промпте | `chat("1) Setup\n2) Impl")` → `artifact` = весь план |
| `ExecutionStageAgentTest` (6) | по StepAgent на шаг (N вызовов); заголовки `Шаг i/N`; prose→1 шаг; пустой план→прямая имплементация; feedback→refine-pass | план 3 шага → 3 LLM-вызова, артефакт содержит `result-1..3` |
| `ValidationStageAgentTest` (6) | PASS→ready; REWORK→не ready; оба→REWORK; без маркера→не ready; план+impl в промпте | `chat("... PASS")` → `readyToAdvance=true` |
| `DoneStageAgentTest` (5) | summary-артефакт; терминальная (ready=false); blank→fallback; все артефакты в промпте | `chat("Калькулятор готов")` → `display.contains("Задача завершена")` |
| `StepAgentTest` (3) | один шаг→результат; план+шаг+done в промпте; profile-block | `run("Impl Calculator", ...)` → промпт содержит план и шаг |
| `PlanParserTest` (11) | `1)`/`1.`/`1:`/`-`/`*`; многострочные; blank-разделитель; преамбула; пустой/prose→empty; `parseOrWhole` fallback | `"1) A\n2) B"` → 2 шага |
| `ChatDataSchemaEvolutionTest` (+3 day-13 auto) | legacy JSON без `awaitingAdvance`/`requirements`→дефолты; round-trip с новыми полями | `{"stage":"PLANNING",...}` без новых полей → `awaitingAdvance=false` |

---

## Чек-лист приёмки
- [x] `/task start` → авто-выбор стартовой стадии через `EntryStageClassifier` — Сценарий A1, B.
- [x] `StageAgent` генерирует артефакт без ручных `/task plan|impl|verdict` — A2–A5.
- [x] «да» → авто-advance + следующий артефакт; текст → feedback-перегенерация — A3, C.
- [x] Профиль-инварианты во всех артефактах (ViewBinding/Kotlin, без Compose/XML) — D.
- [x] `awaitingAdvance`-флаг в `/task show` — A3, G.
- [x] Execution — гибрид (PlanParser + StepAgent на каждый пункт) — E.
- [x] Терминальная DONE → summary, `awaitingAdvance=false` — A5.
- [x] Пауза/resume: авто-состояние переживает restart — G.
- [x] Escape hatch `/task next|set` поверх авто-флоу — F.

## Зависимости
Задача 15 (верификация). Расширяет `16-test-cases-dorabotka.md` (ручной artifact-gated флоу)
автоматизированным потоком (`TaskOrchestrator` + `EntryStageClassifier` + stage-агенты) и
профиль-инвариантами (`StageContext.profileBlock`). Реализация: `com.cliagent.agent.stage.*`,
правки `TaskState` (`awaitingAdvance`/`requirements`) и блока `else` в `ChatCommand`.
