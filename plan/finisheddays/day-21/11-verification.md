# 11 — Верификация

## Сборка и тесты
```
./gradlew build          # BUILD SUCCESSFUL
./gradlew :test --tests "com.cliagent.rag.*"   # 49 tests, 0 failures
```
Полный набор: 398+ тестов (существующие + 49 новых RAG), 0 регрессий.

## Чек-лист приёмки (соответствие заданию day-21)
- [x] набор документов (README/статьи/код/pdf → текст) — `DocumentLoader` (.md + .kt)
- [x] минимум 20-30 страниц — документация проекта (plan/, AGENTS.md, README.md) + исходники
- [x] разбиение на чанки — `Fixed/StructuralChunker`
- [x] генерация эмбеддингов — `OllamaEmbeddingClient` (`nomic-embed-text`, 768 dim, `/api/embed`)
- [x] сохранение индекса (FAISS/SQLite/JSON) — `JsonRagStore` (JSON, atomicWrite)
- [x] метаданные (source, title/file, section, chunk_id) — все 4 поля в `RagChunk`
- [x] минимум 2 стратегии + сравнение — `ChunkingComparison`: статистика + probe retrieval + `/rag compare`
- [x] **0 регрессий** — новый пакет + 2 поля config (defaults) + новые slash-команды; существующий flow идентичен

## Конвенции (AGENTS.md, соблюдены)
- Persistence: JSON + atomicWrite (temp + ATOMIC_MOVE), XDG-пути.
- Schema evolution: `@Serializable` с defaults.
- Coroutines: `suspend` + `Dispatchers.IO`; `CancellationException` re-throw.
- UTF-8 явно (Windows-фикс).
- Sealed Result (`LlmResult`).
- DI-швы: injectable HttpClient → MockEngine в тестах.

## Границы (что НЕ делали — день 22)
- Инъекция `[Retrieved context]` в `PromptBuilder`/`buildMessagesToSend`.
- Режимы ответа с/без RAG, 10 контрольных вопросов.
- Реранкинг (CrossEncoder).

## Ручной smoke (требует локальной Ollama)
```bash
ollama serve
ollama pull nomic-embed-text
./gradlew run --args="chat"
# в REPL:
/rag config
/rag index structural
/rag stats
/rag compare
/rag search "Как работает MCP-оркестрация?"
# Проверить: ~/.local/share/cli-agent/rag/index-structural.json содержит embeddings
```
