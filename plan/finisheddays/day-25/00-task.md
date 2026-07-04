# День 25 — Контекст задания

## Задание (из `plan/newdays/day25.md`)

> 🔥 День 25. Мини-чат с RAG + памятью (production-like)
>
> Реализуйте мини-чат (CLI/веб), который:
> 👉 хранит историю диалога
> 👉 при каждом новом вопросе ищет контекст в базе через RAG
> 👉 отвечает с учётом найденной информации
> 👉 всегда выводит источники
>
> Усиление:
> 👉 добавьте "память задачи" (task state): что пользователь уже уточнил, какие ограничения/термины
>    зафиксированы, что является целью диалога
>
> Проверьте:
> 👉 на 2 длинных сценариях по 10–15 сообщений
> 👉 что ассистент не теряет цель и продолжает выдавать ответы с источниками
>
> Результат: Мини-чат с RAG + источниками + памятью задачи

## Контекст лекции недели 5

Лекция `videossummary/05_RAG_…` покрывает базовый one-shot RAG (запрос → векторная БД → чанки →
промпт → ответ с источником). Понятия «production-чат», «multi-turn контекст», «память задачи»,
«цель диалога» приходят из недель 3 (память/состояние) и 6 (stage flow), уже реализованных в днях
11-15. День 25 = интеграция RAG-недели-5 с памятью-недели-3 в рамках production-чата.

## GAP-анализ (90% уже есть)

| # | Требование | Статус | Где / чего не хватало |
|---|---|---|---|
| 1 | хранит историю диалога | ✅ было | `MemoryStore` / `JsonChatStore` / REPL (дни 1-24) |
| 2 | RAG каждый ход | ✅ было | `ContextAwareAgent.chat()` retrieve (день 22) |
| 3 | отвечает с учётом | ✅ было | `PromptBuilder` инъекция (день 22) |
| 4 | всегда выводит источники | ✅ было | усиленный промпт + `CitationDetector` (день 24) |
| 5 | память задачи (цель/уточнения) | ✅ стало | `setWorkingMemory(currentTask=goal)` в сценарии + **conversation-aware retrieval** (день 25) |
| 6 | 2 сценария по 10-15 сообщений | ✅ стало | **`/rag scenario` + 2 JSON-сценария** (день 25, главный deliverable) |
| 7 | не теряет цель + источники | ✅ стало | per-turn пост-чек + goal retention в отчёте (день 25) |
| ➕ | RAG по умолчанию ON | ✅ стало | `RagConfig.enabled = true` (запрос пользователя, день 25) |

## Маппинг задания → реализация

| Требование | Реализация |
|---|---|
| хранит историю | `MemoryStore` (дни 1-24), персистентный chatId |
| RAG каждый ход | `ContextAwareAgent.chat()` retrieve каждый ход (день 22) |
| отвечает с учётом | `PromptBuilder` инъекция `[Retrieved context]` (день 22) |
| всегда выводит источники | усиленный промпт + пост-чек `CitationDetector` (день 24) |
| цель диалога | `WorkingMemory.currentTask` через `setWorkingMemory` в harness сценария |
| уточнения/ограничения/термины | conversation-aware retrieval (goal + история обогащают запрос) |
| 2 сценария по 10-15 сообщений | `/rag scenario <name>` + `rag/scenarios/rag-architecture.json`, `task-fsm.json` |
| не теряет цель | `reset()` между сценариями + goal retention в `printScenarioReport` |
| источники в каждом ответе | per-turn `CitationDetector` + сводный % в отчёте |

## Решения и обоснования

### 1. Context-enrichment в агенте, не расширение QueryRewriter
`QueryRewriter.rewrite(query: String)` дня 23 — stable контракт, переиспользуется в `/rag compare-modes`.
Расширение сигнатуры (context-параметр) сломало бы backward-compat. Вместо этого `buildConversationQuery()`
в `ContextAwareAgent` обогащает запрос ДО retriever'а — интерфейсы дней 22-24 не трогаются.
`conversationalQuery=false` (default) → байт-идентично дню 24.

### 2. Сценарии: 2 JSON + команда (не код)
JSON-сценарии расширяемы пользователем, демонстрируют production-like CLI, переиспользуют паттерн
`/rag eval` (classpath-загрузка, CitationDetector, mordant-таблица). `/rag scenario` прогоняет с
изоляцией (`reset()` + `setWorkingMemory(goal)` + RAG-on), восстанавливая исходный режим в `finally`.

### 3. RAG default ON
К концу недели 5 RAG — основная фича. Проверено безопасно: тесты ContextAwareAgentRagTest явно
передают `ragEnabled` в конструктор, ConfigRepositoryTest не затрагивает rag. Выключение — через
config/env/`/rag off`.

### 4. GAP-C (авто-экстракция WorkingMemory) отложен
Цель задаётся вручную через сценарий (`setWorkingMemory`). Авто-извлечение уточнений/ограничений из
диалога (по образцу `ProfileExtractor` дня 12) — nice-to-have, отложено. Сценарии валидируются и без него.

## Границы дня 25

- Токен-стриминг (SSE) — Phase 2.
- Авто-экстракция WorkingMemory (GAP-C) — отложено.
- Веб-интерфейс — вне scope (задание допускает CLI).
- Conversational LLM-rewriter (расширение интерфейса QueryRewriter) — отложено (enrichment достаточен).
