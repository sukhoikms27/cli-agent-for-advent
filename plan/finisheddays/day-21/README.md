# День 21 — Индексация документов (README / общий план)

> Пайплайн индексации RAG: corpus → chunking → embeddings → локальный индекс (JSON) с метаданными.
> Корневой контекст: [`00-task.md`](./00-task.md). **День 22** надстроит retrieval + инъекцию в
> промпт (этот день заканчивается на индексе и сравнении стратегий).

## Что произошло с архитектурой

Day 21 — **первый из двух RAG-дней недели 5**. Ключевое архитектурное решение: новый top-level
пакет `rag/`, зеркалирующий паттерны `memory/` и `mcp/` (interface + impl + data classes в одном
пакете, JSON-persistence + atomicWrite, suspend + `withContext(Dispatchers.IO)`, DI-швы для тестов).

| Решение | Обоснование |
|---|---|
| **Эмбеддинги — Ollama локально** (`nomic-embed-text`, 768 dim, `/api/embed`) | Как в лекции недели 5: бесплатно, приватно, данные не покидают машину. `EmbeddingClient` — interface, облако подключается позже. |
| **Корпус — документация проекта** (MD + исходники `.kt`) | Самореференциально: агент отвечает по своей архитектуре. 20-30+ страниц без сторонних материалов. |
| **Индекс — JSON** (не FAISS/SQLite) | Задание явно разрешает «FAISS / SQLite / JSON». Для CLI (малый корпус) JSON проще, читаем, 0 зависимостей. Линейный cosine-scan достаточен. |
| **2 стратегии chunking** (`fixed` + `structural`) | Требование задания. Сравнение через таблицу статистики + пробный retrieval по одним и тем же зонд-запросам. |
| **Agent-слой НЕ трогаем** | День 21 = только индексация. Инъекция `[Retrieved context]` в `PromptBuilder`/`buildMessagesToSend` — день 22. |

## Карта файлов

| # | Файл | Тип | Содержание |
|---|---|---|---|
| 00 | [`00-task.md`](./00-task.md) | контекст | Задание курса (day-21), контекст лекции недели 5, решения, маппинг |
| — | [`README.md`](./README.md) | индекс/план | Этот файл |
| 01 | [`01-models.md`](./01-models.md) | реализация | `rag/RagModels.kt`: `RagDocument`, `RagChunk`, `RagIndex`, `ScoredChunk`, `RagConfig` |
| 02 | [`02-config-paths.md`](./02-config-paths.md) | реализация | `AppConfig += RagConfig`, `AppPaths += ragDir/ragIndexFile`, env override `CLI_AGENT_RAG_*` |
| 03 | [`03-document-loader.md`](./03-document-loader.md) | реализация | `rag/DocumentLoader.kt`: обход корпуса .md/.kt → `List<RagDocument>` |
| 04 | [`04-chunking.md`](./04-chunking.md) | реализация | `rag/chunk/`: `ChunkingStrategy` + `FixedSizeChunker` + `StructuralChunker` |
| 05 | [`05-embeddings.md`](./05-embeddings.md) | реализация | `rag/embedding/`: `EmbeddingClient` + `OllamaEmbeddingClient` (`/api/embed`, batched, retry) |
| 06 | [`06-vector-math.md`](./06-vector-math.md) | реализация | `rag/VectorMath.kt`: cosine + topK (без внешних либ) |
| 07 | [`07-store-indexer.md`](./07-store-indexer.md) | реализация | `rag/JsonRagStore.kt` + `rag/RagIndexer.kt`: chunk → embed → save |
| 08 | [`08-comparison.md`](./08-comparison.md) | реализация | `rag/ChunkingComparison.kt`: статистика 2 стратегий + пробный retrieval |
| 09 | [`09-cli.md`](./09-cli.md) | реализация | `cli/RagCommands.kt`: `/rag index|stats|compare|search|config` + status-line |
| 10 | [`10-tests.md`](./10-tests.md) | тесты | `FixedChunkerTest`, `StructuralChunkerTest`, `VectorMathTest`, `OllamaEmbeddingClientTest`, `JsonRagStoreTest`, `RagIndexerTest` |
| 11 | [`11-verification.md`](./11-verification.md) | верификация | `./gradlew build` green, `/rag index`, `/rag compare`, 0 регрессий |

## Итоги

- **Новый пакет** `rag/` (9 файлов): models, loader, 2 chunker'а, embedding client, vector math,
  JSON store, indexer, comparison.
- **0 новых зависимостей** — Ktor (CIO + ContentNegotiation) уже в `build.gradle.kts`.
- **Agent-слой без изменений** — RAG опционален; без Ollama команды `/rag` деградируют с понятным
  сообщением, остальной агент не страдает.
- **0 регрессий** — новый пакет + 2 поля в config (с defaults) + 3 пути в AppPaths + новые
  slash-команды. Существующий flow идентичен.

## Структура модуля (после Day 21)

```
src/main/kotlin/com/cliagent/
├── rag/                        # NEW — RAG indexing pipeline
│   ├── RagModels.kt            # RagDocument, RagChunk, RagIndex, ScoredChunk, RagConfig
│   ├── DocumentLoader.kt       # обход корпуса .md/.kt → List<RagDocument>
│   ├── chunk/
│   │   ├── ChunkingStrategy.kt # interface + enum { FIXED, STRUCTURAL }
│   │   ├── FixedSizeChunker.kt
│   │   └── StructuralChunker.kt
│   ├── embedding/
│   │   ├── EmbeddingClient.kt
│   │   └── OllamaEmbeddingClient.kt
│   ├── VectorMath.kt           # cosine + topK
│   ├── JsonRagStore.kt         # load/save RagIndex (atomicWrite)
│   ├── RagIndexer.kt           # chunk → embed → save
│   └── ChunkingComparison.kt   # статистика 2 стратегий + пробный retrieval
├── config/
│   ├── AppConfig.kt            # +rag: RagConfig = RagConfig() — NEW field
│   ├── AppPaths.kt             # +ragDir/ragIndexFile/ragIndexFixed/ragIndexStructural — NEW
│   └── ConfigRepository.kt     # +env override CLI_AGENT_RAG_* — NEW
└── cli/
    ├── ChatCommand.kt          # +/rag dispatch + status-line RAG — NEW
    └── RagCommands.kt          # NEW — /rag index|stats|compare|search|config
```

## Конвенции кода (AGENTS.md, соблюдены)

- **Persistence:** JSON + atomicWrite (temp + `Files.move(ATOMIC_MOVE)`), XDG-пути.
- **Schema evolution:** `@Serializable` с **defaults** — старые/неполные файлы грузятся без ошибок.
- **Json:** `Json { ignoreUnknownKeys=true; encodeDefaults=true; explicitNulls=false; prettyPrint=true; coerceInputValues=true }`.
- **Coroutines:** `suspend` + `withContext(Dispatchers.IO)`; `CancellationException` **re-throw**.
- **UTF-8 явно:** `Files.writeString(..., Charsets.UTF_8)` — фикс эмодзи/кириллицы на Windows.
- **Sealed Result:** `LlmResult<List<List<Float>>>` для embedding-вызовов (reuse из `llm/`).
- **DI-seam:** injectable `HttpClient` в `OllamaEmbeddingClient` → MockEngine в тестах без реальной Ollama.

## Чек-лист приёмки (соответствие заданию курса day-21)

- [x] «набор документов (README / статьи / код / pdf → текст)» — корпус: MD + `.kt` проекта
- [x] «минимум 20-30 страниц» — документация + исходники дают 30+ страниц
- [x] «разбиение на чанки (chunking)» — 2 стратегии в `rag/chunk/`
- [x] «генерация эмбеддингов» — `OllamaEmbeddingClient` (`nomic-embed-text`)
- [x] «сохранение индекса (FAISS / SQLite / JSON)» — JSON-индекс (`rag/index.json`)
- [x] «метаданные к каждому чанку (source, title/file, section, chunk_id)» — все 4 поля в `RagChunk`
- [x] «минимум 2 стратегии chunking + сравнение» — `ChunkingComparison`: статистика + retrieval
- [x] **0 регрессий** — новый пакет + опциональные команды; без Ollama агент работает как раньше
