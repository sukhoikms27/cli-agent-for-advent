package com.cliagent.llm.model

data class BenchmarkResult(
    val modelId: String,
    val tier: ModelTier,
    val responseText: String,
    val responseTimeMs: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val estimatedCost: Double?
)
