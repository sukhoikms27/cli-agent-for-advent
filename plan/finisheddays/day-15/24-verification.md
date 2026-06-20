# Задача 24. Верификация дня 15

## Автоматизация

```bash
# Полный прогон тестов (существующие ~130 + новые ~44)
./gradlew test

# Сборка + компиляция main/test
./gradlew build

# Установка дистрибутива для manual REPL
./gradlew installDist
```

**Ожидание:** все три команды зелёные. Счётчик тестов: `./gradlew test` должен показать
`X tests completed` где X = (тесты дня 14) + ~44 новых. Если упал существующий тест — регрессия,
разобраться (вероятная причина: `advanceTaskState` делегирование в guard — задача 09).

## Manual REPL — подготовка

```bash
export XDG_DATA_HOME=/tmp/cliagent-day15-test
rm -rf $XDG_DATA_HOME
./gradlew test build installDist
ALIAS="build/install/cli-agent/bin/cli-agent chat"
```

> LLM-зависимые сценарии (авто-роутинг, stage-генерация) требуют реальной модели. Граничные/негативные
> кейсы (недопустимые переходы, --force, режимы) проверяются без LLM (чистая логика guard).

## Manual REPL — Сценарии проверки

### S1. Заголовок и режим (задачи 15, 18, 19)

```text
cli-agent> (старт)
```
**Ожидание:** `CLI Agent v0.8 | Chat: <id> | Model: <m> | Context: sliding | Compress: OFF | Invariants: OFF | Mode: plan`

```text
cli-agent> /mode
```
**Ожидание:**
```
Interaction mode: plan
  manual — свободный чат; FSM только через /task
  plan   — stage-поток с подтверждением переходов (default)
  auto   — полная автоматизация; переходы без подтверждения
```

```text
cli-agent> /mode auto
cli-agent> /mode
```
**Ожидание:** `Interaction mode set to: auto`, затем `Interaction mode: auto`.

### S2. Контролируемые переходы — жёсткий режим (задачи 10, 09, 07) — БЕЗ LLM

```text
cli-agent> /task start тестовая задача
cli-agent> /task set execution
```
**Ожидание:** `⛔ Blocked: illegal transition planning→execution. Allowed: planning, execution. Use --force to override.`
(стадия НЕ меняется — `planning`, нет плана).

```text
cli-agent> /task plan "1) сделать X 2) проверить"
cli-agent> /task next
```
**Ожидание:** `Advanced to stage: execution` (план есть, gate пройден).

```text
cli-agent> /task set done
```
**Ожидание:** `⛔ Blocked: illegal transition execution→done. Allowed: execution, validation, planning. Use --force to override.`

### S3. `--force` escape (задачи 10, 07)

```text
cli-agent> /task set done --force
```
**Ожидание:** `Stage set to: done (forced)` — осознанный escape, note="forced" в history.

```text
cli-agent> /task
```
**Ожидание:** в `stageHistory` видна запись `execution→done (forced)`.

### S4. Артефактный gate (задачи 07, 10) — БЕЗ LLM

```text
cli-agent> /task reset
cli-agent> /task start задача 2
cli-agent> /task next
```
**Ожидание:** `⛔ Stage planning not ready: missing artifact. Set approved plan: /task plan <text>`
(gate: нет плана → переход заблокирован).

### S5. Авто-роутинг интента (задачи 02, 03) — ТРЕБУЕТ LLM

```text
cli-agent> что такое MVI в Android?
```
**Ожидание (PLAN/авто-роутинг):** обычный чат-ответ (IntentClassifier → QUESTION), FSM НЕ стартует.

```text
cli-agent> спроектируй и реализуй экран настроек профиля на Kotlin с MVI
```
**Ожидание (PLAN/авто-роутинг):** автостарт FSM — появляется артефакт первой стадии (CLARIFY или
PLANNING), уведомление `❓ Уточнение требований` или `📋 Планирование`.

### S6. MANUAL режим — роутинг off (задачи 18, 20)

```text
cli-agent> /mode manual
cli-agent> спроектируй и реализуй сложный модуль
```
**Ожидание:** обычный чат-ответ (роутинг отключён в MANUAL), FSM НЕ стартует автоматически. Доступен
только через `/task start`.

### S7. AUTO режим — полная автоматизация (задачи 20, 17) — ТРЕБУЕТ LLM

```text
cli-agent> /mode auto
cli-agent> реализуй простую функцию сложения two numbers
```
**Ожидание:** автостарт → авто-проход стадий (clarify/planning → execution → validation → done) БЕЗ
запросов «да», с уведомлениями на каждой стадии (`📋 Планирование` → `📦 Утверждённый план готов` →
`⏭ → Реализация` → …). Спиннер меняет лейбл: `Planning…` → `Executing…` → `Validating…`.

### S8. Динамический спиннер (задачи 21, 22) — ТРЕБУЕТ LLM

В AUTO-режиме (S7) наблюдать спиннер во время stage-выполнения:
**Ожидание:** лейбл меняется `Planning…` → `Executing…` → `Validating…` по мере перехода стадий
(каждые 120ms перечитывается `spinnerLabel`).

### S9. Pause/resume (персистентность) — БЕЗ LLM для проверки state

```text
cli-agent> /task start задача 3
cli-agent> /task plan "шаг 1, шаг 2"
cli-agent> /task next
cli-agent> /exit
```
Затем:
```bash
CHATID=$(ls $XDG_DATA_HOME/chats/*.json | head -1 | xargs basename | sed 's/.json//')
$ALIAS -c "$CHATID"
cli-agent> /task
```
**Ожидание:** стадия `execution` (сохранена), `approvedPlan` на месте, `stageHistory` содержит
`planning→execution`. Resume без повторных объяснений.

### S10. StatefulAgent + инварианты в stage-flow (задачи 12, 14, 15) — ТРЕБУЕТ LLM

```bash
$ALIAS --invariants   # с флагом
```
```text
cli-agent> /invariants add BAN no-compose Запрещён Jetpack Compose
cli-agent> /mode auto
cli-agent> реализуй UI экрана на Compose
```
**Ожидание:** даже в AUTO stage-потоке инвариант срабатывает — запрос/ответ с Compose блокируется
(`⛔`), т.к. stage-вызовы идут через `StatefulAgent.chat` → `InvariantGuard` (gap №6 закрыт).

### S11. Без-флаговая регрессия (инвариант совместимости)

```bash
$ALIAS   # без --invariants, без /mode (default PLAN)
```
**Ожидание:** поведение идентично дню 14 для обычного чата; `/task` работает как в дне 13 (с жёстким
`set` из дня 15). Никаких новых флагов не требуется для базовой работы.

## Доп. проверки
- `gateHint` удалён из `ChatCommand` (grep `gateHint` → 0 совпадений вне guard).
- `chatAgent` удалён из `ChatCommand` (grep → 0; используется `agent`).
- Импорт `InvariantGuard` в `ChatCommand` убран (если не используется напрямую).
- Версия `v0.8` в заголовке.
- `/help` содержит `/task set ... --force`, `/mode`.
- Completer `/task set <Tab>` показывает стадии + `--force` + `-f`.
- Completer `/mode <Tab>` показывает `manual`/`plan`/`auto`/`show`.

## Чек-лист приёмки (соответствие заданию курса + доптребованиям)

- [ ] «Допустимые состояния» — `TaskStage` + `allowedTargets` (S2 показывает allowed в сообщении).
- [ ] «Разрешённые переходы» — `ALLOWED` + `TransitionGuard` (S2/S4).
- [ ] «Не мог перепрыгнуть этап» — `⛔ Blocked` без --force (S2).
- [ ] «Нельзя реализация без плана» — ArtifactMissing (S4).
- [ ] «Нельзя финал без валидации» — Illegal при `→done` (S2).
- [ ] «Реакция на недопустимый» — `⛔ Blocked` с причиной (S2/S3).
- [ ] «Продолжение после паузы» — S9 resume.
- [ ] (п.1) Авто-роутинг вопрос/задача — S5, MANUAL off в S6.
- [ ] (п.2) Уведомления по стадиям — S7/S10.
- [ ] (п.3) Режимы manual/plan/auto — S1/S6/S7.
- [ ] (п.4) Динамический спиннер — S8.
- [ ] StatefulAgent + инварианты в stage-flow — S10.
- [ ] Инвариант совместимости — S11 (default PLAN = поведение дня 14).

## Критерии готовности
- `./gradlew test build installDist` зелёный.
- Все сценарии S1–S11 дают ожидаемый результат (LLM-зависимые — при наличии модели).
- Чек-лист приёмки отмечен.
- Нулевая регрессия существующего функционала.

## Зависимости (задачи)
23 (тесты). Демо-сценарий 25 расширяет S1–S11 в полноценный кейс-документ.
