# 03 — DocumentLoader (`rag/DocumentLoader.kt`)

Обход корпуса → `List<RagDocument>`. Задание: «README / статьи / код / pdf → текст».

- **Расширения:** `.md` (документация) и `.kt` (исходники как «эквивалент в коде»). pdf — за рамками
  (мультимодальный RAG/OCR по лекции).
- **`documentId`** = FNV-1a хэш относительного пути (`doc-<hex>`) → **стабилен между запусками** →
  реиндексация идемпотентна: тот же путь = тот же id = те же chunkId.
- **`title`** = первый H1 (`# …`) MD-файла, иначе имя файла.
- **Skip мусора:** директории `build/`, `.gradle/`, `target/`, `node_modules/`; файлы > 1.5 MB; пустые.
- **I/O:** `suspend` + `withContext(Dispatchers.IO)`; несуществующие корни skip. Порядок детерминирован
  (sort by source) — важно для стабильного сравнения стратегий.
- **UTF-8 явно** при чтении (`Files.readString(file, Charsets.UTF_8)`) — фикс кириллицы/эмодзи на Windows.
