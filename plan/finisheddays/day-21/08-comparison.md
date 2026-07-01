# 08 — ChunkingComparison (`rag/ChunkingComparison.kt`)

Сравнение 2 стратегий — требование задания. Не просто подсчёт, а **содержательное** сопоставление.

## compare(documents, probeQueries): ComparisonReport?
1. Строит **оба** индекса (fixed + structural) по одному корпусу — через тот же embedder (требует
   работающую Ollama; ошибка → null).
2. **Статистика** по каждому: `StrategyStats(name, chunkCount, embeddedCount, minTokens, avgTokens,
   maxTokens, dimension)`.
3. **Пробный retrieval** по `probeQueries` (default — 5 запросов про архитектуру проекта): для каждого
   запроса → embed → `topK(k=3)` на каждом индексе → `ProbeResult(query, fixed, structural)`.

## ComparisonReport (чистые данные)
`ComparisonReport(documents, fixed, structural, probes)`. Печатью (mordant-таблица + side-by-side
retrieval) занят CLI (`/rag compare`). Логика без I/O → тестируется без терминала.

Лекция недели 5: «оптимальные параметры (размер чанка, процент overlap) — экспериментально» —
этот класс тот самый эксперимент: видно, какая стратегия даёт более релевантные чанки на тех же
запросах, а не только разное число/размер.
