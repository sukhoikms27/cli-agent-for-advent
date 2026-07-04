package com.cliagent.rag

import com.cliagent.llm.LlmClient
import com.cliagent.rag.rerank.HeuristicReranker
import com.cliagent.rag.rerank.LlmReranker
import com.cliagent.rag.rerank.Reranker
import com.cliagent.rag.rerank.RerankerType
import com.cliagent.rag.rerank.ThresholdReranker
import com.cliagent.rag.rewrite.HeuristicQueryRewriter
import com.cliagent.rag.rewrite.IdentityQueryRewriter
import com.cliagent.rag.rewrite.LlmQueryRewriter
import com.cliagent.rag.rewrite.QueryRewriter
import com.cliagent.rag.rewrite.QueryRewriterType

/**
 * День 23: фабрики компонентов реранкинга/фильтрации. Централизованный маппинг
 * [RagConfig] (строковые типы) → инстансы [QueryRewriter]/[Reranker].
 *
 * Одно место используется и статикой (ChatCommand — сборка при старте из config), и рантаймом
 * (RagCommands — `/rag rerank <type>`, `/rag rewrite <type>` toggle без перезапуска). LLM-варианты
 * переиспользуют уже существующий [client] (z.ai GLM) — НЕ создают отдельный embedder/HTTP.
 *
 * `llmClient = null` (нет MCP/LLM недоступен) → LLM-типы деградируют до identity/none (без throw),
 * сохраняя мягкую деградацию RAG (AGENTS.md: RAG опционален).
 */

/**
 * Собирает [QueryRewriter] по типу.
 * @param type строка из конфига/CLI ("identity" | "heuristic" | "llm")
 * @param llmClient LLM для LLM-режима; null → LLM деградирует до identity
 * @param model имя модели для LLM-режима
 */
fun queryRewriterOf(type: String, llmClient: LlmClient?, model: String): QueryRewriter =
    when (QueryRewriterType.fromString(type)) {
        QueryRewriterType.IDENTITY -> IdentityQueryRewriter
        QueryRewriterType.HEURISTIC -> HeuristicQueryRewriter()
        QueryRewriterType.LLM ->
            if (llmClient != null) LlmQueryRewriter(llmClient, model) else IdentityQueryRewriter
    }

/**
 * Собирает [Reranker] по типу. null = без 2-го этапа (поведение дня 22).
 * @param type строка из конфига/CLI ("none" | "threshold" | "heuristic" | "llm")
 * @param config конфиг — берёт [RagConfig.similarityThreshold] для threshold-режима
 * @param llmClient LLM для LLM-режима; null → LLM деградирует до null (без реранка)
 * @param model имя модели для LLM-режима
 */
fun rerankerOf(type: String, config: RagConfig, llmClient: LlmClient?, model: String): Reranker? =
    when (RerankerType.fromString(type)) {
        RerankerType.NONE -> null
        RerankerType.THRESHOLD -> ThresholdReranker(config.similarityThreshold)
        RerankerType.HEURISTIC -> HeuristicReranker()
        RerankerType.LLM ->
            if (llmClient != null) LlmReranker(llmClient, model) else null
    }
