# 05 — Embeddings (`rag/embedding/`)

## interface
```kotlin
interface EmbeddingClient {
    suspend fun embed(texts: List<String>): LlmResult<List<List<Float>>>  // batched, тот же порядок
    val modelName: String
    val dimension: Int
}
```
Возвращает `LlmResult` (sealed, как `LlmClient.chat`) — вызывающий отличает «Ollama не запущена»
от «сеть». Interface готов к облачному эмбеддеру без правок вызывающего кода.

## OllamaEmbeddingClient
- **Эндпоинт:** `POST {baseUrl}/api/embed` body `{"model":"nomic-embed-text","input":[...]}` →
  `{"embeddings":[[768 floats],...]}`. (Актуальный batched-эндпоинт; устаревший `/api/embeddings`
  singular deprecated.)
- **Batching:** `chunked(batchSize=32)` — не слать сотни текстов одним запросом.
- **Retry** (по образцу `OpenAiCompatibleClient`): до 5 попыток, экспоненциальный backoff (cap 16s),
  ретрай только транзиентных (429/5xx/0). Митигация известного бага nomic-embed-text (Ollama #12585,
  ~50% fail). `CancellationException` re-throw.
- **Недоступна Ollama** → `LlmResult.Error(69, "Ollama недоступна на {url}. Запустите: ollama serve && ollama pull …")`.
- **DI-шов:** injectable `HttpClient` (default CIO); в тестах `HttpClient(MockEngine{…})` — без реальной Ollama.
- **dimension** по имени модели (nomic→768, mxbai→1024, minilm→384, bge-m3→1024).
