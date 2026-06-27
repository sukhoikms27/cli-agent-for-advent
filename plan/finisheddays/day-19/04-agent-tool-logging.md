# 04 — Лог вызова tool'а в агенте (единственное клиентское изменение)

> По запросу пользователя: агент должен логировать, **какой tool дёрнули** в ходе tool-loop'а.
> Это единственное изменение клиентского модуля `src/` в Day 19 — `runToolLoop`/`McpToolExecutor` из
> Day 17 **семантически не меняются** (всё ещё чейнят tools), добавляется только observability.

## Файл

`src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt` — метод `runToolLoop`, цикл исполнения tool_calls.

## Что добавлено

В цикл `for (tc in calls)` — одна строка лога перед `execTool`:
```kotlin
for (tc in calls) {
    val args = parseToolArgs(tc.function.arguments)
    println("🔧 Tool call: ${tc.function.name}${formatToolArgs(args)}")   // NEW
    val toolResult = execTool(tc.function.name, args)
    scratch.add(ChatMessage(role = "tool", content = toolResult, toolCallId = tc.id))
}
```

## Helper `formatToolArgs` — компактная сводка аргументов

Длинные payload (содержимое отчёта в `format_report`/`save_to_file`) не должны спамить вывод. Поэтому:
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
🔧 Tool call: search_wikipedia(query="Kotlin", language="en")
🔧 Tool call: get_repo(owner="JetBrains", repo="kotlin")
🔧 Tool call: format_report(title="Kotlin Digest", sections=[2 items])
🔧 Tool call: save_to_file(filename="kotlin-digest", content="# Kotlin Digest\n\n_28.06.2026_…")
```

Видна вся цепочка: какой tool, с какими аргументами, в каком порядке — ровно то, что нужно для
демонстрации «автоматического выполнения цепочки» из задания Day 19.

## Стиль вывода

Соответствует существующим логам агента (`println` с эмодзи):
- `🔄 Compressing history...`
- `✓ Compressed N messages`
- `⚠️ Warning: ...`
- `🔧 Tool call: ...` ← NEW

Вывод идёт в **stdout** (как остальные статусные сообщения агента) — не в stderr, не в JSON-RPC-канал
(это клиент REPL, не MCP-сервер: здесь stdout-ограничений stdio-протокола нет).

## Критерии готовности

- [x] `./gradlew compileKotlin` (корневой модуль) green.
- [x] `AgentToolUseLoopTest` (3 теста) — green: лог в stdout не влияет на assert'ы (persist-контракт,
      MAX_TOOL_ROUNDS, single-shot — проверяют `saveMessage` calls и возвращаемое значение, не вывод).
- [x] `./gradlew :test` — все корневые тесты green, 0 регрессий.

## Почему не в `execTool`?

`execTool(name, args)` — приватный, без знания об аргументах в красивом виде (принимает уже parsed map).
Лог в цикле `runToolLoop` даёт доступ к `tc.function.name` + свежепарсенным args в одном месте, без
дополнительного проброса. Семантически правильно: логируем **решение LLM** (tool_call), а не результат.
