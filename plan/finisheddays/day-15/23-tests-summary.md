# Задача 23. Сборный обзор тестов (день 15)

## Цель
Единый обзор всех тест-классов дня 15 + расширений существующих. Граничные таблицы для критичных
механизмов.

## Зависимости
Все этапы 0–E. Это обзорный документ, не новая реализация.

## Новые тест-классы

| # | Тест-класс | Что проверяет | Пример данных |
|---|---|---|---|
| 1 | `agent/stage/IntentClassifierTest` (новый) | Авто-роутинг QUESTION/TASK, fallback на ошибку/мусор, blank без LLM, temperature=0, оба слова→TASK | mock LLM возвращает `"TASK"`/`"QUESTION"`/мусор/error |
| 2 | `state/TransitionGuardTest` (новый) | Все 5 веток guard: allowed/illegal/artifact/force/self + боковые переходы без gate | `attempt(PLANNING, DONE)` → Illegal; `attempt(PLANNING, EXECUTION, no plan)` → ArtifactMissing |
| 3 | `agent/StatefulAgentTest` (новый) | Делегирование chat/history/reset; инварианты opt-in (через InvariantGuard); `contextAware` expose | mock base + mock checker; `chat` без checker → делегат; с checker → отказ при Violated |

## Расширения существующих тест-классов

| # | Тест-класс | Что добавляется | Завис. |
|---|---|---|---|
| 4 | `state/TaskStateMachineTest` | `allowedTargets(from)` — self + канонические forward + исключение перепрыгиваний (3 теста) | 06 |
| 5 | `agent/ContextAwareAgentTaskStateTest` | `attemptTransition(to, force)` — Allowed/Illegal/ArtifactMissing/force/null + обратная совместимость `advanceTaskState` (6 тестов) | 09 |
| 6 | `ChatDataSchemaEvolutionTest` (или WorkingMemory-тест) | `WorkingMemory.interactionMode` default PLAN; legacy JSON без поля → PLAN | 18 |

## Граничные таблицы

### TransitionGuard — полная матрица (задача 08)

| # | from | to | artifact | force | Ожидание |
|---|---|---|---|---|---|
| 1 | CLARIFY | PLANNING | — | false | Allowed (нет gate) |
| 2 | PLANNING | EXECUTION | plan | false | Allowed |
| 3 | EXECUTION | VALIDATION | impl | false | Allowed |
| 4 | VALIDATION | DONE | verdict | false | Allowed |
| 5 | VALIDATION | EXECUTION | — | false | Allowed (боковой, rework) |
| 6 | EXECUTION | PLANNING | — | false | Allowed (боковой, replan) |
| 7 | DONE | PLANNING | — | false | Allowed (боковой, new task) |
| 8 | PLANNING | DONE | plan | false | Illegal (перепрыг) |
| 9 | CLARIFY | EXECUTION | — | false | Illegal (перепрыг) |
| 10 | PLANNING | EXECUTION | пусто | false | ArtifactMissing |
| 11 | EXECUTION | VALIDATION | пусто | false | ArtifactMissing |
| 12 | VALIDATION | DONE | пусто | false | ArtifactMissing |
| 13 | PLANNING | DONE | — | true | Allowed (force, note="forced") |
| 14 | PLANNING | EXECUTION | пусто | true | Allowed (force обходит gate) |
| 15 | PLANNING | PLANNING | — | false | Allowed (self, no history) |
| 16 | PLANNING | PLANNING | пусто | false | Allowed (self без gate) |

### IntentClassifier — матрица (задача 04)

| # | Ввод модели | Ввод пользователя | Ожидание |
|---|---|---|---|
| 1 | `"TASK"` | любое | TASK |
| 2 | `"QUESTION"` | любое | QUESTION |
| 3 | `"this is a task."` | любое | TASK (tolerant) |
| 4 | `"бананы"` | любое | QUESTION (garbage fallback) |
| 5 | error 500 | любое | QUESTION (error fallback) |
| 6 | (not called) | `"   "` | QUESTION (blank, no LLM) |
| 7 | `"task not question"` | любое | TASK (оба слова, TASK first) |

### Режимы InteractionMode — матрица (задача 20)

| Ситуация | MANUAL | PLAN | AUTO |
|---|---|---|---|
| Нет задачи, ввод | чат (роутинг off) | IntentClassifier | IntentClassifier |
| Активная задача, awaitingAdvance=true, «да» | advance | advance | advance (но и без «да») |
| Артефакт готов после генерации | awaitingAdvance | awaitingAdvance | авто-advance |
| Нелегальный переход | ⛔ blocked | ⛔ blocked | ⛔ blocked |
| `/task set done` без --force | ⛔ blocked | ⛔ blocked | ⛔ blocked |

## Регрессионные гарантии

Существующие тесты, которые ДОЛЖНЫ остаться зелёными (не модифицируются):

| Тест-класс | Кол-во | Почему не трогается |
|---|---|---|
| `TaskStateMachineTest` (базовые 17) | 17 | `TaskStateMachine` не меняется по сигнатуре; `allowedTargets` — аддитивное дополнение |
| `InvariantGuardTest` | 9 | `InvariantGuard` используется через композицию в `StatefulAgent`; не модифицируется |
| `ContextAwareAgentTest`/`*ProfileTest`/`*InvariantsTest` | 61+ | `ContextAwareAgent` не ломается; `attemptTransition` — аддитивный accessor |
| `StagePromptTemplatesTest` | 5 | `StagePromptTemplates` не трогается |
| `LlmInvariantCheckerTest` | 12 | checker не меняется |
| `ChatDataSchemaEvolutionTest` (существующие) | — | новые поля с default → legacy-тесты зелёные |

## Оценка количества тестов
- Новых: ~17 (TransitionGuard) + ~8 (IntentClassifier) + ~9 (StatefulAgent) = **~34 новых**.
- Расширений: ~3 (allowedTargets) + ~6 (attemptTransition) + ~1 (interactionMode schema) = **~10 доп.**.
- Итого день 15 добавляет **~44 теста**; существующие (~130+) остаются зелёными.

## Критерии готовности
- `./gradlew test` зелёный (все новые + существующие).
- `./gradlew test build` зелёный (компиляция main + test).
- Все граничные кейсы таблиц покрыты.
- Нулевая регрессия: счётчик тестов до/после сравнивается (до = день 14, после = день 14 + ~44).

## Зависимости (задачи)
02–22. Финальный прогон в 24.
