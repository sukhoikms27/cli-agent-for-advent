# День 25 — Мини-чат с RAG + памятью (production-like)

> Мини-чат с RAG + источниками + памятью задачи

## Что сделано

Закрывающий день недели 5: production-like мини-чат, который хранит историю, ищет контекст через
RAG каждый ход, отвечает с источниками, помнит цель диалога и валидируется на 2 длинных сценариях.
90% функционала уже было в днях 22-24; день 25 добавляет главное — scripted-сценарии и
conversation-aware retrieval, плюс включает RAG по умолчанию.

### 1. Scripted-сценарии + команда `/rag scenario` (главный deliverable)

Требование задания: «2 длинных сценария по 10-15 сообщений — не теряет цель и продолжает выдавать
ответы с источниками». Реализовано:
- **2 JSON-сценария** в `src/main/resources/rag/scenarios/`:
  - `rag-architecture.json` (10 turns) — разбор RAG-пайплайна, с follow-up репликами.
  - `task-fsm.json` (10 turns) — разбор task state machine, с follow-up.
- **`/rag scenario <name>`** — прогоняет сценарий: `reset()` (изоляция) → `setWorkingMemory(currentTask=goal)`
  (память задачи) → RAG-on → прогон всех turns через `chat()` с персистентной историей → per-turn
  пост-чек (CitationDetector + keywords) → сводный отчёт (Sources/Citations/Goal retained).

### 2. Conversation-aware retrieval (GAP-B, production-like)

`RagConfig.conversationalQuery` (новое поле, default `false` → backward-compat с днём 24). При `true`
в `ContextAwareAgent.chat()` запрос для `retrieve()` обогащается:
- **Целью диалога** (`WorkingMemory.currentTask`) — фрейм для follow-up.
- **Последними 2 user-репликами** истории — разрешение анафоры («это», «для этого»).

Follow-up «а сколько для этого нужно?» теперь находят контекст (раньше `retrieve()` видел только
короткую фразу). Реализовано через `buildConversationQuery()` — **не ломает интерфейс `QueryRewriter`**
(backward-compat дней 22-24 сохранён). env: `CLI_AGENT_RAG_CONVERSATIONAL_QUERY`.

### 3. RAG включён по умолчанию

`RagConfig.enabled` изменён с `false` на `true`. К концу недели 5 RAG (с индексом, источниками,
цитатами, анти-галлюцинациями, сценариями) — основная фича мини-чата. Выключать явно через
config/env/`/rag off`. Backward-compat безопасно: тесты `ContextAwareAgentRagTest` явно передают
`ragEnabled`, не зависят от дефолта.

## Архитектурные решения

- **Context-enrichment в агенте, не в QueryRewriter** — backward-compat: интерфейс `rewrite(query)`
  дня 23 не меняется, enrichment идёт до retriever'а в `ContextAwareAgent`. `conversationalQuery=false`
  → байт-идентично дню 24.
- **`reset()` между сценариями** — чистит history/workingMemory (изоляция), НЕ long-term (кросс-чат).
- **Цель задаётся явно через `setWorkingMemory`** (GAP-C отложен) — сценарий сам ставит currentTask;
  авто-экстракция уточнений/ограничений из диалога — за рамками дня 25.
- **`printScenarioReport` suspend** — прямой вызов `getWorkingMemory()` для goal-retention, без
  `runBlocking` (handleScenario уже suspend).
- **`listScenarios()` фиксирует известные сценарии** — classpath-перечисление ненадёжно в JAR.

## Тесты

- **479 тестов**, 0 failures (`./gradlew build` зелёный).
- 11 новых: ContextAwareAgentConversational (3), RagScenario (5), RagConfigDefault (3).
- Backward-compat: `conversationalQuery=false` → день 24; `RagConfig()` defaults проверены.

## Границы (не в день 25)

- **Токен-стриминг** (SSE/`stream:true`) — Phase 2.
- **Авто-экстракция WorkingMemory** (агент сам извлекает уточнения/ограничения/цель) — отложено
  (GAP-C; цель задаётся вручную через сценарий).
- **Веб-интерфейс** — вне scope (задание допускает CLI).
- **Conversational LLM-rewriter** (расширение интерфейса QueryRewriter context-параметром) —
  отложено (enrichment достаточен).

## Подробнее

- `00-task.md` — контекст задания + GAP-анализ + маппинг требований→реализация.
- `01-verification.md` — чек-лист соответствия + архитектурные инварианты.
