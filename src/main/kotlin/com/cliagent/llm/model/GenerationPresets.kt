package com.cliagent.llm.model

/**
 * Пресеты генерации — объединяют формат ответа (SystemPrompts) и
 * стратегию рассуждения (PromptTemplates) в один выбор.
 * [ANDROID-DIFF] Android использует похожий подход в GenerationPresets.kt
 */
enum class GenerationPreset(val label: String) {
    STANDARD("standard"),     // SystemPrompts.default + DIRECT
    CONCISE("concise"),       // SystemPrompts.withMaxLength(100) + DIRECT + maxTokens=200
    PROMPTED("prompted"),     // META_PROMPT стратегия
    EXPERTS("experts");       // EXPERT_GROUP стратегия

    fun toSystemMessage(): ChatMessage = when (this) {
        STANDARD -> PromptTemplates.buildSystemMessage(ReasoningStrategy.DIRECT)
        CONCISE  -> SystemPrompts.withMaxLength(100)
        PROMPTED -> PromptTemplates.buildSystemMessage(ReasoningStrategy.META_PROMPT)
        EXPERTS  -> PromptTemplates.buildSystemMessage(ReasoningStrategy.EXPERT_GROUP)
    }

    fun toReasoningStrategy(): ReasoningStrategy = when (this) {
        STANDARD, CONCISE -> ReasoningStrategy.DIRECT
        PROMPTED          -> ReasoningStrategy.META_PROMPT
        EXPERTS           -> ReasoningStrategy.EXPERT_GROUP
    }

    fun toMaxTokens(): Int? = when (this) {
        CONCISE -> 200
        else -> null
    }

    fun toTemperature(): Double = when (this) {
        STANDARD, PROMPTED, EXPERTS -> 0.7
        CONCISE -> 0.3
    }
}
