# Задача 11. Кейс тестирования стейт-машины задачи (Day 13)

Пошаговый тест-кейс для верификации задания Day 13 (состояние задачи как конечный автомат).
Привязан к реальной реализации: `TaskStage`, `TaskState`, `TaskStateMachine`,
`WorkingMemory.taskState`, `ContextAwareAgent` (`getTaskState/setTaskState/advanceTaskState/
revertTaskState`), `handleTask` в `ChatCommand`.

## Контекст задачи-примера
Сениор Android-разработчик строит экран **программного View-based UI** — вьюхи создаются в
Kotlin-коде (`TextView(this)`, `addView()`), **без XML-разметок, без ViewBinding, без Compose**.
Архитектура — MVI + ViewModel, обязательны unit-тесты.

Стейт-машина ведёт работу по этапам: `clarify → planning → execution → validation → done`.
Проверяем: переходы, принудительные смены стадии, откат, паузу и resume без повторных
объяснений, инъекцию состояния в промпт.

## Что проверяем (маппинг на задание курса)
1. Состояние задачи как конечный автомат (`TaskStateMachine`, `TaskStage`).
2. Этап / текущий шаг / ожидаемое действие (`TaskState`).
3. Переходы `clarify → planning → execution → validation → done` с allowed-transitions.
4. Пауза на любом этапе (выход из REPL, состояние персистится).
5. Продолжение без повторных объяснений (resume + инъекция в prompt).
6. Принудительные переходы (`forceSet`) и откат (`back`).
7. Инъекция состояния в каждый запрос (`PromptBuilder`).
8. Инвариант совместимости (без новых флагов поведение = Day 12).

## Подготовка
```bash
export XDG_DATA_HOME=/tmp/cliagent-day13-test
rm -rf $XDG_DATA_HOME
./gradlew test build          # 62 теста зелёные
./gradlew installDist
ALIAS="build/install/cli-agent/bin/cli-agent chat"
```
Чистый `XDG_DATA_HOME` — чтобы изолировать чат от прошлых сессий.

---

## Сценарий A. Полный жизненный цикл FSM

Зонд-задача: `Экран настроек профиля: поля имя/email, переключатель уведомлений, кнопка Save.
Программный View-based, без XML/Compose/ViewBinding, MVI, ViewModel, unit-тесты.`

### A1. Запуск задачи → planning
```text
cli-agent> /task start Экран настроек профиля: поля имя/email, переключатель уведомлений, кнопка Save. Программный View-based, без XML/Compose/ViewBinding, MVI, ViewModel, unit-тесты.
cli-agent> /task show
```
Ожидаемый `/task show`:
```text
📋 Task state:
  Stage: planning
  Current step: Экран настроек профиля: поля имя/email, переключатель ...
```
`/task start` ставит `currentTask` + `taskState(stage=PLANNING, currentStep=<описание>)` одним
вызовом (без двойной персистентности).

### A2. Уточнение (clarify) — откат в начало
```text
cli-agent> /task set clarify
cli-agent> /task expect собрать недостающие требования: валидация email, формат имени, состояние Save
cli-agent> /task show
```
Ожидание: warn `Illegal transition planning→clarify; forcing anyway.` (переход назад не разрешён
правилами → force), но стадия `clarify`, expected action установлен. History: `planning→clarify
(forced)`.
```text
cli-agent> /task next        → clarify → planning (разрешён канонически)
cli-agent> /task show
```
Ожидание: `Stage: planning`, history пополнился `clarify→planning`.

### A3. Planning — утверждённый план
```text
cli-agent> /task plan 1) ProfileSettingsView(programmatic) 2) ProfileIntent/State/Reducer 3) ProfileViewModel 4) Fragment wire 5) Reducer unit-тесты
cli-agent> /task show
```
Ожидание: `Approved plan: 1) ProfileSettingsView...` отображается в `/task show`.

### A4. Execution — переход вперёд + шаги
```text
cli-agent> /task next        → planning → execution
cli-agent> /task step ProfileSettingsView: программно собираю LinearLayout + EditText + Switch + Button
cli-agent> /task expect Reducer unit-тесты зелёные
cli-agent> /task show
```
Ожидание:
```text
📋 Task state:
  Stage: execution
  Current step: ProfileSettingsView: программно собираю LinearLayout + EditText + Switch + Button
  Expected action: Reducer unit-тесты зелёные
  Approved plan: 1) ProfileSettingsView(programmatic) ...
  History:
    - planning→clarify (forced)
    - clarify→planning
    - planning→execution
```

### A5. Validation — проверка
```text
cli-agent> /task next        → execution → validation
cli-agent> /task expect все unit-тесты Reducer + ViewModel зелёные, нет XML/Compose/ViewBinding в коде
cli-agent> /task show
```
Ожидание: `Stage: validation`, expected action обновлён.

### A6. Done
```text
cli-agent> /task done        → из validation → done (канонический переход)
cli-agent> /task show
```
Ожидание: `Stage: done`, history пополнился `validation→done`.

---

## Сценарий B. Пауза + resume без повторных объяснений (ключевое требование курса)

### B1. Сессия 1 — работа до паузы
```text
cli-agent> /task start Экран профиля, programmatic View-based, MVI, тесты
cli-agent> /task next                              → execution
cli-agent> /task step пишу ProfileReducer
cli-agent> /task expect тесты Reducer проходят
/exit                                              ← ПАУЗА
```

### B2. Проверка персистентности на диске
```bash
CHATID=$(ls $XDG_DATA_HOME/chats/*.json | head -1 | xargs basename | sed 's/.json//')
python3 -c "import json;d=json.load(open('$XDG_DATA_HOME/chats/$CHATID.json'));print(json.dumps(d['workingMemory']['taskState'],ensure_ascii=False,indent=2))"
```
Ожидаемое содержимое `taskState`:
```json
{
  "stage": "EXECUTION",
  "currentStep": "пишу ProfileReducer",
  "expectedAction": "тесты Reducer проходят",
  "stageHistory": [{"from":"PLANNING","to":"EXECUTION","at":"..."}]
}
```

### B3. Сессия 2 — resume
```bash
$ALIAS -c "$CHATID"
```
```text
cli-agent> /task show
```
Ожидание: состояние на месте — `Stage: execution`, `Current step: пишу ProfileReducer`,
`Expected action: тесты Reducer проходят`, history `planning→execution`.
```text
cli-agent> продолжи с того места
```
Ожидание: агент отвечает с учётом стадии/шага/ожидаемого действия (видит блок `Task state:` в
system prompt) и **не просит заново объяснять задачу** — это и есть «resume without re-explanation».
Проверить, что в ответе нет «расскажите задачу подробнее» / «что нужно сделать?».
```text
cli-agent> /task next        → execution → validation
```
Ожидание: переход работает после рестарта (FSM восстановлен из персистентности).

---

## Сценарий C. Принудительные переходы и откаты

### C1. Запрещённый переход с force
```text
cli-agent> /task start экран профиля
cli-agent> /task set validation
```
Ожидание: warn `Illegal transition planning→validation; forcing anyway.` + `Stage set to:
validation`, history: `planning→validation (forced)`.

### C2. `/task back` — откат по history
```text
cli-agent> /task back
cli-agent> /task show
```
Ожидание: `Stage: planning` (восстановлен `from` последней записи `planning→validation (forced)`),
history очищена от этой записи.

### C3. `/task back` на пустой history
```text
cli-agent> /task back
```
Ожидание: `⚠️ No stage history to revert.` (history пуста после C2).

### C4. `/task next` из DONE
```text
cli-agent> /task set done      (forced)
cli-agent> /task next
```
Ожидание: `⚠️ No next stage from done (already done?).` (`next(DONE)=null`).

### C5. Реворк: validation → execution (разрешён)
```text
cli-agent> /task set execution
cli-agent> /task next          → execution → validation
cli-agent> /task set execution  (validation→execution — разрешён, без warn)
```
Ожидание: `Stage: execution`, transition без предупреждения о force (т.к.
`isAllowed(validation, execution)=true`).

---

## Сценарий D. Инъекция состояния в промпт (агент учитывает стадию)

Доказательство, что `PromptBuilder` вставляет `Task state:` в каждый запрос.

### D1. Жёсткая проверка через артефакт
Поставить задачу в `execution` с конкретным шагом, отправить запрос и убедиться, что ответ
релевантен текущему шагу (а не стартовой постановке):
```text
cli-agent> /task start экран профиля, programmatic View-based
cli-agent> /task next
cli-agent> /task step ProfileReducer: handle Intent.SaveProfile
cli-agent> покажи Reducer для SaveProfile
```
Ожидание: ответ содержит `Reduce`/`when(intent)` для `SaveProfile` — т.е. агент «знает» текущий
шаг, а не пересказывает весь план.

### D2. Смена шага «на лету» меняет ответ
```text
cli-agent> /task step ProfileViewModel: expose StateFlow
cli-agent> покажи ViewModel
```
Ожидание: ответ про `StateFlow`/`ViewModel`, а не про Reducer — профиль шага подхвачен из live
`WorkingMemory` перед запросом.

> Негативный сигнал: если ответ не меняется при смене `/task step` → состояние не подмешивается
> в промпт.

---

## Сценарий E. Граничные/негативные кейсы `/task`

| # | Ввод | Ожидание |
|---|---|---|
| E1 | `/task` (без аргументов) без активной задачи | `📋 No active task. Use: /task start <description>` |
| E2 | `/task start` (без описания) | `Usage: /task start <description>` |
| E3 | `/task next` без активной задачи | `No active task. Use: /task start <description>` |
| E4 | `/task set foo` (несуществующая стадия) | `Unknown stage: foo. Use: clarify, planning, execution, validation, done` |
| E5 | `/task step` (без текста) | `Usage: /task step <text>` |
| E6 | `/task set EXECUTION` (uppercase) | `Stage set to: execution` (case-insensitive через `valueOf(uppercase())`) |
| E7 | `/task reset` → `/task show` | `No active task`, но `currentTask`/`plan` в WorkingMemory сохранены |
| E8 | `/task done` из `execution` (не validation) | `Task done. (forced from execution)` — force, т.к. `execution→done` не разрешён |
| E9 | `/reset` (общий) → `/task show` | `No active task` (workingMemory очищена → taskState=nil) |

---

## Сценарий F. Автоматизированные тесты (`./gradlew test`)

Соответствие артефакту `09-tests.md`:

| Тест-класс | Что проверяет | Пример данных |
|---|---|---|
| `TaskStateMachineTest` (14) | `isAllowed` (7 пар + self), запрещённые (`PLANNING→DONE` и др.); `next`/`next(DONE)=null`; `transition` кидает на незаконном; `forceSet` с `note="forced"`; self-transition не пишет history; `back` восстанавливает `from`, `null` на пустой | `transition(TaskState(stage=PLANNING), DONE)` → `IllegalArgumentException` |
| `ContextAwareAgentTaskStateTest` (7) | `setTaskState` персистит/читается; не затирает `currentTask`/`plan`/`decisions`; `setTaskState(null)` чистит только FSM; `advanceTaskState` канонические стадии + `null` из DONE; `revertTaskState` по history; состояние инжектится в system prompt | `slot<ChatRequest>` → `content` содержит `Task state:` + `Stage: execution` |
| `PromptBuilderTest` (+3) | `taskState` рендерит блок (`Stage:` lowercase, step/expect/plan); `taskState==null` не рендерит | `WorkingMemory(taskState=TaskState(stage=EXECUTION, currentStep="wire"))` |
| `ChatDataSchemaEvolutionTest` (+2) | legacy WorkingMemory JSON без `taskState` → `null`; round-trip с taskState | `{"currentTask":"old","taskDecisions":[]}` → `taskState==null` |

---

## Чек-лист приёмки (соответствие заданию курса)
- [x] Состояние задачи как конечный автомат — `TaskStateMachine`, `TaskStage`.
- [x] Этап / текущий шаг / ожидаемое действие — `TaskState` (stage / currentStep / expectedAction).
- [x] Переходы `clarify → planning → execution → validation → done` с allowed-rules — Сценарий A.
- [x] Пауза на любом этапе — Сценарий B1 (выход из REPL, состояние персистится в JSON).
- [x] Продолжение без повторных объяснений — Сценарий B3 (resume + инъекция в prompt).
- [x] Принудительные переходы и откат — Сценарий C.
- [x] Инъекция состояния в каждый запрос — Сценарий D.
- [x] Инвариант совместимости — без новых флагов поведение = Day 12 (профиль не затронут).

## Зависимости
Задача 10 (верификация). Этот файл расширяет manual-REPL часть `10-verification.md`
конкретными пошаговыми данными и Android-контекстом (programmatic View-based).
