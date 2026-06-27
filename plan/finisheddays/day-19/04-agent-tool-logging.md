# 04 — Лог вызова tool'а в агенте (DI-sink, корректно поверх спиннера)

> По запросу пользователя: агент должен логировать, **какой tool дёрнули** в ходе tool-loop'а.
> Это единственное семантическое клиентское изменение в Day 19 — `runToolLoop`/`McpToolExecutor` из
> Day 17 **всё ещё чейнят tools**, добавляется только observability.
>
> **Подводный камень, найденный при ревью:** сырой `println` **затирается** спиннером mordant — вывод
> «появляется рядом со статусом и исчезает». Решение — DI-sink, подключаемый к `AppTerminal`.

## Корень проблемы

REPL крутит спиннер (`AppTerminal.withSpinner`) **во время** всего `chat()`-вызова:
```kotlin
// ChatCommand.dispatchFreeText
AppTerminal.withSpinner({ spinnerLabel() }) { statefulAgent.chat(input) }
```
А `runToolLoop` исполняет tools **внутри** этого блока. Сырой `println` пишет в **stdout мимо**
mordant-терминала — анимация перерисовывает свою строку через `\r` и затирает/перемешивает вывод.
Отсюда симптом пользователя: «появляется рядом со статусом и может исчезать».

**Доказательство, что через `AppTerminal` работает:** stage-блоки печатаются через
`onEmit → AppTerminal.println` **поверх** активного спиннера (`dispatchFreeText`, Day 15) — и
отображаются корректно. mordant `textAnimation` умеет вставлять строки выше себя.

## Решение: DI-sink (агент не зависит от CLI-слоя)

Архитектурное правило: агент (`src/main/kotlin/com/cliagent/agent/`) **не должен импортировать**
CLI-слой (`com.cliagent.cli.*`). Поэтому — sink как параметр конструктора:

```kotlin
class ContextAwareAgent(
    ...,
    private val toolExecutor: ToolExecutor? = null,
    /**
     * День 19: sink для статусного вывода (compress-warnings, tool-call-лог). **Default `::println`**
     * сохраняет поведение вне REPL (тесты, batch). В REPL подключается к AppTerminal.println —
     * критично: спиннер крутится во время chat(), и сырой println (stdout) затирается анимацией.
     */
    private val logger: (String) -> Unit = { msg -> println(msg) }
) : Agent {
```

Wiring в REPL (`ChatCommand.run`):
```kotlin
val agent = ContextAwareAgent(
    ...,
    toolExecutor = toolExecutor,
    logger = AppTerminal::println   // ← печатает «поверх» спиннера mordant
)
```

## Все замены println → logger в агенте

Бонус-фикс: та же проблема затирания была у **всех** статусных логов агента (compress-warnings,
context-overflow, MCP-unavailable). Все переведены на `logger`:

| Место | Было | Стало |
|---|---|---|
| `chat()`: auto-compress start | `println("🔄 Compressing history...")` | `logger(...)` |
| `chat()`: compress result | `println("✓ Compressed N messages...")` | `logger(...)` |
| `chat()`: context overflow warning | `println("⚠️ Warning: estimated N tokens...")` | `logger(...)` |
| `runToolLoop`: tool-call лог | `println("🔧 Tool call: ...")` | `logger(...)` |
| `loadToolsOrNull()`: MCP unavailable | `println("⚠️ MCP tools unavailable...")` | `logger(...)` |

## Лог tool-call'а в runToolLoop

В цикл `for (tc in calls)` — одна строка лога перед `execTool`:
```kotlin
for (tc in calls) {
    val args = parseToolArgs(tc.function.arguments)
    logger("🔧 Tool call: ${tc.function.name}${formatToolArgs(args)}")
    val toolResult = execTool(tc.function.name, args)
    scratch.add(ChatMessage(role = "tool", content = toolResult, toolCallId = tc.id))
}
```

### Helper `formatToolArgs` — компактная сводка аргументов

Длинные payload (содержимое отчёта в `format_report`/`save_to_file`) не должны спамить вывод:
```kotlin
private fun formatToolArgs(args: Map<String, Any?>): String {
    if (args.isEmpty()) return ""
    return args.entries.joinToString(separator = ", ", prefix = "(", postfix = ")") { (k, v) ->
        val raw = when (v) {
            is String -> "\"${v.take(MAX_ARG_LEN)}${if (v.length > MAX_ARG_LEN) "…" else ""}\""
            is List<*> -> "[${v.size} item${if (v.size == 1) "" else "s"}]"
            is Map<*, *> -> "{${v.size} field${if (v.size == 1) "" else "s"}}"
            null -> "null"
            else -> v.toString()
        }
        "$k=$raw"
    }
}
```
- Строки обрезаются до `MAX_ARG_LEN = 40` символов с `…`-суффиксом.
- Коллекции показывают **размер**, а не дамп (`[3 items]`, `{2 fields}`) — структура без спама.

## Пример вывода (сценарий tech-дайджеста)

```
⠋ Thinking…              ← спиннер крутится (chat() активен)
🔧 Tool call: search_wikipedia(query="Kotlin", language="en")
⠙ Thinking…
🔧 Tool call: get_repo(owner="JetBrains", repo="kotlin")
⠹ Thinking…
🔧 Tool call: format_report(title="Kotlin Digest", sections=[2 items])
⠸ Thinking…
🔧 Tool call: save_to_file(filename="kotlin-digest", content="# Kotlin Digest\n\n_28.06.2026_…")
```
Лог tool-call'а печатается **поверх** спиннера (не затирается) — видна вся цепочка: какой tool,
с какими аргументами, в каком порядке. Ровно то, что нужно для демонстрации «автоматического
выполнения цепочки» из задания Day 19.

## Почему DI-sink, а не прямой импорт AppTerminal в агенте?

- **Архитектура:** агент — доменный слой, не должен зависеть от терминального/CLI-представления
  (Dependency Inversion). Sink `(String) -> Unit` — абстракция, `AppTerminal::println` — реализация.
- **Тестопригодность:** default `::println` сохраняет поведение ~300 unit-тестов без изменений
  (они не проходят `logger=`). При желании в тест можно передать collecting-sink и assert'ить лог.
- **Консистентность с toolExecutor:** тот же паттерн DI уже используется для `ToolExecutor?` —
  `logger` — естественное расширение (null/default вне REPL, конкретная реализация внутри).

## Критерии готовности

- [x] `./gradlew compileKotlin compileTestKotlin` green.
- [x] `./gradlew :test --rerun-tasks` — **300 тестов, 0 failures, 0 errors** (default `::println` не
      сломал ни один из ~40 call-сайтов `ContextAwareAgent(...)` в тестах — параметр defaulted).
- [x] REPL-wiring: `ChatCommand` передаёт `logger = AppTerminal::println` → печать поверх спиннера.
- [x] Все 5 статусных логов агента переведены на `logger` (compress ×2, overflow-warn, tool-call, MCP-unavailable).

## Почему не в `execTool`?

`execTool(name, args)` — приватный, без знания об аргументах в красивом виде (принимает уже parsed map).
Лог в цикле `runToolLoop` даёт доступ к `tc.function.name` + свежепарсенным args в одном месте, без
дополнительного проброса. Семантически правильно: логируем **решение LLM** (tool_call), а не результат.
