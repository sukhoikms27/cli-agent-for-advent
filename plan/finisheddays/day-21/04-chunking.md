# 04 — Chunking (`rag/chunk/`)

## interface + enum
```kotlin
interface ChunkingStrategy { val name: String; fun chunk(doc: RagDocument): List<RagChunk> }
enum class ChunkingStrategyType { FIXED, STRUCTURAL }   // fromString() парсер
```

## FixedSizeChunker (метод 1 лекции — «фиксированный размер»)
Нарезка по токенам с **overlap** (`~4 char/token`):
- `chunkChars = chunkSizeTokens × 4`, `overlapChars = chunkOverlapTokens × 4`, `step = chunkChars − overlapChars`.
- Окно: `[start, start+chunkChars)` → чанк; `start += step` → следующий. Пример лекции: 1-500, 451-950.
- Минус (лекция): можно разрезать посередине предложения; overlap смягчает.
- `section = "fixed-{index}"`.

## StructuralChunker (метод 2 лекции — «семантический, по структуре»)
Сплит по MD-заголовкам `^#{1,6}\s`:
1. Документ → секции (заголовок + тело до следующего заголовка). Текст до первого H1 → "(intro)".
2. Секция ≤ `maxChunkTokens` → один чанк, `section` = текст заголовка.
3. Секция > `maxChunkTokens` → под-нарезка окном с overlap (как fixed).
- Для `.kt` (не MD) — одна секция "(file)" с под-нарезкой.
- Точнее fixed: сохраняет контекст секции (заголовок входит в `text` → кормится в эмбеддер).

Оба проставляют метаданные: `chunkId = "{docId}-{index}"`, `source`, `title`, `section`, `strategy`.
