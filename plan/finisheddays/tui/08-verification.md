# Задача 08. Верификация TUI

## Цель
Подтвердить JLine3 (ввод) + mordant (вывод) работают end-to-end; регрессий нет.

## Manual REPL (`installDist`-бинарь — надёжнее для интерактива, чем `./gradlew run`)
1. `cli-agent chat` → промпт `cli-agent> `.
2. Редактирование: стрелки влево/вправо, Ctrl+W (удалить слово), Ctrl+U (удалить строку).
3. История: ↑/↑ → предыдущие команды; после рестарта `cli-agent chat` — история доступна (персистентная).
4. Completion: Tab на `/me` → `/memory`; `/profile a` → `/profile add` (если ArgumentCompleter добавлен).
5. Ctrl+C — отменяет ввод, REPL продолжается; Ctrl+D — выход.
6. `/stats`/`/cost` — таблицы mordant, цвета; `cli-agent chat --no-color` → plain без ANSI.
7. Сообщение агенту → спиннер во время ответа, затем результат.
8. Все slash-команды (`/memory`, `/profile`, `/branch`, `/strategy`) работают как прежде.

## Автоматизация
- `./gradlew build` — компиляция + 32 теста не сломаны.
- Стриминг (SSE) — вне scope (Phase 2).

## Критерии готовности
- REPL через JLine3; вывод через mordant; спиннер; таблицы; `--no-color`.
- Регрессий по slash-командам и агенту нет.

## Зависимости
Задачи 06, 07.
