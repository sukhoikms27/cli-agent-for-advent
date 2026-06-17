# День 11. Модель памяти ассистента (memory layers) — задачи

## Задание курса (`plan/newdays/day11.md`)
Описать и реализовать модель памяти для ассистента. Разделить информацию минимум на 3 типа:
- **краткосрочная** (текущий диалог)
- **рабочая** (данные текущей задачи)
- **долговременная** (профиль, решения, знания)

Требования: разные типы хранятся отдельно; агент явно выбирает что и куда сохраняется; проверить, какие данные попадают в каждый слой и как это влияет на ответы. Артефакт: агент с явной моделью памяти (memory layers).

## Три слоя

| Слой | Тип | Содержимое | Хранение | Scope |
|---|---|---|---|---|
| `SHORT_TERM` | краткосрочная | текущий диалог | `ChatData.messages` + context-стратегии | per-chat |
| `WORKING` | рабочая | данные текущей задачи (task, plan, notes, decisions) | `ChatData.workingMemory` | per-chat, reset при `/reset` |
| `LONG_TERM` | долговременная | knowledge, decisions, profile | `AppPaths.longTermFile` (global JSON) | global, кросс-чат |

## Принятые решения
1. Расширять `ContextAwareAgent` (не вводить `StatefulAgent` — он на Day 13 со state machine + invariants).
2. Добавить test-инфру (JUnit5 + MockK) и юнит-тесты.
3. Сохранение в слои — только ручные `/memory` команды (LLM-извлечение отложить на Day 12).
4. Пустые слои элизируются в `PromptBuilder` → поведение дней 1–10 не меняется без памяти.

## Декомпозиция на задачи (выполнять последовательно)

| # | Файл | Задача | Зависимости |
|---|---|---|---|
| 01 | `01-memory-layer-model.md` | Модель слоёв: `MemoryLayer` enum + `WorkingMemory` + `LongTermMemory` + `UserProfile` stub | — |
| 02 | `02-storage-working-longterm.md` | `AppPaths` long-term пути + `ChatData.workingMemory` + методы `MemoryStore` + `JsonChatStore` + `JsonLongTermStore` | 01 |
| 03 | `03-prompt-builder.md` | `PromptBuilder` + `renderBlock()` для слоёв | 01 |
| 04 | `04-agent-integration.md` | `ContextAwareAgent`: загрузка слоёв, слоёный system prompt, reset, аксессоры | 02, 03 |
| 05 | `05-cli-memory-command.md` | `/memory` команда в `ChatCommand` + help + сообщение `/reset` | 04 |
| 06 | `06-tests.md` | test-зависимости в `build.gradle.kts` + 5 тест-классов | 01–05 |
| 07 | `07-verification.md` | Manual REPL-верификация + `./gradlew build`/`test` | 06 |

## Точки расширения
- **Day 12 (персонализация):** заполняется `LongTermMemory.profile: UserProfile?` (stub уже заведён в задаче 01), `PromptBuilder` уже рендерит профиль.
- **Day 13 (task state machine):** добавляется `WorkingMemory.taskState: TaskState? = null` (defaulted), `state/` пакет, `StatefulAgent`. Pause/resume = per-chat `WorkingMemory` перезагружается в `ensureLoaded()`.

## Риски
- Конкурентность global long-term файла (last-write-wins между параллельными процессами) — atomic write спасает от повреждения, не от потерянных апдейтов. Для single-user CLI приемлемо.
- `MemoryStore` interface растёт на 6 методов — единственная реализация `JsonChatStore`, других нет → не ломает.
- `/memory clear long` деструктивно и global — warning + без алиаса; единичный ключ удаляется через `save long ... ""`.
