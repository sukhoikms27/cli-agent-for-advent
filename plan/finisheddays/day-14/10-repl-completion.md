# Задача 10. Tab-completion `/invariants` в `ReplEngine`

## Что
Добавить `/invariants` и его подкоманды в JLine3 `ArgumentCompleter` (tab-completion REPL).
Минимальная правка, по образцу `/task` / `/profile` (день 12-13).

## Зависимости
09 (команды определены).

## Реализация
Правка `src/main/kotlin/com/cliagent/cli/ReplEngine.kt` — в существующий completer:

1. Корневой список команд пополняется `"/invariants"`.
2. `ArgumentCompleter` для `/invariants`: подкоманды `show | add | remove | clear`.
3. Для `/invariants add` — completer категорий `STACK | BAN | ARCH | BUSINESS` (как completer
   стадий для `/task set`).

```kotlin
// псевдо-структура (зеркало существующих /task, /profile completers)
val invariantsCompleter = ArgumentCompleter(
    StringsCompleter("/invariants"),
    StringsCompleter("show", "add", "remove", "clear")
)
// для add — отдельный branch с StringsCompleter("STACK","BAN","ARCH","BUSINESS")
```

## Проверка
- Manual: `/inva<TAB>` → дополнение до `/invariants`; `/invariants <TAB>` → `show add remove clear`.
- `/invariants add <TAB>` → категории.
- Существующее completion не сломано (`/task`, `/profile`, `/memory`).
- Юнит-тест completer'а — если в проекте есть (проверить `ReplEngineTest`/аналоги day-12);
  иначе manual в верификации (задача 12).

## Решения
- **Зеркало `/profile` completer'а** — консистентность UX; минимум нового кода.
- Подкоманды и категории как статичные `StringsCompleter` — они фиксированы (enum), не зависят от
  runtime-состояния (id инвариантов динамичны, их не кcomplete'им — как и stage-history).
