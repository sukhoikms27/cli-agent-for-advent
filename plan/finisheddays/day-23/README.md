# День 23 — Реранкинг и фильтрация RAG

> Улучшенный RAG: фильтрация/реранкинг + query rewrite + сравнение режимов

## Что сделано

Расширил retrieval-pipeline дня 22 вторым этапом — реранкингом/фильтрацией, плюс query rewrite
на стороне запроса:

```
query → [QueryRewriter] → embed(rewritten) → topK(candidatePool) → [Reranker] → .take(topK) → в промпт
```

### Реранкеры (3 варианта из задания)
| Тип | Класс | Подход |
|---|---|---|
| **threshold** | `ThresholdReranker` | фильтр по порогу косинусного сходства |
| **heuristic** | `HeuristicReranker` | term-overlap Jaccard: `alpha*cosine + (1-alpha)*overlap` |
| **llm** | `LlmReranker` | GLM-as-judge, batch-оценка 0–10 (1 LLM-запрос, не N) |

### Query rewrite (2 стратегии)
| Тип | Класс | Подход |
|---|---|---|
| **identity** | `IdentityQueryRewriter` | pass-through (день 22) |
| **heuristic** | `HeuristicQueryRewriter` | lowercase + удаление RU/EN стоп-слов |
| **llm** | `LlmQueryRewriter` | GLM переформулирует запрос (синонимы, аббревиатуры) |

### Конфигурация (`RagConfig`, +4 поля с defaults)
```kotlin
candidatePoolSize: Int = 20,        // топ-K ДО фильтрации (лекция: «топ-20 кандидатов»)
similarityThreshold: Float = 0.0f,  // порог отсечения (0 = без отсечения)
queryRewriter: String = "identity", // identity | heuristic | llm
reranker: String = "none"           // none | threshold | heuristic | llm
```

## CLI

```
/rag rewrite <identity|heuristic|llm>   # runtime-toggle query rewrite
/rag rerank <none|threshold|heuristic|llm>  # runtime-toggle реранкера
/rag compare-modes                       # A/B-матрица 3 rewrite × 4 rerank = 12 режимов
```

`/rag compare-modes` прогоняет все eval-вопросы в каждом режиме и выводит mordant-таблицу покрытия
expectedKeywords + подсветку лучшего режима. Baseline `identity+none` = день 22.

## Архитектурные решения

- **Backward compat:** `identity+none` → байт-идентично дню 22. Старый config.json грузится (defaults).
- **Мягкая деградация:** LLM-ошибка в rewrite/rerank → исходный запрос/порядок (не throw, не null).
- **Batch LLM-reranker:** все кандидаты в 1 промпте → JSON `{chunkId: score}` (не N запросов).
  Tolerant-парсинг (regex-fallback) переживает markdown-fences/обрывы.
- **Единый retrieval-путь:** `RagRetriever` — единственная точка pipeline (DRY). Реранк/rewrite — плагины.
- **Runtime-toggle:** `setRewriter`/`setReranker` (как `setRagEnabled` дня 22) — A/B без перезапуска.
- **Agent/PromptBuilder неизменны:** реранк прозрачен — `retrieve()` возвращает финальные чанки.

## Тесты

- **447 тестов**, 0 failures, 0 errors (`./gradlew build` зелёный).
- 33 новых теста: HeuristicQueryRewriter (9), Rerankers (11), RagRetrieverDay23 (8), enum/identity.
- Существующие тесты дней 1–22 не сломаны (RagRetrieverTest дня 22 зелёный — backward compat).

## Границы (не в день 23)

- **Обязательные цитаты + режим «не знаю»** при слабом контексте — день 24. Задел: `similarityThreshold`
  (при `topK`-после-фильтрации = 0 → слабый контекст).
- **Мини-чат с RAG + памятью задачи** — день 25.
- Streaming, CrossEncoder через отдельную Ollama-модель (bge-reranker) — вне scope. LLM-reranker
  через z.ai GLM покрывает «отдельную модель» из задания.

## Подробнее

- `00-task.md` — контекст задания + маппинг требований→реализация.
- `01-verification.md` — проверка всех 7 пунктов задания + архитектурные инварианты.
