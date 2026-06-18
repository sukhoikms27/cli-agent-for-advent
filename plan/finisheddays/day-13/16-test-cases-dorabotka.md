# Задача 16 (доработка Day 13). Кейс тестирования — один конкретный пример

Пошаговый кейс для верификации Day 13 с доработкой (stage-enforcing промпты + детерминированные
artifact-gated переходы, Вариант 2 автора курса). Один сквозной пример.

## Контекст
Сениор Android-разработчик строит экран **программного View-based UI** (вьюхи в Kotlin-коде, без
XML/Compose/ViewBinding), MVI + ViewModel, unit-тесты. Стейт-машина ведёт по стадиям
`clarify → planning → execution → validation → done` с artifact-gated hard-block переходами и
stage-enforcing промптами.

**Задача:** `Экран настроек профиля: поля имя/email, переключатель уведомлений, кнопка Save.
Программный View-based, без XML/Compose/ViewBinding, MVI, ViewModel, unit-тесты.`

## Подготовка
```bash
export XDG_DATA_HOME=/tmp/cliagent-day13
rm -rf $XDG_DATA_HOME
./gradlew test build installDist
ALIAS="build/install/cli-agent/bin/cli-agent chat"
```
> `/task`-команды и gating проверяются без LLM. Stage-поведение (clarify задаёт вопросы и т.д.)
> требует рабочего API-ключа.

## Что проверяем
1. FSM `clarify → planning → execution → validation → done` с allowed-transitions.
2. Stage-enforcing промпты — поведение per stage.
3. Artifact-gated hard-block: `/task next` без `plan`/`impl`/`verdict` отказывает.
4. Escape hatch `/task set` минует gate.
5. Артефакты `approvedPlan`/`implementation`/`verdict` рендерятся и персистятся.
6. Пауза/resume без повторных объяснений.
7. Инвариант совместимости (без `/task start` — обычный чат).

---

## Полный жизненный цикл (один проход)

### 1. Старт → planning
```text
cli-agent> /task start Экран настроек профиля: поля имя/email, переключатель уведомлений, кнопка Save. Программный View-based, без XML/Compose/ViewBinding, MVI, ViewModel, unit-тесты.
```
Ожидание: `✓ Task started: ... (stage: planning)`. `currentTask` и `taskState(stage=PLANNING)`
поставлены одним вызовом.

### 2. Clarify — stage-prompt заставляет уточнять
```text
cli-agent> /task set clarify
cli-agent> что мне нужно учесть?
```
Ожидание:
- `⚠️ Illegal transition planning→clarify; forcing anyway.` (переход назад не разрешён → force),
  стадия `clarify`.
- Ответ агента — **вопросы** (валидация email? формат имени? состояние Save после клика? где
  хранить?), **без кода**. Это stage-prompt CLARIFY в действии.

```text
cli-agent> /task next        → clarify→planning (canAdvance=true, clarify опц.)
```

### 3. Planning — hard-block без артефакта
```text
cli-agent> /task next
```
Ожидание: `⚠️ Stage planning not ready: missing artifact. Set approved plan: /task plan <text>` —
**HARD BLOCK**, стадия НЕ меняется.

```text
cli-agent> /task plan 1) ProfileSettingsView (программный LinearLayout+EditText+Switch+Button) 2) ProfileIntent/State/Reducer 3) ProfileViewModel (StateFlow) 4) Fragment wire 5) Reducer unit-тесты
cli-agent> /task next        → planning→execution (approvedPlan готов)
```

### 4. Execution — hard-block без implementation
```text
cli-agent> /task next
```
Ожидание: `⚠️ Stage execution not ready: missing artifact. Set implementation: /task impl <text>` —
HARD BLOCK.

```text
cli-agent> /task impl ProfileSettingsView + ProfileReducer + ProfileViewModel готовы, код на Kotlin, без XML/Compose/ViewBinding
cli-agent> /task next        → execution→validation
```
(Опц.: в execution спроси `покажи Reducer для SaveProfile` — ответ должен быть кодом на Kotlin,
programmatic View-based, без XML.)

### 5. Validation — hard-block без verdict
```text
cli-agent> /task next
```
Ожидание: `⚠️ Stage validation not ready: missing artifact. Set verdict: /task verdict <text>` —
HARD BLOCK.

```text
cli-agent> /task verdict все unit-тесты Reducer+ViewModel зелёные, в коде нет XML/Compose/ViewBinding
cli-agent> /task next        → validation→done
```

### 6. Done + итоговое состояние
```text
cli-agent> /task show
```
Ожидание:
```text
📋 Task state:
  Stage: done
  Current step: Экран настроек профиля: ...
  Approved plan: 1) ProfileSettingsView ... 5) Reducer unit-тесты
  Implementation: ProfileSettingsView + ProfileReducer + ProfileViewModel готовы ...
  Verdict: все unit-тесты Reducer+ViewModel зелёные, нет XML/Compose/ViewBinding
  History:
    - planning→clarify (forced)
    - clarify→planning
    - planning→execution
    - execution→validation
    - validation→done
```

---

## Пауза + resume (без повторных объяснений)

```text
cli-agent> /exit                                              ← ПАУЗА
```
```bash
CHATID=$(ls $XDG_DATA_HOME/chats/*.json | head -1 | xargs basename | sed 's/.json//')
$ALIAS -c "$CHATID"                                            ← RESUME
```
```text
cli-agent> /task show
```
Ожидание: состояние полностью на месте (stage=done, все артефакты plan/impl/verdict, history). Новые
поля `implementation`/`verdict` пережили restart.
```text
cli-agent> подведи итог по задаче
```
Ожидание: агент отвечает с учётом артефактов (видит блок `Task state:` в system prompt) и **не просит
заново объяснять задачу**.

---

## Escape hatch (минуя gate)

```text
cli-agent> /task set execution
```
Ожидание: `✓ Stage set to: execution` — force, **без** проверки `canAdvance`. Stage-prompt
переключается на execution при следующем запросе.

---

## Контрастная проверка stage-prompt (ядро доработки)

Сравнить поведение агента на один и тот же зонд `расскажи про экран` в разных стадиях:
- `clarify` → вопросы, без кода.
- `execution` → код (Kotlin, programmatic View-based).
- `validation` → проверка + вердикт, без нового кода.

Негативный сигнал: если ответ одинаковый во всех стадиях → stage-prompt не подмешивается.

---

## Чек-лист приёмки
- [x] FSM `clarify → planning → execution → validation → done` с allowed-transitions.
- [x] Stage-enforcing промпты — поведение per stage (clarify уточняет, execution пишет код, validation проверяет).
- [x] Artifact-gated hard-block: `/task next` без `plan`/`impl`/`verdict` отказывает.
- [x] Escape hatch `/task set` минует gate.
- [x] Артефакты `approvedPlan`/`implementation`/`verdict` рендерятся и персистятся.
- [x] Пауза/resume без повторных объяснений (taskState + артефакты переживают restart).
- [x] Инвариант совместимости: без `/task start` — обычный чат (Day 13 base).

## Зависимости
Задача 15 (верификация). Расширяет `11-test-cases.md` (базовый кейс Day 13) сценарием доработки
(stage-prompts + artifact-gated переходы).
