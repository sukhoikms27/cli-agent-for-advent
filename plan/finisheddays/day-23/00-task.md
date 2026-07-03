# День 23 — Контекст задания

## Задание (из `plan/newdays/day23.md`)

> 🔥 День 23. Реранкинг и фильтрация
>
> Добавьте второй этап после поиска:
> 👉 reranker или фильтр релевантности (порог similarity / отдельная модель / heuristic)
>
> Настройте:
> 👉 порог отсечения нерелевантных результатов
> 👉 топ-K до и после фильтрации
>
> Сравните:
> 👉 качество без фильтра/rewriting
> 👉 качество с фильтром
>
> Результат: Улучшенный RAG: фильтрация/реранкинг + query rewrite + сравнение режимов

## Контекст лекции недели 5 (RAG)

**Реранкинг** (лекция `videossummary/05_RAG_...`): при поиске топ-20 похожих чанков часть результатов
может быть нерелевантной — схожая тематика, но не отвечает на вопрос.

> Пример: запрос «Как настроить CICD для Kotlin Multiplatform»
> - Релевантно: «Настройка GitHub Actions для KMP»
> - Нерелевантно: «Обзор CICD инструментов за 2023 год» (тема похожа, ответа нет)

Pipeline реранкинга из лекции:
1. Ищем топ-20 кандидатов (быстро, миллисекунды) — `topK(candidatePoolSize)`
2. CrossEncoder переоценивает каждый чанк относительно запроса (медленно, секунды) — `Reranker`
3. LLM получает отфильтрованные, наиболее точные данные — `.take(topK)`

**Метрики качества** (лекция): правдивость ответов = извлечение фактов из чанков. Измеряется покрытием
`expectedKeywords` контрольных вопросов (как в день 22 `/rag eval`).

## Маппинг задания → реализация

| Требование задания | Реализация |
|---|---|
| reranker: порог similarity | `ThresholdReranker` (`rag/rerank/ThresholdReranker.kt`) — `filter { score >= threshold }` |
| reranker: heuristic | `HeuristicReranker` (`rag/rerank/HeuristicReranker.kt`) — term-overlap Jaccard: `alpha*cosine + (1-alpha)*overlap` |
| reranker: отдельная модель | `LlmReranker` (`rag/rerank/LlmReranker.kt`) — GLM-as-judge, batch-оценка 0–10, tolerant JSON-парсинг |
| порог отсечения | `RagConfig.similarityThreshold` (0.0 = без отсечения) + `ThresholdReranker` |
| топ-K до фильтрации | `RagConfig.candidatePoolSize` (default 20, лекция: «топ-20 кандидатов») |
| топ-K после фильтрации | `RagConfig.topK` (default 5, день 22) — финальное число чанков в промпте |
| query rewrite | `rag/rewrite/`: `IdentityQueryRewriter` / `HeuristicQueryRewriter` / `LlmQueryRewriter` |
| сравнение без фильтра/rewriting | `/rag compare-modes` — матрица 3 rewrite × 4 rerank = 12 режимов, отчёт покрытия keywords |
| сравнение с фильтром | та же матрица включает baseline (identity+none = день 22) и все режимы дня 23 |

## Архитектура

```
query → [QueryRewriter] → embed(rewritten) → topK(candidatePool) → [Reranker] → .take(topK) → в промпт
```

- **QueryRewriter** — до эмбеддинга (меняет текст запроса). `IDENTITY` = pass-through (день 22).
- **Reranker** — после topK (переоценивает кандидатов). `null` = без 2-го этапа (день 22).
- **Backward compat:** `rewriter=identity + reranker=none` → байт-идентично дню 22
  (`topK(qVec, chunks, topK)`).

Pipeline реализован в `RagRetriever.retrieve()` — единая точка (DRY), переиспользуется агентом
и `/rag eval`/`compare-modes`.

## Решения и обоснования

### 1. Все 3 опции reranker + 2 опции rewrite

Задание даёт выбор («порог / отдельная модель / heuristic»). Реализованы **все три** реранкера +
**две** стратегии rewrite, покрывая полный спектр подходов из лекции. Выбор — через config/CLI
runtime-toggle, без перекомпиляции.

### 2. LLM-reranker как batch (1 запрос, не N)

Лекция описывает CrossEncoder как «медленно, секунды». Наивная реализация — N LLM-запросов на чанк —
недопустимо дорога. `LlmReranker` отправляет **все кандидаты в одном промпте** → LLM возвращает
JSON `{chunkId: score}` → один запрос на retrieval. Tolerant-парсинг (regex-fallback) переживает
markdown-fences и обрывы.

### 3. Мягкая деградация сохранена

Как день 22: ошибка LLM (rewrite/rerank) → исходный запрос/порядок (не null, не throw).
`retrieve()` падает только при ошибке эмбеддинга/пустом индексе. Агент отвечает без RAG-блока.

### 4. `RagConfig` + 4 поля с defaults (schema evolution)

Все новые поля (`candidatePoolSize`, `similarityThreshold`, `queryRewriter`, `reranker`) с defaults,
воспроизводящими день 22. Старый `config.json` грузится без правок (AGENTS.md: add fields with
defaults, never remove).

### 5. Runtime-toggle через `setRewriter`/`setReranker`

Симметрично `setRagEnabled` дня 22 — A/B-сравнение в одном чате без перезапуска. `/rag compare-modes`
автоматизирует прогон матрицы режимов, сохраняя/восстанавливая исходный runtime-режим.

## Границы дня 23

- Обязательные цитаты + режим «не знаю» при слабом контексте — день 24 (но `similarityThreshold` —
  задел: при `topK`-после-фильтрации = 0 → слабый контекст).
- Мини-чат с RAG + памятью задачи — день 25.
- Streaming, CrossEncoder через отдельную Ollama-модель (bge-reranker) — вне scope (LLM-reranker
  через z.ai GLM покрывает «отдельную модель» из задания).
