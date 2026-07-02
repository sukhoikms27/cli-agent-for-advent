package com.cliagent.rag

import kotlinx.serialization.Serializable

/**
 * RAG-модели дня 21 (индексация документов). Лекция недели 5: chunking → embeddings → индекс.
 *
 * Все `@Serializable` data classes имеют **defaults** на каждом поле (schema evolution, AGENTS.md):
 * старые/неполные индексы грузятся без ошибок. Векторы сериализуются как `List<Float>` —
 * kotlinx-serialization handles нативно.
 *
 * Метаданные задания day-21 (source, title/file, section, chunk_id) покрыты полями [RagChunk].
 */

/**
 * Один проиндексированный документ (файл корпуса). [content] — сырой текст; [id] стабилен между
 * запусками (хэш относительного пути), поэтому реиндексация идемпотентна.
 */
@Serializable
data class RagDocument(
    val id: String,
    val source: String,
    val title: String,
    val content: String,
    val fileType: String,
    val createdAt: Long = 0L
)

/**
 * Фрагмент документа для векторного поиска. Каждое поле-метаданное задания day-21 отмечено.
 *
 * @param chunkId  "{documentId}-{index}" ← **chunk_id**
 * @param documentId ссылка на [RagDocument.id]
 * @param source   путь файла            ← **source**
 * @param title    имя/H1 файла          ← **title/file**
 * @param section  заголовок секции MD / "fixed-{n}" ← **section**
 * @param text     собственно текст чанка (кормится в эмбеддер)
 * @param index    порядковый номер чанка в документе (0-based)
 * @param tokenCount оценка токенов (~4 char/token, как в llm/token/TokenCounter)
 * @param embedding вектор; null до генерации (persist при отсутствии экономит место)
 * @param strategy какая стратегия нарезала ("fixed" | "structural")
 */
@Serializable
data class RagChunk(
    val chunkId: String,
    val documentId: String,
    val source: String,
    val title: String,
    val section: String,
    val text: String,
    val index: Int,
    val tokenCount: Int = 0,
    val embedding: List<Float>? = null,
    val strategy: String = "structural"
)

/**
 * Локальный индекс: набор документов + все чанки (с эмбеддингами) для одной стратегии chunking.
 * Один JSON-файл на стратегию (см. [com.cliagent.config.AppPaths.ragIndexFixed]/Structural).
 *
 * @param strategy     "fixed" | "structural"
 * @param embeddingModel модель эмбеддера (напр. "nomic-embed-text") — для отладки/миграций
 * @param dimension    размерность вектора (768 для nomic-embed-text)
 * @param documents    документы корпуса
 * @param chunks       все чанки (плоский список); embedding может быть null, если генерация не запускалась
 * @param createdAt    epoch millis
 */
@Serializable
data class RagIndex(
    val strategy: String,
    val embeddingModel: String = "",
    val dimension: Int = 0,
    val documents: List<RagDocument> = emptyList(),
    val chunks: List<RagChunk> = emptyList(),
    val createdAt: Long = 0L
) {
    /** Чанки, у которых есть вектор — кандидаты для retrieval. */
    val embeddedChunks: List<RagChunk> get() = chunks.filter { it.embedding != null }
}

/**
 * Чанк + его косинусное сходство с запросом (результат [com.cliagent.rag.VectorMath.topK]).
 * `score ∈ [-1, 1]`: 1 — семантически идентичны, 0 — о разном, −1 — противоположны (лекция недели 5).
 */
data class ScoredChunk(
    val chunk: RagChunk,
    val score: Float
)

/**
 * Конфигурация RAG (часть [com.cliagent.config.AppConfig.rag]). Все поля с defaults — старые
 * config.json грузятся без ошибок; новые поля добавляются только с defaults (AGENTS.md).
 *
 * @param enabled          включён ли RAG (статус-строка, день 22 — инъекция в промпт)
 * @param embeddingProvider "ollama" (пока единственный; interface готов к облаку)
 * @param embeddingModel   "nomic-embed-text" (768 dim, из лекции недели 5)
 * @param embeddingBaseUrl "http://localhost:11434" — Ollama; на VPS заменить адрес
 * @param corpusRoots      относительные пути к корням корпуса (напр. ["docs", "plan", "src"])
 * @param chunkSizeTokens  целевой размер чанка (500–1000, рекомендация лекции)
 * @param chunkOverlapTokens перекрытие границ (overlap решает потерю контекста на стыках)
 * @param defaultStrategy  "fixed" | "structural"
 */
@Serializable
data class RagConfig(
    val enabled: Boolean = false,
    val embeddingProvider: String = "ollama",
    val embeddingModel: String = "nomic-embed-text",
    val embeddingBaseUrl: String = "http://localhost:11434",
    val corpusRoots: List<String> = listOf("plan", "docs", "README.md"),
    val chunkSizeTokens: Int = 500,
    val chunkOverlapTokens: Int = 100,
    val defaultStrategy: String = "structural"
)
