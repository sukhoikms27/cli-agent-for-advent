package com.cliagent.llm.model

enum class ModelTier {
    WEAK,    // Быстрая, дешёвая, ограниченная
    MEDIUM,  // Баланс скорости и качества
    STRONG   // Максимальное качество, дороже
}

data class ModelInfo(
    val id: String,
    val tier: ModelTier,
    val contextWindow: Int,
    val costPerMillionInput: Double?,
    val costPerMillionOutput: Double?
)
