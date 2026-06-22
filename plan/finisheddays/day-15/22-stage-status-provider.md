# Задача 22. `spinnerLabel` — динамический лейбл стадии в call-sites (п.4)

## Цель
Перевести call-sites спиннера на динамический лейбл, отражающий текущую стадию задачи:
thinking/planning/executing/validating/… или «Thinking…» при отсутствии задачи.

## Зависимости
21 (`withSpinner(() -> String)`), 16 (`StageAnnouncer` — человекочитаемые имена), 12 (`StatefulAgent`).

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ChatCommand.kt` — функция `spinnerLabel` (новая) + 4 call-site.

## Что изменить

### 1. Новая функция `spinnerLabel` (в `ChatCommand`)

```kotlin
/**
 * Динамический лейбл спиннера по текущей стадии задачи (день 15, п.4).
 * Возвращает глагол действия: «Thinking…» (нет задачи), «Planning…», «Executing…», …
 * Никогда не бросает — вызывается в render-лямбде mordant каждый кадр.
 */
private fun spinnerLabel(agent: StatefulAgent): String {
    val stage = agent.contextAware.getTaskState()?.stage ?: return "Thinking…"
    return when (stage) {
        TaskStage.CLARIFY -> "Clarifying…"
        TaskStage.PLANNING -> "Planning…"
        TaskStage.EXECUTION -> "Executing…"
        TaskStage.VALIDATION -> "Validating…"
        TaskStage.DONE -> "Finalizing…"
    }
}
```

> `getTaskState()` — `suspend`, но `() -> String` в render-лямбде не suspend. Варианты:
> (а) читать стадию синхронно из cached-поля (если `WorkingMemory` уже загружен в `ensureLoaded`);
> (б) использовать `runBlocking` внутри labelProvider (нежелательно — блокирует).
>
> **Решение:** `getTaskState` в `ContextAwareAgent` читает `getWorkingMemory()?.taskState`, а
> `getWorkingMemory` после `ensureLoaded` возвращает in-memory `workingMemory` (без suspend IO после
> первой загрузки). Проверить, что `getWorkingMemory` не делает IO после loaded=true. Если делает —
> кэшировать стадию в `ChatCommand` (обновлять после каждого `orchestrator.handleUserInput`/
> `agent.chat`). На этапе реализации: если `getTaskState` требует suspend, сделать `spinnerLabel`
> non-suspend через cached-значение (обновлять в цикле REPL перед `withSpinner`).

### 2. Call-sites (строки 157, 167, 694, 801) — переход на динамический лейбл

**Сейчас** (4 места):
```kotlin
// строка 157
val taskResponse = AppTerminal.withSpinner("Thinking…") { orchestrator.handleUserInput(input) }
// строка 167
val response = AppTerminal.withSpinner("Thinking…") { chatAgent.chat(input) }
// строка 694 (ProfileExtractor)
val merged = AppTerminal.withSpinner("Inferring profile…") { extractor.extract(history, cur) }
// строка 801 (/task start)
val display = AppTerminal.withSpinner("Thinking…") { orchestrator.startTask(desc) }
```

**После** (3 из 4 переводятся на стадийный лейбл; ProfileExtractor остаётся статичным):
```kotlin
// строка 157 — stage-поток: стадийный лейбл
val stepResult = AppTerminal.withSpinner({ spinnerLabel(agent) }) {
    orchestrator.handleUserInput(input, mode)
}
// строка 167 — обычный чат: «Thinking…» (нет активной задачи в этой ветке)
val response = AppTerminal.withSpinner({ spinnerLabel(agent) }) { agent.chat(input) }
// строка 694 — ProfileExtractor: ОСТАЁТСЯ статичным (не связан со стадиями задачи)
val merged = AppTerminal.withSpinner("Inferring profile…") { extractor.extract(history, cur) }
// строка 801 — /task start: стадийный лейбл (стартовая стадия)
val display = AppTerminal.withSpinner({ spinnerLabel(agent) }) { orchestrator.startTask(desc) }
```

## Логика
- **`spinnerLabel(agent)`** — читает текущую стадию задачи через `agent.contextAware.getTaskState()`.
  При отсутствии задачи (`taskState == null`) → «Thinking…» (текущий дефолт). При активной задаче →
  глагол стадии.
- **Глаголы, не существительные** — «Planning…», не «PLANNING». Консистентно с текущим «Thinking…»
  (процесс, не состояние). Английский — как существующий лейбл.
- **ProfileExtractor остаётся статичным** — `/profile extract` не связан со стадиями задачи; лейбл
  «Inferring profile…» релевантнее. Динамический overload тут не нужен.
- **Обычный чат (строка 167)** — в этой ветке `taskState == null` (вопрос, не задача) → `spinnerLabel`
  вернёт «Thinking…». Но если вдруг активная задача (edge case) — покажет стадию. Безопасно.

## Особенность: suspend в non-suspend лямбде

`getTaskState()` — suspend. `() -> String` в render-лямбде — non-suspend. Решение на этапе реализации:

**Вариант A (предпочтительный):** проверить, что `ContextAwareAgent.getWorkingMemory` после
`ensureLoaded` не делает IO (читает in-memory `workingMemory` поле). Если так — `getTaskState` можно
сделать non-suspend (или добавить non-suspend overload `getTaskStateSync`), и `spinnerLabel` работает
напрямую.

**Вариант B (fallback):** кэшировать стадию в `ChatCommand` — обновлять `var currentSpinnerStage`
после каждого хода (orchestrator/chat). `spinnerLabel` читает `currentSpinnerStage`. Менее точно
(стадия в середине AUTO-прохода не обновится в реальном времени), но безопасно.

На этапе реализации — проверить `getWorkingMemory` и выбрать A или B. Документировать выбор в коде.

## Ключевые инварианты
- **`spinnerLabel` никогда не бросает** — вызывается в render-лямбде; исключение нарушило бы
  анимацию. Safe-чтение с default «Thinking…».
- **3 из 4 call-sites переведены** — stage-поток и /task start; ProfileExtractor статичен (намеренно).
- **Обычный чат — «Thinking…»** — нет задачи → нет стадии → дефолт.
- **Обратная совместимость** — старый overload `withSpinner(String)` доступен; ProfileExtractor его
  использует.

## Решения
- **Глаголы стадий на английском** — консистентно с «Thinking…». Русские имена (`StageAnnouncer`) —
  для уведомлений (полные), лейбл спиннера — краткий английский (терминальная конвенция).
- **Не использовать `StageAnnouncer.stageName`** — тот даёт полные русские («Планирование»), а лейбл
  спиннера должен быть кратким («Planning…»). Разные форматы для разных контекстов.
- **ProfileExtractor не трогаем** — его лейбл осмысленнее статичным; не связан со stage-потоком.

## Критерии готовности
- `spinnerLabel(agent)` возвращает «Thinking…»/«Planning…»/«Executing…»/… по стадии.
- Call-sites 157, 167, 801 переведены на `{ spinnerLabel(agent) }`; 694 (ProfileExtractor) статичен.
- Спиннер показывает стадию во время stage-выполнения (особенно в AUTO — пользователь видит переход
  planning→executing→validating в реальном времени).
- На non-TTY поведение не меняется (mordant не пишет в pipe).

## Зависимости (задачи)
21. Демо F в 25.
