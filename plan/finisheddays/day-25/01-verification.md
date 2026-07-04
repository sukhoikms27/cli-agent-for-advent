# День 25 — Верификация

## Сборка

- `./gradlew build` — **BUILD SUCCESSFUL**, 479 тестов, 0 failures, 0 errors.
- 11 новых тестов (ContextAwareAgentConversational 3 + RagScenario 5 + RagConfigDefault 3).
- Существующие тесты дней 1-24 не сломаны.

## Чек-лист соответствия заданию day-25

| Требование | Статус | Реализация / тест |
|---|---|---|
| хранит историю диалога | ✅ | `MemoryStore`/`JsonChatStore` (дни 1-24). REPL персистентный |
| RAG каждый ход | ✅ | `ContextAwareAgent.chat()` retrieve. + **default ON** (день 25) |
| отвечает с учётом | ✅ | `PromptBuilder` инъекция `[Retrieved context]` (день 22) |
| всегда выводит источники | ✅ | промпт + пост-чек `CitationDetector` (день 24) |
| память задачи: цель | ✅ | `setWorkingMemory(currentTask=goal)` в harness. Тест: `RagConfigDefault` |
| память задачи: уточнения | ✅ | conversation-aware retrieval обогащает follow-up. Тест: `conversationalQuery true enriches query` |
| 2 сценария по 10-15 сообщений | ✅ | `rag-architecture.json` (10 turns), `task-fsm.json` (10 turns). Тест: `RagScenarioTest` |
| не теряет цель | ✅ | `reset()` изоляция + goal retention в отчёте. Тест: `printScenarioReport` (goal retained ✓) |
| источники в каждом ответе | ✅ | per-turn `CitationDetector` + сводный % в `printScenarioReport` |

## Архитектурные инварианты (проверены)

### 1. Backward compat с днём 24
- `conversationalQuery=false` (default) → `retrieve(userMessage)` точно, без обогащения.
  Тест: `conversationalQuery false default passes plain userMessage — backward compat day 24`.
- `RagConfig()` дефолты: `dontKnowThreshold=0.0`, `corpusRoots` из дня 24 сохранены.
  Тест: `RagConfig preserves dontKnowThreshold and corpusRoots from day 24`.

### 2. RAG default ON безопасен
- `RagConfig().enabled == true` (день 25). Тест: `default RagConfig has RAG enabled`.
- Тесты ContextAwareAgentRagTest/Conversational явно передают `ragEnabled` в конструктор — не зависят
  от дефолта (проверено grep: 0 зависимостей от `RagConfig().enabled`).
- ConfigRepositoryTest не затрагивает `rag.enabled` (только `mcp.enabled`).

### 3. Мягкая деградация дней 22-24 сохранена
- `retrieve()=null` → агент без RAG-блока (не canned). `conversationalQuery` не влияет на это.
- `CancellationException` пробрасывается (handleScenario try/catch re-throw).

### 4. Изоляция сценариев
- `reset()` между сценариями чистит history/workingMemory/facts/summary.
- Long-term memory НЕ трогается (кросс-чат, правильно).
- Исходный RAG-режим восстанавливается в `finally` (как `handleEval`/`handleCompareModes`).

### 5. Conversation enrichment edge-cases
- Первый ход (история пуста) → без блока «Предыдущие вопросы». Тест: `first turn no history`.
- `currentTask=null` → без блока «Контекст задачи».
- Длинная история → только 2 последние реплики (`takeLast(2)`), не раздуваем embedding.

## Smoke-тест (ручной, требует Ollama + z.ai)

```
/rag index                    # переиндексация корпуса (с AGENTS.md + .kt из дня 24)
/rag scenario rag-architecture # прогон 10-turn сценария
# → 10 ответов, per-turn src/cite, итоги: Sources X/10, Citations Y/10, Goal retained ✓
/rag scenario task-fsm         # второй сценарий
# → изолированный прогон (reset между сценариями)
/rag config                    # проверить conversationalQuery, enabled=ON
```

Включение conversation-aware (опционально, для лучшего follow-up):
```
# config.json: rag.conversationalQuery = true
# или env: CLI_AGENT_RAG_CONVERSATIONAL_QUERY=true
/rag scenario rag-architecture # follow-up «а сколько для этого нужно?» теперь находят контекст
```

## Что НЕ в день 25 (границы)

- Токен-стриминг (SSE / `stream:true`) — Phase 2.
- Авто-экстракция WorkingMemory (GAP-C) — отложено (цель задаётся вручную).
- Веб-интерфейс — вне scope (задание допускает CLI).
- Conversational LLM-rewriter — отложено (enrichment достаточен).
