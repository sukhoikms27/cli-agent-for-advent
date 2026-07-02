# 10 — Тесты (`src/test/kotlin/com/cliagent/rag/`)

49 unit-тестов, все зелёные. Зеркалят паттерны `mcp/` (MockEngine) и `memory/` (temp-путь).

| Класс | Файлов | Тестов | Покрытие |
|---|---|---|---|
| `VectorMathTest` | 1 | 10 | cosine 1/0/−1/эталон/empty/zero; topK sort/skip-null/fewer/k=0 |
| `chunk/ChunkerTest` | 1 | 13 | fixed: split/ids/metadata/blank/overlap; structural: headings/section/subdivide/h2-h3/intro/metadata; StrategyType parse |
| `embedding/OllamaEmbeddingClientTest` | 1 | 7 | MockEngine: batched order/empty/unavailable(Ollama off)/dimension/retryable/backoff/unparseable error |
| `JsonRagStoreTest` | 1 | 5 | round-trip/absent/corrupt/clear/schema-evolution (старый индекс без embedding) |
| `RagIndexerTest` | 1 | 6 | end-to-end (FakeEmbedder детермин.)/embedder-fail/structural-sections/no-embedder/empty-cursor/progress |
| `DocumentLoaderTest` | 1 | 8 | md+kt/title-fallback/stable-id/skip-build/nonexistent/blank/nested/single-file |

## Паттерны
- **MockEngine** (OllamaEmbeddingClient): injectable HttpClient → тесты без реальной Ollama. `ktor-client-mock`
  добавлен в testImplementation (выровнено с mcp-server, день 18).
- **FakeEmbedder** (RagIndexer): детерминированные векторы из хэша текста — проверяет chunk→embed→save
  связку без сети.
- **@TempDir** (Store, Indexer, Loader): tmp-пути → без патчинга статичных AppPaths.
- **runTest** (корутины): виртуализирует время; retry-delay не висит.
