# День 28 — Локальная LLM + RAG (ЧЕРНОВИК плана)

> Неделя 6, задание 3 из `plan/newdays/day28.md`

## Задание

🔥 **День 28. Локальная LLM + RAG**

Подключите локальную LLM к вашему RAG-пайплайну:
- 👉 используйте индекс из Недели 6 (corpus индекс дней 21-24)
- 👉 retrieval выполняется локально (Ollama embeddings)
- 👉 генерация ответа — через локальную модель

Сравните:
- 👉 ответы локальной модели
- 👉 ответы облачной модели (если есть)

Оцените: качество, скорость, стабильность.

**Результат:** RAG-система, полностью работающая локально.

## Контекст кодовой базы (из Research)

RAG-пайплайн **уже полностью локальный** с дня 21:
- `OllamaEmbeddingClient` (`rag/embedding/OllamaEmbeddingClient.kt`) — embeddings через `http://localhost:11434/api/embed`, модель `nomic-embed-text`.
- `RagRetriever` (`rag/RagRetriever.kt:44-124`) — retrieve → rewrite → embed → topK → rerank.
- `RagConfig.embeddingProvider="ollama"`, `embeddingBaseUrl="http://localhost:11434"`.

Но генерация ответа (LLM) по умолчанию — облачная (z.ai glm-5.1). День 28 = **полностью локальный RAG**: embeddings + LLM-генерация обе на Ollama.

Используя runtime-switch из дня 27 (`/provider local`) или env `CLI_AGENT_PROVIDER=ollama`, вся RAG-цепочка становится локальной без правок RAG-кода.

## Что добавить в код

1. **Команда сравнения `/rag compare-local`** (или расширить существующий `/rag compare-modes`):
   - Прогон одного и того же запроса через local LLM и cloud LLM с одинаковым retrieved-контекстом.
   - Вывод: local-ответ vs cloud-ответ + метрики (latency, token count, наличие цитат).
   - По образцу `/rag eval` (день 24) и `/rag scenario` (день 25).
2. **Метрики качества/скорости/стабильности**:
   - Скорость: latency (ms), tokens/sec.
   - Качество: CitationDetector score (день 24), длина ответа.
   - Стабильность: multiple runs, variance.
3. **Batch-сценарий сравнения** — JSON-список из 5-10 запросов по корпусу, прогон через обе модели, сводная mordant-таблица.
4. **Тесты** — compare-local с mock LLM (local+cloud), метрики.

## Deliverables

- [ ] Полностью локальный RAG: embeddings (nomic-embed-text) + генерация (qwen3:14b) — оба на Ollama, без интернета.
- [ ] Команда `/rag compare-local` — side-by-side сравнение local vs cloud на одном retrieved-контексте.
- [ ] Метрики: latency, tokens/sec, citation score, длина — в сводной таблице.
- [ ] Batch-сценарий 5-10 запросов, документация результата.
- [ ] Тесты зелёные.
- [ ] Отчёт в `swarm-report/day-28-2026-07-11.md`.

## Архитектурные инварианты

- RAG-код (Retriever/Indexer/Embedder) не меняется — только LLM-провайдер.
- backward-compat: cloud RAG (день 25) работает идентично.
- Мягкая деградация: при недоступности Ollama → fallback без RAG-блока (как день 24).
- Корутины: `withContext(Dispatchers.IO)` для HTTP, `withTimeoutOrNull` для долгих local-запросов.

## Связь с последующими днями

- **День 29** оптимизирует локальную модель (на основе метрик дня 28).
- Метрики дня 28 — baseline для дня 29 (before/after).
