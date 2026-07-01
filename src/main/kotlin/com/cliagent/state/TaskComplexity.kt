package com.cliagent.state

/**
 * Сложность задачи (день 21, волна W2). Определяется [com.cliagent.agent.stage.TaskComplexityClassifier]
 * по описанию задачи и управляет тем, где рой окупается (см. [com.cliagent.agent.stage.TaskOrchestrator.defaultAgents]).
 *
 * - [TRIVIAL]  — 1 атомарное действие, короткое описание («объясни X», «переведи», фактология).
 *   Весь пайплайн — single-agent (рой не окупается).
 * - [MODERATE] — 2–4 шага, одна область. Рой на PLANNING/EXECUTION/VALIDATION.
 * - [COMPLEX]  — многошаговая, кросс-доменная, длинный артефакт. Полный рой (где AUTO-гейт разрешает).
 */
enum class TaskComplexity { TRIVIAL, MODERATE, COMPLEX }