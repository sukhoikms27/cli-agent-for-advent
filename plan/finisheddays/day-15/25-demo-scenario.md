# Задача 25. Демо-сценарий дня 15 (test-cases)

## Контекст
Демонстрация полного жизненного цикла дня 15: контролируемые переходы + StatefulAgent + авто-роутинг
+ уведомления + режимы + динамический спиннер. Контекст задачи-примера — тот же, что в day-13/14
(Android-разработчик, View-based, MVI+ViewModel, unit-тесты) — для континуитета между демо.

**Стек (фиксированный):** Kotlin, ViewBinding (НЕ Compose), MVI + ViewModel, RecyclerView,
unit-тесты. Это важно для сценария H (инвариант no-compose).

## Предусловие
Профиль и инварианты для сценария H задаются через REPL; остальные сценарии работают без них.

## Подготовка

```bash
export XDG_DATA_HOME=/tmp/cliagent-day15-test
rm -rf $XDG_DATA_HOME
./gradlew test build          # ~174 теста зелёные (день 14 ~130 + день 15 ~44)
./gradlew installDist
ALIAS="build/install/cli-agent/bin/cli-agent chat"
```

> Сценарии A, B, C, D, G — без LLM (чистая логика guard/режимы/persists). E, F, H — требуют LLM
> (авто-роутинг, stage-генерация, инвариант-judge).

## Что проверяем (маппинг на задание курса)

1. «Допустимые состояния + разрешённые переходы» → Сценарий A
2. «Не мог перепрыгнуть этап» → Сценарий B
3. «Нельзя реализация без плана / финал без валидации» → Сценарий B
4. «Реакция ассистента на недопустимый переход» → Сценарий C
5. «Продолжение после паузы» → Сценарий G
6. (п.1) Авто-роутинг «вопрос vs задача» → Сценарий A (часть 2)
7. (п.2) Уведомления по стадиям → Сценарий E
8. (п.3) Режимы manual/plan/auto → Сценарий D
9. (п.4) Динамический спиннер → Сценарий F
10. StatefulAgent + инварианты в stage-flow → Сценарий H

---

## Сценарий A. Допустимые состояния, переходы и авто-роутинг (БЕЗ LLM часть 1)

**A1. Заголовок и default-режим:**
```text
cli-agent> (старт)
```
**Ожидание:**
```
CLI Agent v0.8 | Chat: <id> | Model: <m> | Context: sliding | Compress: OFF | Invariants: OFF | Mode: plan
```

**A2. Команда `/task` без активной задачи:**
```text
cli-agent> /task
```
**Ожидание:** `📋 No active task. Use: /task start <description>`

**A3. Старт задачи и проверка стадий:**
```text
cli-agent> /task start реализуй экран настроек профиля на ViewBinding+MVI
```
**Ожидание:** оркестратор выбирает стартовую стадию (CLARIFY/PLANNING), генерирует артефакт.
*Если CLARIFY:* `❓ Уточнение требований` + вопросы. *Если PLANNING:* `📋 Планирование` + план.
`/task` показывает `Stage: <stage>`, `awaitingAdvance: true`.

**A4. Авто-роутинг (PLAN, часть 2) — ТРЕБУЕТ LLM:**
```text
cli-agent> /task reset
cli-agent> что такое паттерн Reducer в MVI?
```
**Ожидание:** IntentClassifier → QUESTION → обычный чат-ответ про Reducer. FSM НЕ стартует
(`taskState == null` после reset).

```text
cli-agent> спроектируй и реализуй ProfileReducer с unit-тестами
```
**Ожидание:** IntentClassifier → TASK → автостарт FSM (без `/task start`), появляется артефакт
первой стадии.

---

## Сценарий B. Перепрыгивание → блок; артефактный gate (БЕЗ LLM)

**B1. Попытка перепрыгнуть planning→done:**
```text
cli-agent> /task reset
cli-agent> /task start задача X
cli-agent> /task set done
```
**Ожидание:**
```
⚠️ ⛔ Blocked: illegal transition planning→done. Allowed: planning, execution. Use --force to override.
```
Стадия НЕ меняется (`/task` → `Stage: planning`).

**B2. Попытка execution без плана (artifact-gate):**
```text
cli-agent> /task set execution
```
**Ожидание:**
```
⚠️ ⛔ Blocked: stage planning not ready (missing artifact). Set approved plan: /task plan <text> Use --force to override.
```

**B3. Заполняем план → переход разрешён:**
```text
cli-agent> /task plan "1) ProfileReducer 2) ProfileViewModel 3) unit-тесты"
cli-agent> /task next
```
**Ожидание:** `✓ Advanced to stage: execution` (план есть, gate пройден).

**B4. Попытка done из execution (без validation):**
```text
cli-agent> /task set done
```
**Ожидание:** `⛔ Blocked: illegal transition execution→done. Allowed: execution, validation, planning.`

---

## Сценарий C. Реакция на недопустимый + `--force` escape (БЕЗ LLM)

**C1. Информативное сообщение о блокировке:**
Из B4 — сообщение содержит `Allowed: execution, validation, planning` (что МОЖНО) + `Use --force`.

**C2. `--force` как осознанный escape:**
```text
cli-agent> /task set done --force
```
**Ожидание:** `✓ Stage set to: done (forced)`. `/task` → `stageHistory` содержит `execution→done (forced)`.

**C3. `-f` alias:**
```text
cli-agent> /task set planning -f
```
**Ожидание:** `✓ Stage set to: planning (forced)`.

**C4. `/task done` уважает gate из validation:**
```text
cli-agent> /task set validation --force
cli-agent> /task done
```
**Ожидание:** `⛔ Blocked: validation not ready (missing verdict). Set verdict: /task verdict <text>`
(нет verdict → блок).

```text
cli-agent> /task verdict "все тесты зелёные, PASS"
cli-agent> /task done
```
**Ожидание:** `✓ Task done.` (verdict есть, gate пройден).

---

## Сценарий D. Режимы manual / plan / auto (контраст) — частично LLM

**D1. MANUAL — роутинг off:**
```text
cli-agent> /reset
cli-agent> /mode manual
cli-agent> спроектируй и реализуй сложный модуль аутентификации
```
**Ожидание:** обычный чат-ответ (роутинг off в MANUAL). FSM НЕ стартует. `Mode: manual` в заголовке.

**D2. PLAN — подтверждение переходов (default):**
```text
cli-agent> /mode plan
cli-agent> реализуй ProfileViewModel с unit-тестами
```
**Ожидание:** автостарт FSM (роутинг on). После генерации артефакта — `✅ Перейти к стадии
EXECUTION? Ответьте да — продолжить`. Пользователь подтверждает словом.

**D3. AUTO — полная автоматизация:**
```text
cli-agent> /reset
cli-agent> /mode auto
cli-agent> реализуй простую функцию сложения two integers с тестом
```
**Ожидание:** автостарт → авто-проход clarify→planning→execution→validation→done БЕЗ запросов «да».
На каждой стадии — уведомление (`📋 Планирование` → `📦 Утверждённый план готов` → `⏭ → Реализация` → …).

**D4. AUTO уважает guard (перепрыгивание блокируется):**
Даже в AUTO нелегальный переход невозможен — оркестратор делает только канонический forward через
`attemptTransition`. Проверить: AUTO не «перепрыгивает» через validation; если артефакт не готов —
авто-advance блокируется (ArtifactMissing), пользователь видит уведомление.

---

## Сценарий E. Уведомления по стадиям (п.2) — ТРЕБУЕТ LLM

В AUTO-режиме (D3) наблюдать последовательность уведомлений:
```
❓ Уточнение требований      (если CLARIFY)
[вопросы агенту]
⏭ → Планирование
📋 Планирование
[план]
📦 Утверждённый план готов
⏭ → Реализация
⚙️ Реализация
[реализация]
📦 Реализация готова
⏭ → Валидация
🔍 Валидация
[вердикт]
📦 Вердикт готов
⏭ → Завершение
🏁 Завершение
[итог]
```
Пользователь видит прогресс и артефакты каждой стадии, даже не подтверждая переходы.

---

## Сценарий F. Динамический спиннер (п.4) — ТРЕБУЕТ LLM

В AUTO-режиме (D3) наблюдать спиннер во время stage-выполнения:
- Во время planning-генерации: `⠋ Planning…` (не `Thinking…`)
- После перехода к execution: `⠙ Executing…`
- Во время validation: `⠹ Validating…`
- При отсутствии задачи (обычный чат): `⠼ Thinking…`

Лейбл перечитывается каждые 120ms (`spinnerLabel(agent)`), отражая актуальную стадию. Особенно
наглядно в AUTO, где стадии сменяются автоматически.

**Негативный сигнал:** если лейбл всегда «Thinking…» — механизм не работает (call-site не переведён
на динамический overload, задача 22).

---

## Сценарий G. Pause/resume (персистентность) — БЕЗ LLM для проверки state

**G1. Прогресс до паузы:**
```text
cli-agent> /task start задача для resume
cli-agent> /task plan "шаг А, шаг Б"
cli-agent> /task next
cli-agent> /exit
```

**G2. Проверка персистентности (вне REPL):**
```bash
CHATID=$(ls $XDG_DATA_HOME/chats/*.json | head -1 | xargs basename | sed 's/.json//')
python3 -c "import json;d=json.load(open('$XDG_DATA_HOME/chats/$CHATID.json'));print(json.dumps(d['workingMemory']['taskState'],indent=2,ensure_ascii=False))"
```
**Ожидание:** `stage: execution`, `approvedPlan` на месте, `stageHistory` содержит `planning→execution`.

**G3. Resume:**
```bash
$ALIAS -c "$CHATID"
```
```text
cli-agent> /task
```
**Ожидание:** `Stage: execution`, артефакты на месте, `awaitingAdvance: false`. Агент продолжает без
повторных объяснений (состояние восстановлено из `workingMemory.taskState`).

**G4. Режим тоже персистится:**
Если перед `/exit` был `/mode auto`, после resume `Mode: auto` в заголовке.

---

## Сценарий H. StatefulAgent + инварианты в stage-flow (gap №6) — ТРЕБУЕТ LLM

**H1. Запуск с инвариантами:**
```bash
$ALIAS --invariants
```
```text
cli-agent> /invariants add BAN no-compose Использовать только ViewBinding, НЕ Jetpack Compose
cli-agent> /invariants
```
**Ожидание:** инвариант `no-compose` в списке, `Invariants: ON` в заголовке.

**H2. Попытка провести Compose через stage-поток:**
```text
cli-agent> /mode auto
cli-agent> реализуй экран настроек на Jetpack Compose с MVI
```
**Ожидание (gap №6 закрыт):** даже в AUTO stage-потоке инвариант срабатывает — stage-вызовы идут
через `StatefulAgent.chat` → `InvariantGuard`. Если execution-агент предлагает Compose в реализации,
ответ блокируется (`⛔`), retry-loop (до 3 раз), fallback с `⚠️`. Пользователь видит, что инвариант
защищает stage-flow, а не только свободный чат.

**H3. Контраст — корректный стек проходит:**
```text
cli-agent> /reset
cli-agent> реализуй экран настроек на ViewBinding с MVI
```
**Ожидание:** AUTO-проход проходит нормально (ViewBinding не нарушает `no-compose`), доходит до DONE.

---

## Граничные/негативные кейсы

| # | Ввод | Ожидание |
|---|---|---|
| E1 | `/task set foo` (несуществующая стадия) | `Unknown stage: foo. Use: clarify, planning, execution, validation, done` |
| E2 | `/task set` (без стадии) | `Usage: /task set <...> [--force]` |
| E3 | `/task next` без активной задачи | `No active task. Use: /task start <description>` |
| E4 | `/mode foo` | `Usage: /mode [manual\|plan\|auto]. Unknown mode: foo.` |
| E5 | `/task set execution --force` из planning без плана | `Stage set to: execution (forced)` (force обходит gate) |
| E6 | `/task back` после `--force` | откат на предыдущую стадию по history |
| E7 | resume чата с `interactionMode=auto` | `Mode: auto` в заголовке (персистентность) |
| E8 | blank ввод при активной задаче | orchestrator: уточнение (PLAN) или advance (AUTO, но blank не «да») |

## Автоматизированные тесты (ссылка)

| Тест-класс | Что проверяет | Сценарий демо |
|---|---|---|
| `TransitionGuardTest` | все ветки guard + боковые + force + self | B, C |
| `IntentClassifierTest` | QUESTION/TASK/fallback/blank | A (часть 2) |
| `StatefulAgentTest` | делегирование + инварианты opt-in + expose | H |
| `TaskStateMachineTest` (+allowedTargets) | FSM + легальные цели | A, B |
| `ContextAwareAgentTaskStateTest` (+attemptTransition) | аксессор перехода | B, C, G |
| `ChatDataSchemaEvolutionTest` (+interactionMode) | schema-evolution режима | D, G |

## Чек-лист приёмки (соответствие заданию курса + доптребованиям)

- [x] «Допустимые состояния» — A1, A3 (TaskStage + allowedTargets в сообщениях B/C)
- [x] «Разрешённые переходы» — A3, B3 (канонический forward работает)
- [x] «Не мог перепрыгнуть этап» — B1, B4, C1 (⛔ без --force)
- [x] «Нельзя реализация без плана» — B2 (ArtifactMissing)
- [x] «Нельзя финал без валидации» — B4, C4 (Illegal/ArtifactMissing при →done)
- [x] «Реакция на недопустимый» — C1 (⛔ + Allowed + Use --force)
- [x] «Продолжение после паузы» — G (resume без повторных объяснений)
- [x] (п.1) Авто-роутинг вопрос/задача — A4 (QUESTION→чат, TASK→FSM), MANUAL off в D1
- [x] (п.2) Уведомления по стадиям — E (структурные 📋/⚙️/🔍/🏁 в AUTO)
- [x] (п.3) Режимы manual/plan/auto — D (контраст трёх режимов)
- [x] (п.4) Динамический спиннер — F (Planning…/Executing…/Validating…)
- [x] StatefulAgent + инварианты в stage-flow — H (gap №6 закрыт, Compose блокируется в AUTO)
- [x] Инвариант совместимости — default PLAN = поведение дня 14 (S11 в верификации)

## Зависимости (задачи)
24 (верификация). Расширяет S1–S11 в полноценный демо-кейс с контекстом задачи-примера.
