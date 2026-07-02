# 02 — Конфиг и пути (`config/`)

## AppConfig (добавлено поле)
```kotlin
val rag: RagConfig = RagConfig()   // default → старые config.json грузятся без ошибок
```

## AppPaths (добавлены пути, XDG `dataDir.resolve("rag")`)
```kotlin
val ragDir: Path get() = dataDir.resolve("rag")
val ragIndexFile: Path get() = ragDir.resolve("index.json")
val ragIndexFixed: Path get() = ragDir.resolve("index-fixed.json")
val ragIndexStructural: Path get() = ragDir.resolve("index-structural.json")
```
Один файл на стратегию → `/rag compare` строит оба независимо.

## ConfigRepository.load() — env override (по образцу MCP/model)
```
CLI_AGENT_RAG_PROVIDER          → rag.embeddingProvider
CLI_AGENT_RAG_EMBEDDING_MODEL   → rag.embeddingModel
CLI_AGENT_RAG_EMBEDDING_URL     → rag.embeddingBaseUrl
CLI_AGENT_RAG_CHUNK_SIZE        → rag.chunkSizeTokens
CLI_AGENT_RAG_CHUNK_OVERLAP     → rag.chunkOverlapTokens
```
`corpusRoots`/`defaultStrategy` через env НЕ задаются (только config.json, как `mcp`-массив).

## Schema evolution
`RagConfig` — все поля с defaults → старые config.json без секции `rag` грузятся как `RagConfig()`
(RAG off, поведение дней 1-20). 0 регрессий.
