# Дизайн: Время ответа в лоадере (доп. п.5)

**Дата:** 2026-06-20
**Статус:** Draft (ожидает review)
**Тип:** Самостоятельное расширение (UX-polish; не входит в курс дня 15, отложено из декомпозиции)

## Контекст и мотивация

Спиннер (`AppTerminal.withSpinner`) крутит кадровую анимацию (`⠋⠙⠹…`) с лейблом, но не показывает,
сколько времени прошло с начала операции. Для долгих LLM-вызовов (особенно AUTO-режим дня 15, где
stage-поток идёт автоматически) пользователь не видит, «зависло» или работает. Индикатор elapsed
time даёт обратную связь: «12.3s» растёт → идёт работа; не растёт → проблема.

День 15 (п.4) уже ввёл динамический лейбл (`withSpinner(() -> String)`, задачи 21–22). Эта
спецификация добавляет **elapsed time** в тот же спиннер — естественное расширение.

## Решения

1. **Elapsed в render-лямбде, не отдельным компонентом.** `withSpinner` уже дёргает `labelProvider()`
   каждый кадр; добавить туда elapsed — тривиально. Не вводит новой сущности.
2. **Формат — секунды с одним знаком (`12.3s`)** до 60s, далее `1m 23s` (как в mordant progress).
3. **Совместимо с динамическим лейблом (п.4)** — elapsed показывается вместе со стадийным лейблом:
   `⠋ Planning… (3.2s)`.
4. **Точность — `System.currentTimeMillis()`** — достаточно для UX; `nanoTime` избыточен.
5. **Опциональный overload для возврата elapsed** — если нужно показать «Готово за Xs» после
   завершения, `withSpinnerTimed` возвращает `Pair<T, Long>` (elapsed ms).

## Архитектура

### Правка `AppTerminal.withSpinner` (overload от задачи 21)

```kotlin
suspend fun <T> withSpinner(labelProvider: () -> String, block: suspend () -> T): T = coroutineScope {
    val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    val start = System.currentTimeMillis()
    val animation = t.textAnimation<Int> { tick ->
        val elapsed = (System.currentTimeMillis() - start) / 1000.0
        "${frames[tick % frames.length]} ${labelProvider()} (${formatElapsed(elapsed)})"
    }
    // ... остальное как в задаче 21
}

private fun formatElapsed(seconds: Double): String =
    if (seconds < 60) String.format("%.1fs", seconds)
    else {
        val m = (seconds / 60).toInt()
        val s = (seconds % 60).toInt()
        "${m}m ${s}s"
    }
```

### Опциональный `withSpinnerTimed` (если нужен elapsed наружу)

```kotlin
suspend fun <T> withSpinnerTimed(labelProvider: () -> String, block: suspend () -> T): Pair<T, Long> {
    val start = System.currentTimeMillis()
    val result = withSpinner(labelProvider, block)
    return result to (System.currentTimeMillis() - start)
}
```

Call-site (если показывать «Готово за Xs» после):
```kotlin
val (response, elapsed) = AppTerminal.withSpinnerTimed({ spinnerLabel(agent) }) { agent.chat(input) }
AppTerminal.markdown(response)
AppTerminal.println("(${formatElapsed(elapsed / 1000.0)})")
```

## Компоненты

| Компонент | Ответственность |
|---|---|
| `AppTerminal.formatElapsed(seconds)` | Форматирование `12.3s` / `1m 23s` (private). |
| `AppTerminal.withSpinner` (правка) | Добавить `start` + elapsed в render-лямбду. |
| `AppTerminal.withSpinnerTimed` (новый, опц.) | Возвращает `Pair<T, Long>` для пост-вывода. |

## Риски

- **`System.currentTimeMillis()` на Windows** — точность ~15ms; для UX-индикатора (обновление каждые
  120ms) достаточно. `nanoTime` дал бы точнее, но усложняет (и не нужен для секунд).
- **Совместимость с non-TTY** — mordant не пишет кадры в pipe; elapsed не виден в пайпах (как и
  сам спиннер). Приемлемо (pipe = скриптовый режим, elapsed не критичен).
- **Длина строки** — `⠋ Planning… (12.3s)` длиннее `⠋ Thinking…`; на узких терминалах может
  переноситься. mordant `textAnimation` обновляет в одной строке (overwrite), перенос маловероятен.
  Проверить на 80-колоночном.

## Тестирование

- Юнит-тесты `formatElapsed`: `0.0`→`0.0s`, `12.34`→`12.3s`, `59.9`→`59.9s`, `60.0`→`1m 0s`,
  `83.0`→`1m 23s`, `125.0`→`2m 5s`.
- Интеграционный тест `withSpinner` (сложнее — анимация; проверить через мок Terminal или
  опустить, т.к. визуальный UX). Основная логика в `formatElapsed`.

## Критерии приёмки

- [ ] Спиннер показывает elapsed: `⠋ Planning… (3.2s)` (растёт со временем).
- [ ] Формат `Xs` до 60s, `Xm Ys` после.
- [ ] Совместимо с динамическим лейблом дня 15 (п.4) — обе фичи в одном render.
- [ ] Non-TTY не падает (mordant сам не пишет в pipe).
- [ ] `./gradlew test build` зелёный.

## Зависимости

- `AppTerminal` (правка overload из задачи 21 дня 15).
- Зависит от п.4 (динамический лейбл) — реализуется поверх того же overload.
