# 01 — Модели данных (`rag/RagModels.kt`)

`@Serializable` data classes со **всеми полями по defaults** (schema evolution, AGENTS.md):

- **`RagDocument`** — один файл корпуса: `id` (стабильный хэш пути), `source`, `title` (H1 или имя),
  `content`, `fileType`, `createdAt`.
- **`RagChunk`** — фрагмент для поиска. Метаданные задания day-21: `chunkId` (chunk_id), `source`,
  `title` (title/file), `section`, `documentId`, `text`, `index`, `tokenCount`, `embedding` (List<Float>?
  — null до генерации), `strategy`.
- **`RagIndex`** — индекс одной стратегии: `strategy`, `embeddingModel`, `dimension`, `documents`,
  `chunks`, `createdAt`. Свойство `embeddedChunks` фильтрует чанки с вектором.
- **`ScoredChunk`** — чанк + косинусный score (результат `topK`).
- **`RagConfig`** — часть `AppConfig.rag`: `enabled`, `embeddingProvider`, `embeddingModel`,
  `embeddingBaseUrl`, `corpusRoots`, `chunkSizeTokens` (default 500), `chunkOverlapTokens` (default 100),
  `defaultStrategy`.

Векторы сериализуются как `List<Float>` — kotlinx-serialization handles нативно.
