# Задача 21. `withSpinner(() -> String)` — динамический лейбл (п.4)

## Цель
Overload `withSpinner`, принимающий `() -> String` вместо `String` — лейбл перечитывается каждый
кадр, позволяя отражать текущую стадию/действие (thinking/planning/executing/validating). Старый
overload делегирует — обратно совместимо.

## Зависимости
`AppTerminal.withSpinner` (существующий). Без новых зависимостей.

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/AppTerminal.kt` — метод `withSpinner` (строки 60–87).

## Что изменить

**Сейчас** (строки 69–87): один overload с фиксированным `label: String`. Лейбл замкнут в render-лямбде
один раз — не перечитывается.

**После:** добавить overload с `labelProvider: () -> String`; существующий overload делегирует:

```kotlin
/**
 * Крутит спиннер с [label], пока выполняется [block] (LLM-вызов и т.п.).
 *
 * День 15 (п.4): делегирует в [withSpinner] с [labelProvider] — статичный лейбл как частный случай.
 * Обратно совместимо: существующие call-sites `withSpinner("Thinking…") { ... }` не меняются.
 */
suspend fun <T> withSpinner(label: String, block: suspend () -> T): T =
    withSpinner({ label }, block)

/**
 * Крутит спиннер с динамическим [labelProvider], пока выполняется [block] (день 15, п.4).
 *
 * В отличие от overload-а со статичным [String], [labelProvider] вызывается на каждом кадре —
 * лейбл отражает текущую стадию/действие (thinking/planning/executing/validating/…). Источник метки
 * — состояние агента (см. ChatCommand.spinnerLabel, задача 22).
 *
 * mordant `Animation` сам ничего не выводит на non-interactive терминале (piped stdin), поэтому
 * спиннер не garble'ит вывод в пайпах; на TTY рисует кадры в одной строке.
 *
 * `CancellationException` не глотаем: finally отменяет джобу и чистит кадр.
 */
suspend fun <T> withSpinner(labelProvider: () -> String, block: suspend () -> T): T = coroutineScope {
    val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    val animation = t.textAnimation<Int> { tick ->
        "${frames[tick % frames.length]} ${labelProvider()}"
    }
    val job = launch {
        var tick = 0
        while (isActive) {
            animation.update(tick++)
            delay(120)
        }
    }
    try {
        block()
    } finally {
        job.cancel()
        animation.clear()
    }
}
```

## Логика
- **`labelProvider()` в render-лямбде** — вызывается каждый кадр (каждые 120ms); перечитывает
  актуальную стадию. Источник метки — замыкание call-site (например, `() -> spinnerLabel(agent)` в
  `ChatCommand`, задача 22).
- **Старый overload делегирует** — `withSpinner(label: String, block) = withSpinner({ label }, block)`.
  Существующие call-sites (`ProfileExtractor`-вызов в `ChatCommand.kt:694`) не меняются.
- **Поведение на non-TTY сохранено** — mordant `textAnimation` не пишет в pipe (та же реализация,
  только лейбл динамический); overload не вводит новой логики вывода.

## Поток
```
withSpinner({ spinnerLabel(agent) }) { block() }:
  render-лямбда вызывается каждые 120ms:
    "${frames[tick % len]} ${spinnerLabel(agent)}"   ← labelProvider() перечитывает стадию
  block() выполняется (LLM-вызов, stage-агент)
  finally: cancel job, clear animation
```

Если во время `block()` стадия меняется (AUTO-режим: planning→execution), спиннер подхватит новое
значение на следующем кадре — пользователь видит прогресс.

## Ключевые инварианты
- **Обратная совместимость** — старый overload сохранён, делегирует в новый. Существующие 4 call-site
  (`ChatCommand.kt:157, 167, 694, 801`) можно оставить как есть или перевести на динамический (задача 22).
- **`labelProvider` не должен бросать** — вызывается в render-лямбде (mordant); исключение нарушило бы
  анимацию. `spinnerLabel` (задача 22) должен быть safe (никогда не бросает, всегда возвращает строку).
- **`delay(120)` тот же** — частота кадров не меняется; только источник лейбла.

## Решения
- **Overload, не замена сигнатуры** — обратно совместимо. Если бы заменили `label: String` на
  `labelProvider: () -> String`, сломали бы все call-sites. Overload — gradual migration.
- **`() -> String`, не `StateFlow<String>`** — проще; не вводит зависимость от coroutines-flow.
  Лейбл — простое чтение состояния, flow избыточен.
- **Render-лямбда перечитывает** — это ключевое отличие. `tick` был единственной переменной раньше;
  теперь `labelProvider()` тоже вызывается в кадре.

## Критерии готовности
- `withSpinner(() -> String, block)` overload компилируется.
- `withSpinner(String, block)` делегирует в новый overload.
- Существующие call-sites работают без изменений (обратная совместимость).
- На TTY динамический лейбл обновляется каждые 120ms.

## Зависимости (задачи)
Используется в 22 (call-sites с `spinnerLabel`). Демо F в 25.
