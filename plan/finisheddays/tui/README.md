# TUI: JLine3 + mordant — задачи

## Цель
Внедрить JLine3 (слой ввода) + mordant (слой вывода) в REPL cli-agent. Feel как у Claude Code (scrollback-REPL, история, completion, цвета, таблицы, спиннер) при малой адаптации. `kotter` отложен до потребности в live-панелях todo/tool-use.

## Контекст
Текущий REPL (`cli/ChatCommand.kt`) — голый `BufferedReader.readLine()` в `runBlocking { while(true) }`:
- нет редактирования строк/истории/completion;
- нет цветов (clikt `echo` + `trimMargin` + emoji);
- нет спиннера во время LLM-запроса (UI висит до 60с).

`CLAUDE.md` уже предписывает JLine3 + mordant. `global-plan.md` обновлён (TUI-стратегия зафиксирована).

## Принятые решения
1. JLine3 = ввод (LineReader: история, completion, сигналы); mordant = вывод (цвета, таблицы, спиннер).
2. REPL-цикл выносится в новый `cli/ReplEngine.kt` (как в CLAUDE.md); slash-диспетч `when` сохраняется.
3. mordant-обёртка `cli/AppTerminal.kt` (`object AppTerminal`); `echo` → `AppTerminal.println`.
4. Спиннер вокруг `agent.chat()` через `coroutineScope { launch { spinner }; ... }`.
5. Агентный слой (`agent/`, `llm/`, `memory/`) НЕ трогается.

## Декомпозиция (выполнять последовательно)

Все 8 задач выполнены. Статус: ✅.

| # | Файл | Задача | Зависимости | Статус |
|---|---|---|---|---|
| 01 | `01-deps.md` | Зависимости в `build.gradle.kts` (jline 3.29.0, mordant 2.5.0) + проверка резолва/компиляции | — | ✅ |
| 02 | `02-apppaths-history.md` | `AppPaths.replHistoryFile` | 01 | ✅ |
| 03 | `03-appterminal.md` | `cli/AppTerminal.kt` — mordant Terminal-обёртка (println, цвета, `disableColor`) | 01 | ✅ |
| 04 | `04-replengine.md` | `cli/ReplEngine.kt` — JLine3 LineReader, история, AggregateCompleter, Ctrl+C/D | 02 | ✅ |
| 05 | `05-chatcommand-wiring.md` | `ChatCommand.run()` использует ReplEngine + AppTerminal; вывод через mordant | 03, 04 | ✅ |
| 06 | `06-spinner.md` | Спиннер во время LLM-вызова (`withSpinner`: coroutineScope + launch + animation.clear) | 05 | ✅ |
| 07 | `07-tables-colors.md` | `/stats`/`/cost` таблицы mordant, цветные префиксы (ok/err/warn), `--no-color` | 05 | ✅ |
| 08 | `08-verification.md` | REPL-проверки, таблицы, спиннер, build + 32 теста | 06, 07 | ✅ |

## Markdown-рендер ответов LLM (расширение по запросу, сверх 8 задач)
Ответ LLM рендерится через `mordant.markdown.Markdown` (`AppTerminal.markdown(text)` → widget-overload `t.println(Widget)`), а не как чистый текст:
- GitHub Flavored Markdown: заголовки, списки, жирный/курсив/инлайн-код, fenced-блоки **с рамкой** (по умолчанию mordant), цитаты, таблицы.
- Цвет на TTY, plain на `--no-color`/пайпах (mordant сам стрипает ANSI).
- **Защитный fallback:** mordant-markdown (intellij-markdown) бросает `IllegalStateException` на одиночном `$` (math-delimiter) и подобных токенах. LLM-ответы часто содержат `$` (математика, шелл, цены, `path: $`), поэтому `AppTerminal.markdown` оборачивает рендер в try/catch и при ошибке парсинга падает в plain-текст — REPL никогда не крашится. Тест: `MarkdownRenderTest` (4 кейса, incl. `$`-fallback). Всего тестов: 36.

## Верификация (задача 08) — результаты
- `./gradlew build` — SUCCESSFUL, тесты проходят (36 с markdown-тестами).
- Piped smoke: banner + `cli-agent>`; все slash-команды (`/help /history /chats /stats /cost /memory /profile /strategy /branch /facts /reset`) отвечают корректно, без крахов/регрессий.
- Мутации персистятся: `/profile set/add` → `longterm/memory.json` (style, constraints); `/memory save working` → чат-JSON (`workingMemory.currentTask`).
- JLine-история пишется в `repl-history` (с timestamp'ами) даже в dumb/piped-режиме.
- `--no-color` флаг принимается → `AppTerminal.disableColor()` (plain вывод).
- Спиннер: на non-interactive терминале mordant `Animation` но-оп (без garbling); lifecycle `launch → block → cancel + clear` корректен, `CancellationException` не глотается. Путь LLM проверен на быстрой 401-ошибке — чистый вывод ответа после спиннера.
- Ручные TTY-проверки (за пределами piped-режима): редактирование строк (стрелки/Ctrl+W/U), Tab-completion (`/me`→`/memory`, `/profile a`→`/profile add`), анимация спиннера на TTY, Ctrl+C отмена ввода — вынесены пользователю на интерактивную проверку `installDist`-бинарём.

## Риски
- Версии jline/mordant — проверить резолв в задаче 01 (jline 3.29.0, mordant 3.0.0).
- JLine + System.in в `./gradlew run` (stdin через JavaExec) — верифицировать интерактивно; `installDist`-бинарь надёжнее.
- Спиннер + suspend `agent.chat` — корректная отмена `job.cancel()` и не глотать `CancellationException`.
