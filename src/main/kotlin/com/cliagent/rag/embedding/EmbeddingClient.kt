package com.cliagent.rag.embedding

import com.cliagent.llm.LlmResult

/**
 * День 21: клиент эмбеддинг-модели. Лекция недели 5: эмбеддинг-модель превращает текст в вектор
 * (768/1024 чисел) для косинусного поиска семантически близких фрагментов.
 *
 * Возвращает [LlmResult] (sealed, как `LlmClient.chat`) — `Success<List<List<Float>>>` (по одному
 * вектору на входной текст, тот же порядок) либо `Error(code, message)`. Это позволяет вызывающему
 * слою (RagIndexer) отличить «Ollama не запущена» от «сеть отвалилась» и дать пользователю совет.
 *
 * Пока единственная реализация — [OllamaEmbeddingClient] (локально, бесплатно, приватно).
 * Interface готов к подключению облачного эмбеддера (OpenAI-compatible) без правок вызывающего кода.
 */
interface EmbeddingClient {
    /**
     * Эмбеддинг пакета текстов (batched). Длина каждого вектора = размерности модели
     * (768 для nomic-embed-text). Возвращает векторы **в том же порядке**, что и входные тексты.
     */
    suspend fun embed(texts: List<String>): LlmResult<List<List<Float>>>

    /** Имя модели (для [com.cliagent.rag.RagIndex.embeddingModel]). */
    val modelName: String

    /** Размерность вектора модели (для [com.cliagent.rag.RagIndex.dimension]). */
    val dimension: Int
}
