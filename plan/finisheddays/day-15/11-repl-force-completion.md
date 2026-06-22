# Задача 11. Completer `--force` для `/task set` в `ReplEngine` (Этап A)

## Цель
Tab-completion флага `--force` (и alias `-f`) в подкоманде `/task set`, чтобы пользователь видел
escape-hatch в автодополнении.

## Зависимости
10 (`/task set --force`). Существующий completer `/task` (день 13).

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ReplEngine.kt` — `buildCompleter` (строки 50–85), блок `task`.

## Что изменить

**Сейчас** (строки ~68–71): completer `/task` — 3 уровня: команда / подкоманда / стадия. Стадии
подсказываются для всех подкоманд, что не совсем точно (нужны только для `set`), но работает.

```kotlin
val task = ArgumentCompleter(
    StringsCompleter("/task"),
    StringsCompleter("show", "start", "next", "set", "step", "expect", "plan", "impl", "verdict", "back", "done", "reset"),
    StringsCompleter("clarify", "planning", "execution", "validation", "done")
)
```

**После:** добавить `--force`/`-f` в третий уровень (рядом со стадиями). JLine3 `StringsCompleter`
подсказывает все свои значения независимо от контекста — `--force` будет виден при дополнении
третьего аргумента, что корректно для `/task set <stage|--force>`:

```kotlin
val task = ArgumentCompleter(
    StringsCompleter("/task"),
    StringsCompleter("show", "start", "next", "set", "step", "expect", "plan", "impl", "verdict", "back", "done", "reset"),
    StringsCompleter("clarify", "planning", "execution", "validation", "done", "--force", "-f")
)
```

## Логика
- **`StringsCompleter` не контекстно-зависим** — он подсказывает все свои кандидаты на данной позиции
  аргумента. `--force` будет предлагаться в третьей позиции для `/task set`, что и нужно. Для других
  подкоманд (`/task step <text>`) это не мешает — пользователь всё равно вводит свой текст.
- **Alias `-f`** — короткий флаг, как договорено в задаче 10.
- **Не ломает существующий completion** — стадии остаются; `--force`/`-f` добавлены в тот же
  `StringsCompleter`.

## Альтернатива (отвергнута)
Сделать контекстно-зависимый completer через `AggregateCompleter` из двух `ArgumentCompleter`
(один для `set` с `--force`, другой для остальных) — сложнее, и JLine3 `ArgumentCompleter` плохо
различает подкоманды без `TreeCompleter`. `TreeCompleter` (доступен в JLine3 3.29.0) был бы чище,
но требует рефакторинга всего completer'а — за рамками этой задачи. Оставить простое решение.

## Критерии готовности
- В REPL при `/task set <Tab>` среди кандидатов видны стадии + `--force` + `-f`.
- Существующий completion `/task` (подкоманды, стадии) не сломан.

## Зависимости (задачи)
10. Ручная REPL-проверка в 24 (верификация).
