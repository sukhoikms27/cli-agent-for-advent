package com.cliagent.llm.model

/**
 * Стратегия рассуждения — определяет, как формулировать промпт
 * для решения задачи. Каждая стратегия модифицирует system message
 * и (опционально) структуру запроса.
 */
enum class ReasoningStrategy(val label: String) {
    DIRECT("direct"),             // Прямой ответ без инструкций
    STEP_BY_STEP("step_by_step"), // «Решай пошагово»
    META_PROMPT("meta_prompt"),   // Сначала составь промпт, потом реши
    EXPERT_GROUP("expert_group")  // Группа экспертов
}
