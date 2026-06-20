# Дизайн: Status-line под вводом — контекст/стоимость (доп. п.7)

**Дата:** 2026-06-20
**Статус:** Draft (ожидает review)
**Тип:** Самостоятельное расширение (UX-polish; не входит в курс дня 15, отложено из декомпозиции)

## Контекст и мотивация

Пользователь не видит «сколько контекта осталось» и «сколько потрачено» во время работы. `/cost`/`/stats`
показывают по запросу, но не live. Цель: постоянная status-line под строкой ввода (или в ней) с
заполненностью контекстного окна и/или суммарной стоимостью чата.

## Текущее состояние (из исследования)

- `TokenCounter.estimateHistoryTokens(messages)` — грубая оценка (`length/4 + 4`).
- `agent.getEstimatedHistoryTokens()` — accessor (используется в `printStats`).
- `contextLimit` (128000, хардкод в `ContextAwareAgent`, приватное, без accessor) — лимит окна.
- `TokenCounter.SessionTokens.totalPrompt/totalCompletion` — аккумулятор (in-memory).
- `Pricing` — цена по модели; per-request cost считается.
- **Два терминала не координируются:** mordant `AppTerminal.t` и JLine3 `ReplEngine.terminal` —
  live-status-bar требует их синхронизации (нетривиально).
- **`reader.readLine("cli-agent> ")` блокирует** — обновление status во время ввода требует фонового
  треда/корутины.

## Решения (три альтернативы)

### Альтернатива A: Live status-bar через JLine3 `Status` (высокая сложность)

JLine3 3.29.0 включает `org.jline.utils.Status` — нижняя строка терминала, обновляемая без дизрапта
ввода. Реализация:
1. Вынести `org.jline.terminal.Terminal` из `ReplEngine` в общее место (пробросить в AppTerminal или
   отдельный `StatusBar` класс).
2. `Status.getTerminalStatus(terminal)` → `status.update(listOf("ctx 12k/128k · \$0.0123"))`.
3. Фоновая корутина обновляет status каждые N секунд + после каждого хода.
4. `dumb(true)` фолбэк: в pipe status неработоспособен → отключать.

**Плюсы:** live, профессиональный UX (как в Claude Code). **Минусы:** высокая сложность, 2 терминала,
Windows cmd quirks, фон.корутина + блокирующий readLine (синхронизация).

### Альтернатива B: Печать перед/после хода (низкая сложность, РЕКОМЕНДУЕТСЯ)

В цикле REPL печатать status-строку после каждого ответа агента (и перед следующим промптом):
```
[ответ агента]

ctx 12.3k/128k (10%) · \$0.0123 · stage: planning
cli-agent> _
```
Не live (обновляется раз за ход), но дёшево, работает в pipe, не трогает readline/JLine.

**Плюсы:** тривиально, совместимо с dumb-режимом, нулевая координация терминалов. **Минусы:** не
обновляется во время долгого ввода (но это редко нужно).

### Альтернатива C: Статус в самом prompt (низкая сложность)

`reader.readLine(prompt)` принимает любой текст. Встроить краткий статус:
```
cli-agent [12k/128k · \$0.0123 · planning]> _
```
Формировать prompt на каждой итерации цикла REPL.

**Плюсы:** тривиально, статус виден всегда (даже во время ввода). **Минусы:** длинный prompt на узких
терминалах; не live (обновляется при следующем readLine).

## Рекомендация: Альтернатива B (печать после хода) + опц. C (краткий в prompt)

- **B как основа** — надёжно, работает везде, показывает ctx% + cost + stage после каждого ответа.
- **C как дополнение** — краткий режим в prompt (`cli-agent [10% · \$0.01]>`) для постоянной видимости.
- **A (live bar) — отложить** до отдельной задачи; сложность не оправдана для текущего UX.

## Архитектура (Альтернатива B)

### `AppTerminal.statusLine(...)` (новый метод)

```kotlin
fun statusLine(usedTokens: Int, contextLimit: Int, totalCost: Double?, stage: TaskStage?) {
    val pct = (usedTokens.toDouble() / contextLimit * 100).toInt()
    val costStr = totalCost?.let { "\$" + "%.4f".format(it) } ?: "cost n/a"
    val stageStr = stage?.let { " · stage: ${it.name.lowercase()}" } ?: ""
    t.println("${TextColors.cyan("ctx")} ${usedTokens/1000}k/${contextLimit/1000}k ($pct%) · $costStr$stageStr")
}
```

### `ContextAwareAgent` accessor'ы (правка)

- `getContextLimit(): Int` — публичный accessor для приватного `contextLimit` (128000).
- `getTotalCost(): Double?` — если персистируется (п.6 `ChatData.totalCost`); иначе из `TokenCounter`.

### Call-site в `ChatCommand` (после ответа)

```kotlin
// после AppTerminal.markdown(response) в else-ветке
val used = agent.contextAware.getEstimatedHistoryTokens()
val limit = agent.contextAware.getContextLimit()
val cost = agent.contextAware.getTotalCost()
val stage = agent.contextAware.getTaskState()?.stage
AppTerminal.statusLine(used, limit, cost, stage)
```

## Компоненты

| Компонент | Ответственность |
|---|---|
| `AppTerminal.statusLine(...)` (новый) | Форматирование `ctx Xk/Yk (Z%) · $cost · stage: …`. |
| `ContextAwareAgent.getContextLimit()` (новый accessor) | Публичный геттер для `contextLimit`. |
| `ContextAwareAgent.getTotalCost()` (новый accessor) | Суммарная стоимость (in-memory или персист). |
| `ChatCommand` (правка) | Пост-вывод status-line после ответа. |
| (опц. C) `ReplEngine.readLine(prompt)` (правка) | Параметризованный prompt с кратким статусом. |

## Риски

- **Точность оценки токенов** — `estimateHistoryTokens` грубая (`length/4+4`); реальный prompt может
  отличаться на 20–30%. Альтернатива: использовать `lastRequestTokens.promptTokens` (точный, но
  последний запрос, не вся история). Решение: показывать estimate с пометкой `~` (`~12k`).
- **Рассинхрон с context-стратегиями** — sliding window/summary/branch меняют «историю»; оценка
  может не совпадать с тем, что реально уходит в LLM. Митигация: `estimateHistoryTokens` по текущему
  `buildMessagesToSend` (а не по голой `history`).
- **Длинная строка на узких терминалах** — `ctx 12k/128k (10%) · $0.0123 · stage: planning` ~45
  символов; на 80-колоночном помещается. Проверить; при необходимости сократить (`10% · $0.01`).
- **Персистентность cost** — зависит от п.6 (`ChatData.totalCost`). Без него — in-memory (теряется
  при рестарте).

## Тестирование

- `statusLine` форматирование (юнит-тест): разные pct/cost/stage; null cost; null stage.
- `getContextLimit`/`getTotalCost` accessor'ы.
- (опц. C) prompt-форматирование — визуальная REPL-проверка.
- Интеграция: после ответа печатается status-line (ручная REPL-проверка).

## Вне скоупа

- Live status-bar (Альтернатива A, JLine3 `Status`) — отложено.
- Графический progress-bar для контекста (`█████░░░░░ 50%`) — возможно позже.
- Предупреждение при приближении к лимиту (`⚠️ ctx 90% — скоро компрессия`) — связать с `--compress`.

## Критерии приёмки

- [ ] После каждого ответа — status-line `ctx ~12k/128k (10%) · $0.0123 · stage: planning`.
- [ ] Неизвестная модель → `cost n/a`.
- [ ] Нет активной задачи → без `stage:` части.
- [ ] Работает в pipe (dumb-режим) — простая печать, не JLine Status.
- [ ] (опц. C) Краткий статус в prompt: `cli-agent [10% · $0.01]>`.
- [ ] `./gradlew test build` зелёный.

## Зависимости

- `AppTerminal`, `ReplEngine` (правки).
- `ContextAwareAgent` accessor'ы (`contextLimit`, cost).
- `TokenCounter.estimateHistoryTokens` (существующий).
- Связан с п.6 (`ChatData.totalCost` для персистентной стоимости).
- `contextLimit` сейчас хардкод 128000; будущее — `ModelInfo.contextWindow` (мёртвая модель, можно
  оживить).
