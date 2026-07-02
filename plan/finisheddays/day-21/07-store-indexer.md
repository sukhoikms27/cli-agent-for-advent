# 07 — Store + Indexer (`rag/JsonRagStore.kt`, `rag/RagIndexer.kt`)

## JsonRagStore (зеркало JsonLongTermStore)
- `load(): RagIndex` — graceful: пустой `RagIndex(strategy="empty")` если файл отсутствует,
  `strategy="corrupt"` если битый (runCatching → null → default). Не падает.
- `save(index)` — `ensureDir()` + `atomicWrite` (UUID temp sibling, UTF-8 явно, ATOMIC_MOVE+REPLACE).
- `clear(strategy)` — сохраняет пустой индекс.
- `path()` — для `/rag stats`.
- Конструктор принимает `Path = AppPaths.ragIndexFile` → тесты подставляют temp-путь.

## RagIndexer (оркестратор)
`RagIndexer(chunker, embedder, store).index(documents, onProgress)`:
1. **Chunking** (`Dispatchers.Default`): `documents.flatMap { chunker.chunk(it) }`.
2. **Embeddings** (`embedder.embed` batched): проставляет `embedding` в каждый чанк. `onProgress(done, total)`
   после каждого batch'а. Ошибка embedder → `return null` (индекс НЕ сохраняется — retry позже).
3. **Save:** `RagIndex(strategy, embeddingModel, dimension, documents, chunks, createdAt)` → `store.save`.
- `embedder=null` → строит индекс **без векторов** (офлайн-дебаг; `embeddedChunks`=0).
- Пустой корпус → сохраняет пустой индекс (не null).
