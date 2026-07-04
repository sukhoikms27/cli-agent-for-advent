# День 24 — Цитаты, источники и анти-галлюцинации

> Ответы с обязательными источниками и цитатами + режим «не знаю» при слабом контексте

## Что сделано

Доработал RAG дня 23 так, чтобы модель **обязательно** возвращала источники (source + section/chunk_id)
и цитаты (фрагменты из найденных чанков), а при слабом контексте — программно отказывалась отвечать
(canned «не знаю» без вызова LLM). Плюс — UX-улучшения: live-таймер в спиннере и серая строка
длительности после ответа.

### 1. Анти-галлюцинации: режим «не знаю» (усиление задания)

`RagConfig.dontKnowThreshold` (новое поле, default `0.0` = выключено, backward-compat с днём 23).
При `max similarity < порога` (или 0 чанков совпали) — агент возвращает canned-response **без
вызова LLM** через `CannedResponses.weakContext()`. Persist'ится в history как обычный assistant-ответ.

- Отдельно от `similarityThreshold` дня 23 (тот фильтрует чанки для реранкера; этот — отказывается отвечать).
- **НЕ срабатывает** при `retrieve()=null` (Ollama down / пустой индекс) — там мягкая деградация дня 22.
- env override: `CLI_AGENT_RAG_DONT_KNOW_THRESHOLD`.

### 2. Обязательные источники и цитаты

- **Усиленный промпт** (`PromptBuilder.renderRetrievedBlock`): требует структурированный ответ
  (Ответ / Источники / Цитаты) + инструкцию «не знаю, уточните» при отсутствии ответа в чанках.
- **Пост-чек** (`CitationDetector`, pure function): проверяет ответ на упоминание basename источников
  и наличие кавычек ≥15 символов / substring-overlap ≥40 chars с текстом чанков. Warning через
  `logger` (поверх спиннера), НЕ блокирует и НЕ re-prompt — дёшево. `citationLogger` колбэк —
  для `/rag eval` и тестов.

### 3. Обновлённые контрольные вопросы + расширенный корпус

**Корпус:** `corpusRoots` расширен с `["plan","docs","README.md"]` до
`["plan","docs","README.md","AGENTS.md","src/main/kotlin"]`. Раньше `AGENTS.md` и `.kt`-исходники
НЕ индексировались, но 7/10 eval-вопросов ссылались на `AGENTS.md` (невалидно).

**10 нестандартных вопросов** (`resources/rag/eval-questions.json`): каждый со своим источником
и конкретным фактом (числа/алгоритмы/имена enum'ов) — batchSize=32, chunkSize=500, FNV-1a,
REDUNDANCY(3), категории инвариантов, InteractionMode, maxToolRounds=8, cosine [−1,1],
TransitionOutcome.

### 4. Расширенный `/rag eval`

Добавлены колонки `RAG src` (✓/✗) и `RAG cite` (✓/✗) + итоги:
```
RAG answers with sources:   A/B (C%)   ← день 24
RAG answers with citations: A/B (C%)   ← день 24
```

### 5. UX: live-таймер + серая строка длительности

- **Live-таймер** (`AppTerminal.withTimedSpinner`): лейбл спиннера обновляется каждые 120ms —
  `Thinking… 00:00:03`. Время через `System.nanoTime()` в closure.
- **Серая строка** (`AppTerminal.printDuration`): `⏱ HH:MM:SS` серым цветом (mordant `gray`)
  после ответа агента.
- **Прогрессивные логи этапов**: `📚 RAG: 5 chunk(s), best similarity 0.82` → `🤖 Generating answer via LLM…`
  → `🚫 Anti-hallucination: best 0.20 < threshold 0.40 → canned «не знаю»`.

## Архитектурные решения

- **Программный canned-response, не LLM-генерация отказа** — 100% гарантия, дёшево, persist'ится.
- **Пост-чек warning-only, не re-prompt** — дёшево, не блокирует; модель может не послушаться промпт.
- **`dontKnowThreshold=0.0` (default) → backward-compat** с днём 23 (даже при низком сходстве LLM отвечает).
- **Мягкая деградация дня 22 сохранена**: `retrieve()=null` → агент без RAG-блока (не canned).
- **`Agent.chat()` сигнатура неизменна** — как в днях 22–23.
- **`CancellationException`** нигде не глотаем (AGENTS.md).
- **Pure `CitationDetector`** — без I/O, легко тестировать.

## Тесты

- **468 тестов**, 0 failures, 0 errors (`./gradlew build` зелёный).
- 21 новый тест: CitationDetector (12), PromptBuilder день-24 (3), ContextAwareAgentRag день-24 (6).
- Существующие тесты дней 1–23 не сломаны (backward compat: `dontKnowThreshold=0.0` → день 23).

## Границы (не в день 24)

- **Мини-чат с RAG + памятью задачи** (production-like) — день 25.
- **Токен-стриминг** (SSE / `stream:true`) — Phase 2, отдельный PR (progressive = логи этапов).
- **Re-prompt при отсутствии цитат** (дорогая гарантия) — вне scope (warning-only).
- **CrossEncoder через bge-reranker** — закрыто LLM-reranker'ом дня 23.

## Подробнее

- `00-task.md` — контекст задания + лекции недели 5 + маппинг требований→реализация.
- `01-verification.md` — чек-лист соответствия + архитектурные инварианты.
