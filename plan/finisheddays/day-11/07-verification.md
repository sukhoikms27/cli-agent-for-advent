# Задача 07. Верификация

## Цель
Проверить end-to-end, что слои разделены, явно наполняются и влияют на ответы; регрессия дней 1–10 не сломана.

## Manual REPL (`./gradlew run --args="chat -c new"`)
1. Старт: баннер; `/memory show long` → пусто.
2. `/memory save long knowledge stack Kotlin` → спросить «на каком языке писать?» → ответ тяготеет к Kotlin. `/memory show long` подтверждает.
3. `/memory save working task "build auth service"` + `/memory save working plan "1) routes 2) tokens"` → «какой следующий шаг?» → ответ ссылается на task/plan. `/memory show working`.
4. Второй чат (`-c new`): `/memory show working` пусто (per-chat), `/memory show long` всё ещё Kotlin (global) → separation + кросс-сессия. Спросить про язык в чате 2 → тоже Kotlin.
5. `/reset` в чате 1 → working пусто, long-term на месте.
6. `/memory clear long` (warning) → long-term пуст → ответ больше не смещён к Kotlin.
7. Регрессия: новый чат без памяти → `/stats` оценка токенов та же, system-prompt контент идентичен дням 1–10.

## Автоматизация
- `./gradlew test` — 5 тест-классов зелёные (задача 06).
- `./gradlew build` — компилируется (новые `@Serializable` модели, расширенный `MemoryStore`, `PromptBuilder`, `/memory`).

## Критерии готовности (соответствие заданию)
- ✅ ≥3 типа памяти (short/working/long-term).
- ✅ Разные типы хранятся отдельно (per-chat messages/working vs global long-term файл).
- ✅ Явный выбор что куда (`/memory save ...`).
- ✅ Проверено, какие данные попадают в каждый слой (`/memory show ...`) и как влияют на ответы (шаги 2–4, 6).
- ✅ Поведение дней 1–10 без памяти не изменилось (шаг 7).

## Зависимости
Задача 06.
