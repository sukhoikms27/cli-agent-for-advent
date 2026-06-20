# Задача 03. Авто-роутинг интента в `ChatCommand` (п.1)

## Цель
При отсутствии активной задачи (`taskState == null`) и режиме ≠ MANUAL агент сам определяет интент
входного сообщения: QUESTION → обычный чат; TASK → автостарт FSM без ручного `/task start`.

## Зависимости
02 (`IntentClassifier`). Зависит от режима (задача 18 `InteractionMode`) — но реализуется в этой
задаче через проверку `interactionMode != MANUAL` с дефолтом `PLAN` (поле появится в задаче 18; до
него считаем, что роутинг активен всегда при `taskState == null`). Согласование с режимами — задача 20.

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ChatCommand.kt` — else-ветка REPL-цикла (строки 154–172).

## Что изменить

**Сейчас** (строки 154–172): else-ветка безусловно отдаёт свободный текст в `orchestrator.handleUserInput`;
при `taskState == null` оркестратор возвращает `null`, и текст уходит в `chatAgent.chat(input)`.

**После**: при `taskState == null` добавить проверку интента через `IntentClassifier`. Роутинг
включается только когда нет активной задачи (иначе активная задача перехватывается оркестратором как
обычно). QUESTION → чат (как сейчас); TASK → `orchestrator.startTask(input)`.

```kotlin
else -> {
    val taskState = agent.getTaskState()

    // День 15 (п.1): при отсутствии активной задачи — авто-определение «вопрос vs задача».
    // QUESTION → обычный чат; TASK → автостарт жизненного цикла FSM (без /task start).
    // Роутинг НЕ применяется, если уже есть активная задача (её ведёт оркестратор),
    // и отключается в режиме MANUAL (FSM только через явный /task start) — задача 20.
    if (taskState == null) {
        val intent = intentClassifier.classify(input)
        if (intent == UserIntent.TASK && interactionMode != InteractionMode.MANUAL) {
            val w = agent.getWorkingMemory() ?: WorkingMemory()
            agent.setWorkingMemory(w.copy(currentTask = input))
            val display = AppTerminal.withSpinner({ spinnerLabel(agent) }) {
                orchestrator.startTask(input)
            }
            AppTerminal.println()
            AppTerminal.markdown(display)
            AppTerminal.println()
            return@runBlocking
        }
    }

    // День 13 (авто-поток): при активной задаче свободный текст = подтверждение
    // перехода («да») или уточнение артефакта текущей стадии. Иначе — обычный чат.
    val taskResponse = AppTerminal.withSpinner({ spinnerLabel(agent) }) {
        orchestrator.handleUserInput(input)
    }
    if (taskResponse != null) {
        AppTerminal.println()
        AppTerminal.markdown(taskResponse)
        AppTerminal.println()
    } else {
        // День 14: при --invariants свободный чат идёт через InvariantGuard
        // (отказ запроса-нарушителя + retry ответа-нарушителя).
        val response = AppTerminal.withSpinner({ spinnerLabel(agent) }) { chatAgent.chat(input) }
        AppTerminal.println()
        AppTerminal.markdown(response)
        AppTerminal.println()
    }
}
```

## Сопутствующие правки в `ChatCommand.run()`

1. **Инстанцирование классификатора** (рядом с оркестратором, строки ~109–112):
   ```kotlin
   val intentClassifier = IntentClassifier(client, model)
   ```

2. **`interactionMode` — значение режима для текущего чата** (геттер из `WorkingMemory`, поле
   добавляется в задаче 18). До задачи 18 — временно `val interactionMode = InteractionMode.PLAN`
   (значение-заглушка, не ломает компиляцию); после задачи 18 заменить на чтение из
   `agent.getWorkingMemory()?.interactionMode ?: InteractionMode.PLAN`. Это создаёт циклическую
   зависимость задач 03↔18↔20 — поэтому порядок выполнения: 18 (модель+поле) → 03/20 (использование).
   **Уточнённый порядок в этой задаче:** реализовать с `InteractionMode.PLAN` заглушкой, реальное
   чтение подключается в задаче 19 (`/mode`) и 20 (orchestrator mode-aware).

3. **`spinnerLabel(agent)`** — ссылка на динамический лейбл спиннера (задача 22). До задачи 22
   использовать существующий `withSpinner("Thinking…")`. Эта задача фокусируется на роутинге, не на
   спиннере — но call-site уже подготовлен под подмену в этапе E.

4. **Возврат из ветки** — `return@runBlocking` после автостарта задачи (весь ход обработан; не
   нужно падать в обычный чат). Используется тот же label, что и в остальном `when`.

## Логика решения
- **Роутинг только при `taskState == null`** — активная задача уже перехватывается оркестратором
  (`handleUserInput` возвращает не-null); добавлять интент-проверку поверх означало бы двойной
  перехват и конфликты. Простой принцип: «есть задача → ведём её; нет задачи → решаем, заводить ли».
- **`currentTask` выставляется перед `startTask`** — как и в существующем `/task start`
  (`ChatCommand.kt:800`), чтобы описание задачи попало в `WorkingMemory` (рендерится в промпте).
- **`MANUAL` отключает роутинг** — в этом режиме пользователь хочет полный контроль: FSM только через
  явный `/task start`. Это согласуется с задачей 20 (orchestrator mode-aware).
- **Автостарт через `orchestrator.startTask`** — переиспользование существующего потока
  (классификатор стартовой стадии → генерация артефакта → `awaitingAdvance`). Не дублируем логику.

## Критерии готовности
- Ввод без активной задачи, классифицированный как TASK → автостарт FSM (появляется артефакт первой
  стадии, как от `/task start`).
- Ввод, классифицированный как QUESTION → обычный чат.
- При активной задачи роутинг не вмешивается (оркестратор работает как прежде).
- В режиме MANUAL роутинг отключён (ввод всегда идёт в чат, если нет задачи).

## Зависимости (задачи)
02 (классификатор). Согласуется с 18/20 (режимы). Спиннер — 21/22 (call-site подготовлен).
