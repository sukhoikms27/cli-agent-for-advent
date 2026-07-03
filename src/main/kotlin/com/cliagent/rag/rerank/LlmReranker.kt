package com.cliagent.rag.rerank

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.rag.ScoredChunk

/**
 * День 23: LLM-as-judge реранкер. Лекция недели 5: CrossEncoder-модели переоценивают каждый чанк
 * относительно запроса (медленно, секунды). Здесь роль CrossEncoder'а выполняет генеративная LLM
 * (z.ai GLM): один batch-запрос со всеми кандидатами → LLM выставляет релевантность 0–10 каждому
 * → пересортировка по LLM-score.
 *
 * **Batching (важно):** все кандидаты в ОДИН prompt, а не по запросу на чанк (иначе N LLM-вызовов
 * на retrieval — недопустимо дорого). LLM возвращает JSON `{chunkId: score}`; tolerant-парсинг
 * (regex-fallback) переживает markdown-fences, trailing commas, обрывы.
 *
 * **Мягкая деградация (как день 22):** `LlmResult.Error` / unparseable → вернуть `candidates` в
 * исходном порядке (не валить retrieval). `CancellationException` re-throw (AGENTS.md).
 *
 * Tie-break: равные LLM-score → исходный cosine-порядок (stable sort сохраняет порядок равных).
 *
 * @param client LLM-клиент (тот же z.ai, что и основная генерация)
 * @param model  имя модели (напр. "glm-5.1")
 */
class LlmReranker(
    private val client: LlmClient,
    private val model: String,
) : Reranker {
    override val name: String = "llm"

    override suspend fun rerank(query: String, candidates: List<ScoredChunk>): List<ScoredChunk> {
        if (candidates.isEmpty()) return candidates
        // Запрос строим с превью каждого чанка (id + укороченный текст) — полный текст раздуло бы prompt.
        val prompt = buildJudgePrompt(query, candidates)
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = prompt),
            ),
            temperature = 0.0, // детерминированная оценка, без креатива
            maxTokens = 512,  // JSON с N оценок укладывается с запасом
        )
        return when (val result = client.chat(request)) {
            is LlmResult.Error -> candidates // мягкая деградация: исходный порядок
            is LlmResult.Success -> {
                val content = result.data.choices.firstOrNull()?.message?.content
                val scores = content?.let { parseJudgeScores(it) } ?: return candidates
                if (scores.isEmpty()) return candidates
                // stable sort: равные LLM-score сохраняют исходный cosine-порядок (tie-break).
                candidates.sortedByDescending { sc -> scores[sc.chunk.chunkId] ?: 0f }
            }
        }
    }

    private fun buildJudgePrompt(query: String, candidates: List<ScoredChunk>): String = buildString {
        appendLine("Запрос: $query")
        appendLine()
        appendLine("Кандидаты (chunkId : превью текста):")
        candidates.forEachIndexed { _, sc ->
            val preview = sc.chunk.text.replace('\n', ' ').take(240)
            appendLine("${sc.chunk.chunkId}: $preview")
        }
        appendLine()
        appendLine("Оцени релевантность каждого кандидата запросу числом от 0 до 10.")
        appendLine("Верни только JSON: {\"chunkId1\": score, \"chunkId2\": score, ...}")
    }

    /**
     * Tolerant-парсинг оценок LLM. Сначала пробуем найти JSON-объект (regex → parseToJsonElement
     * → JsonObject.entries); если не вышло — regex-fallback построчно: `"chunkId": 7` или `chunkId 7`.
     * @return Map<chunkId, score> или пустой Map, если ничего не распарсилось.
     */
    internal fun parseJudgeScores(content: String): Map<String, Float> {
        // 1) Попытка вытащить JSON-объект {...}
        val jsonRegex = Regex("\\{[^{}]*\\}", RegexOption.DOT_MATCHES_ALL)
        val jsonObject = jsonRegex.find(content)?.value
        if (jsonObject != null) {
            val parsed = runCatching {
                (JSON.parseToJsonElement(jsonObject) as? kotlinx.serialization.json.JsonObject)
            }.getOrNull()
            if (parsed != null) {
                val scores = parsed.entries.mapNotNull { (k, v) ->
                    val score = v.toString().trim('"').toFloatOrNull() ?: return@mapNotNull null
                    k to score
                }.toMap()
                if (scores.isNotEmpty()) return scores
            }
        }
        // 2) Regex-fallback: "d1-0": 8 OR d1-0: 8 OR d1-0 8 (по строкам)
        val lineRegex = Regex("""["']?([a-z0-9\-_]+)["']?\s*[:=]\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
        return lineRegex.findAll(content)
            .map { it.groupValues[1] to it.groupValues[2].toFloatOrNull() }
            .filter { it.second != null }
            .associate { it.first to it.second!! }
            .takeIf { it.isNotEmpty() }
            ?: emptyMap()
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "Ты — реранкер для RAG-системы. Тебе дают поисковый запрос и набор текстовых кандидатов " +
                "(результат векторного поиска). Оцени, насколько каждый кандидат ревелантен запросу " +
                "(10 — точно отвечает, 0 — нерелевантен). Верни оценки в формате JSON-объекта: " +
                "ключ — chunkId, значение — число от 0 до 10. Без пояснений, только JSON."

        /** Единый Json-инстанс для tolerant-парсинга (AGENTS.md — не создавать на каждый вызов). */
        private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
