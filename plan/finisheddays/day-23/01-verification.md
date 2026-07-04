# День 23 — Верификация

## Проверка пунктов задания (`plan/newdays/day23.md`)

### ✅ 1. «Добавьте второй этап после поиска»

**Реализовано:** `RagRetriever.retrieve()` расширен до pipeline
`rewrite → embed → topK(pool) → rerank → take(topK)`. Реранкер — отдельный интерфейс
`Reranker` (`rag/rerank/Reranker.kt`), вызывается ПОСЛЕ `topK`-поиска кандидатов.

**Тест:** `RagRetrieverDay23Test.llm reranker reorders candidates by judge scores` — кандидат с
низким cosine, но высокой LLM-оценкой поднимается наверх.

### ✅ 2. «reranker или фильтр релевантности (порог similarity / отдельная модель / heuristic)»

**Реализованы все три варианта:**
- **Порог similarity:** `ThresholdReranker` — `filter { score >= threshold }`.
- **Heuristic:** `HeuristicReranker` — term-overlap Jaccard `finalScore = alpha*cosine + (1-alpha)*overlap`.
- **Отдельная модель:** `LlmReranker` — GLM-as-judge, batch-оценка 0–10 по запросу↔чанк.

**Тесты:** `RerankersTest` (11 кейсов) — пороги, alpha edge-cases, term-overlap пересортировка,
empty list, enum aliases.

### ✅ 3. «порог отсечения нерелевантных результатов»

**Реализовано:** `RagConfig.similarityThreshold` (Float, default 0.0 = без отсечения).
Используется `ThresholdReranker`. Типичные пороги: 0.3 (weak), 0.5 (умеренный), 0.7 (строгий).

**Тест:** `RerankersTest.threshold filters out low-score chunks` — [0.8, 0.3, 0.6] @ threshold=0.5
→ [0.8, 0.6].

### ✅ 4. «топ-K до и после фильтрации»

**Реализовано:**
- **До:** `RagConfig.candidatePoolSize` (default 20) — `topK(qVec, chunks, candidatePoolSize)`.
- **После:** `RagConfig.topK` (default 5, день 22) — `.take(topK)` от результата реранка.

В `RagRetriever.retrieve()`: `pool = topK(qVec, chunks, maxOf(candidatePoolSize, topK))` → pool не
меньше topK (иначе финальный take обрежет релевантное).

### ✅ 5. «query rewrite» (из «Результат: ... + query rewrite»)

**Реализовано:** `rag/rewrite/` — 3 стратегии:
- `IdentityQueryRewriter` — pass-through (день 22).
- `HeuristicQueryRewriter` — lowercase, удаление RU/EN стоп-слов, нормализация пунктуации.
- `LlmQueryRewriter` — GLM переформулирует запрос (синонимы, расширение аббревиатур).

**Тесты:** `HeuristicQueryRewriterTest` (9 кейсов) — стоп-слова RU/EN, сохранение терминов,
edge-cases, enum.

### ✅ 6. «сравните качество без фильтра/rewriting»

**Реализовано:** `/rag compare-modes` — матрица {rewrite: identity, heuristic, llm} ×
{rerank: none, threshold, heuristic, llm} = 12 режимов. Для каждого — прогон всех eval-вопросов,
покрытие expectedKeywords. mordant-таблица + подсветка лучшего режима.

Baseline `rw=identity|rr=none` = день 22 (без фильтра/rewriting).

### ✅ 7. «качество с фильтром»

**Реализовано:** та же матрица включает все режимы с фильтром (threshold, heuristic, llm) и
rewrite (heuristic, llm). Δ видна в таблице.

## Архитектурные инварианты

### ✅ Backward compatibility (день 22 не сломан)

`rewriter=null + reranker=null` → `topK(qVec, chunks, topK)` — байт-идентично дню 22.
**Тест:** `RagRetrieverDay23Test.rewriter null and reranker null equals day 22 behavior`.

Все новые `RagConfig` поля с defaults → старый `config.json` грузится без правок.

### ✅ Мягкая деградация (как день 22)

- LLM-ошибка в rewrite → исходный запрос (`LlmQueryRewriter`).
- LLM-ошибка/unparseable в rerank → исходный порядок (`LlmReranker`).
- `CancellationException` re-throw (AGENTS.md — никогда не глотать).

**Тест:** `RagRetrieverDay23Test.llm reranker returns original order on LLM error`.

### ✅ Единый retrieval-путь (DRY)

`RagRetriever` — единственная точка pipeline. Не дублируется в `RagCommands`/`ChunkingComparison`.
Реранк/rewrite — плагины через интерфейсы.

### ✅ Agent.chat() / PromptBuilder неизменны

`Agent` интерфейс не менялся. `PromptBuilder.renderRetrievedBlock` (день 22) рендерит результат
`retrieve()` как есть — реранк прозрачен для агента.

### ✅ suspend для IO, чистые функции без IO

`LlmQueryRewriter`/`LlmReranker` — `suspend` (HTTP). `ThresholdReranker`/`HeuristicReranker`/
`HeuristicQueryRewriter` — чистые функции (suspend по контракту интерфейса, без IO по факту).

## Сборка и тесты

```
./gradlew build → BUILD SUCCESSFUL
```

- **447 тестов**, 0 failures, 0 errors.
- Новые тесты дня 23: 33 (HeuristicQueryRewriter 9 + Rerankers 11 + RagRetrieverDay23 8 +
  Identity/enum кейсы).
- Существующие тесты дней 1–22 не сломаны (RagRetrieverTest дня 22 зелёный — backward compat).

## Файлы

**Новые (8 main + 4 test):**
- `rag/rewrite/QueryRewriter.kt` (interface + IDENTITY + enum)
- `rag/rewrite/HeuristicQueryRewriter.kt`
- `rag/rewrite/LlmQueryRewriter.kt`
- `rag/rerank/Reranker.kt` (interface + enum)
- `rag/rerank/ThresholdReranker.kt`
- `rag/rerank/HeuristicReranker.kt`
- `rag/rerank/LlmReranker.kt`
- `rag/RagFactories.kt` (queryRewriterOf/rerankerOf)
- `src/test/.../rag/rewrite/HeuristicQueryRewriterTest.kt`
- `src/test/.../rag/rerank/RerankersTest.kt`
- `src/test/.../rag/RagRetrieverDay23Test.kt`

**Модифицированы (5):**
- `rag/RagModels.kt` (+4 поля RagConfig)
- `rag/RagRetriever.kt` (pipeline + setters)
- `cli/RagCommands.kt` (toggle + compare-modes + display)
- `cli/ChatCommand.kt` (wiring)
- `cli/ReplEngine.kt` (completer)
