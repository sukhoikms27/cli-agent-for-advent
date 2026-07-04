package com.cliagent.rag.rewrite

/**
 * День 23: query rewrite — переформулирование запроса **до** эмбеддинга. Лекция недели 5 упоминает
 * реранкинг и улучшение retrieval; query rewrite — симметричный приём на стороне запроса:
 * синонимы, расширение аббревиатур, нормализация стоп-слов повышают полноту векторного поиска.
 *
 * Контракт: [rewrite] возвращает строку для эмбеддинга. **Мягкая деградация:** при невозможности
 * переформулировать (нет модели, ошибка сети) реализация обязана вернуть исходный запрос —
 * retrieval не должен падать (как день 22: ошибка не валит агент).
 *
 * `suspend` — LLM-реализации ходят в сеть; чистые (heuristic) — не suspend по факту, но
 * интерфейс унифицирован для прозрачной подмены в [com.cliagent.rag.RagRetriever].
 */
interface QueryRewriter {
    /** Имя режима для статус-вывода и конфига ("identity" | "heuristic" | "llm"). */
    val name: String

    /** @return переформулированный запрос (никогда не null; при ошибке — исходный) */
    suspend fun rewrite(query: String): String
}

/**
 * Тривиальный rewriter: возвращает запрос как есть. Дефолт — воспроизводит поведение дней 21–22
 * (байт-идентичный эмбеддинг запроса). Используется при [QueryRewriterType.IDENTITY] и как
 * fallback при сбое LLM/heuristic.
 */
object IdentityQueryRewriter : QueryRewriter {
    override val name: String = "identity"
    override suspend fun rewrite(query: String): String = query
}

/**
 * Тип query rewrite (поле [com.cliagent.rag.RagConfig.queryRewriter]).
 * Алиасы tolerant к регистру/написанию (как [com.cliagent.rag.chunk.ChunkingStrategyType]).
 */
enum class QueryRewriterType {
    IDENTITY,
    HEURISTIC,
    LLM;

    companion object {
        /** @param s строка из конфига/CLI; null/unknown → [IDENTITY] (безопасный дефолт = день 22). */
        fun fromString(s: String?): QueryRewriterType {
            val key = s?.trim()?.lowercase() ?: return IDENTITY
            return when {
                key.isEmpty() -> IDENTITY
                key.startsWith("ident") || key == "none" || key == "off" || key == "no" -> IDENTITY
                key.startsWith("heuristic") || key == "heu" || key == "stopwords" -> HEURISTIC
                key == "llm" || key == "ai" || key == "model" || key == "glm" -> LLM
                else -> IDENTITY
            }
        }
    }
}
