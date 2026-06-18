# TUI-библиотеки для cli-agent — исследование и выбор

## Context

Текущий CLI-слой (`cli/ChatCommand.kt`) — голый `BufferedReader(InputStreamReader(System.`in`)).readLine()` внутри `runBlocking { while(true) }`. Из последствий:
- **Нет редактирования строк** (стрелки, Ctrl+W/U), **нет истории** команд между сессиями, **нет tab-completion** для slash-команд (`/memory`, `/profile`, `/branch`...).
- **Нет цветов/ANSI** — вывод через clikt `echo()` с ручным `trimMargin()` и emoji-глифами; таблицы `/stats`/`/cost` выровнены пробелами в строковых шаблонах.
- **Нет индикатора загрузки** — во время LLM-запроса (до 60с таймаут) UI просто висит на промпте `>`, без спиннера.
- `CLAUDE.md` **уже описывает** JLine3 + mordant как задуманный стек, но фактически ни одна не подключена. Зависимость в `build.gradle.kts` — только `com.github.ajalt.clikt:clikt:4.4.0`.

Цель: подобрать TUI-библиотеку(и) под критерии **простота адаптации · удобство · доступность · простота**, и наметить внедрение.

## Кандидаты (Kotlin/JVM, Maven Central)

| Библиотека | Координата | Что даёт | Адаптация | Соответствие критериям |
|---|---|---|---|---|
| **JLine3** | `org.jline:jline:3.29.0` | LineReader: редактирование строк, **персистентная история**, tab-completion (AggregateCompleter), корректный Ctrl+C (`UserInterruptException`)/Ctrl+D (`EndOfFileException`) | **Малая** — заменить `BufferedReader.readLine()` на `LineReader.readLine("cli-agent> ")`; `Completer` собирается из списка slash-команд | ★★★★★ простота, ★★★★★ зрелость, точно закрывает главную боль (ввод/история/completion) |
| **mordant** | `com.github.ajalt:mordant:3.0.0` | ANSI-цвета с auto-detect + `--no-color`, таблицы (`table{}`), спиннеры/progress, markdown. **Уже тащится транзитивно через clikt 4.x** | **Малая-средняя** — `val t = Terminal()`; заменить `echo`/`trimMargin` на `t.println(green("✓")...)`, таблицы для `/stats`/`/cost`, спиннер вокруг `agent.chat()` | ★★★★★ простота, ★★★★★ идиоматичный Kotlin (тот же автор, ajalt, что у clikt), доступность тривиальна |
| **kotter** | `com.varabyte.kotter:kotter:1.1.x` | Декларативный live-TUI (recomposition, `liveVar`/`liveListOf`), панели, анимации, Kotlin Multiplatform | **Высокая** — смена парадигмы (компонентная модель, full-screen render-loop); весь REPL переписывается под `runApp { }` | ★★★ возможности, но ★★ простота адаптации — избыточен для chat-REPL |
| **Lanterna** | `com.googlecode.lanterna:lanterna:3.0.3` | Full-screen curses-виджеты (окна, панели, кнопки, таблицы, меню), pure Java | **Высокая** — Java-centric API, менее идиоматичен для Kotlin, редкие апдейты; для полноэкранного приложения, не REPL | ★★ простота, ★★★ для full-screen, но оверхил для чата |

## Рекомендация: JLine3 + mordant

Соответствует всем четырём критериям и **уже зафиксировано в `CLAUDE.md`** как задуманный стек:
- **JLine3** = слой ввода (история, completion, редактирование, корректные сигналы). Малая дельта, закрывает главную боль.
- **mordant** = слой вывода (цвета, таблицы, спиннер во время LLM-запроса). Тот же автор и экосистема, что у clikt; зависимость уже на classpath транзитивно — прямое подключение тривиально.
- Они **комплементарны**: JLine = input, mordant = output. Это стандартная связка для Kotlin REPL (Spring Shell, ajalt-тулзы).

kotter / Lanterna — рассматривать только если позже понадобится полноэкранный live-дашборд (например, panel-режим с одновременным показом истории, профиля и хода задачи). Для chat-REPL это избыточно и дороже в адаптации.

## Намечаемое внедрение (после выбора)

1. **`build.gradle.kts`** — добавить `org.jline:jline:3.29.0` и `com.github.ajalt:mordant:3.0.0` (mordant уже транзитивен, но объявить явно).
2. **`cli/ReplEngine.kt`** (новый, как в CLAUDE.md) — вынести REPL-цикл из `ChatCommand.run()`:
   - `TerminalBuilder.builder().system(true).build()` + `LineReaderBuilder` с `HISTORY_FILE = AppPaths.dataDir.resolve("repl-history")` и `AggregateCompleter` из slash-команд.
   - `while(true) { try { line = reader.readLine("cli-agent> ") } catch (UserInterruptException) { continue } catch (EndOfFileException) { break } }`.
   - `AppPaths` — добавить `replHistoryFile`.
3. **Вывод** — `object`/поле `Terminal()` (mordant); `echo(...)` → `t.println(...)`, таблицы для `/stats`/`/cost`, цветные префиксы ошибок/успеха. Убрать stray `println` в `ContextAwareAgent.kt`/`OpenAiCompatibleClient.kt`.
4. **Спиннер во время LLM-запроса** — `coroutineScope { val job = launch { spinner }; val r = agent.chat(input); job.cancel() }` (сейчас chat-путь синхронный, нужен `launch`).
5. **Completion** — `StringsCompleter("/exit","/help","/history","/chats","/stats","/cost","/summary","/compress","/facts","/reset")` + `ArgumentCompleter` для `/strategy`, `/branch`, `/memory`, `/profile` с подкомандами.

## Верификация
- `./gradlew build` — компиляция + тесты (существующие 32 не должны сломаться; slash-диспетч `when` сохраняется).
- REPL: стрелки/Ctrl+W/Ctrl+U работают; история `/strategy` доступна стрелкой ↑ после рестарта; Tab дополняет `/me` → `/memory`, `/profile a` → `/profile add`.
- `/stats`/`/cost` — таблицы mordant, цвета; `--no-color` → plain.
- Спиннер крутится во время LLM-ответа, исчезает по завершении.
- Ctrl+C — отмена ввода (не выход), Ctrl+D — выход.

## Примечание
Это исследование; реализация — отдельный шаг после выбора библиотеки. Выше — рекомендация JLine3+mordant как отвечающая критериям «простота адаптации / удобство / доступность / простота».
