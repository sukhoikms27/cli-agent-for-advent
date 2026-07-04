package com.cliagent.agent

import com.cliagent.llm.model.ChatMessage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import com.cliagent.rag.ScoredChunk

/**
 * Сборка слоёного system prompt (день 11 — prompt builder из лекции недели 3).
 *
 * Компонует базовый system-промпт с блоками долговременной и рабочей памяти.
 * Пустые слои элизируются → при отсутствии памяти контент system-сообщения
 * байт-идентичен дням 1–10 (поведение не меняется).
 *
 * Порядок блоков: base → long-term → working → retrieved → invariants
 * (долговременный контекст «весомее», рабочая задача — ближе к запросу; retrieved-контекст RAG —
 * между working и invariants: задача задаёт фрейм, чанки дают факты, инварианты — последними
 * для recency; день 22).
 *
 * Точки расширения:
 *  - Day 12: [LongTermMemory.profile] рендерится автоматически (см. [UserProfile.renderBlock]).
 *  - Day 13: [WorkingMemory.taskState] рендерится в [WorkingMemory.renderBlock] (блок Task state).
 *  - Day 22: [retrievedContext] рендерится в [renderRetrievedBlock] (блок `[Retrieved context]`).
 *  - Day 24: инструкция блока усилена — обязательные источники + цитаты + «не знаю» (анти-галлюцинации).
 */
class PromptBuilder(
    private val baseSystem: ChatMessage,
    private val longTerm: LongTermMemory?,
    private val working: WorkingMemory?,
    private val retrievedContext: List<ScoredChunk>? = null,
) {
    fun build(): ChatMessage {
        val parts = mutableListOf(baseSystem.content)
        longTerm?.takeIf { !it.isEmpty() }?.let { parts.add(it.renderBlock()) }
        working?.takeIf { !it.isEmpty() }?.let { parts.add(it.renderBlock()) }
        retrievedContext?.takeIf { it.isNotEmpty() }?.let { parts.add(it.renderRetrievedBlock()) } // день 22
        longTerm?.renderInvariantsBlock()?.let { parts.add(it) }   // день 14: блок инвариантов
        // Все слои пусты  parts == [baseSystem.content]  контент неизменен
        return baseSystem.copy(content = parts.joinToString("\n\n"))
    }
}

/** Секция долговременной памяти: knowledge, decisions, profile, invariants. */
internal fun LongTermMemory.renderBlock(): String {
    val lines = mutableListOf<String>()
    lines.add("[Long-term memory]")
    if (knowledge.isNotEmpty()) {
        lines.add("Knowledge:")
        knowledge.forEach { (k, v) -> lines.add("  - $k: $v") }
    }
    if (decisions.isNotEmpty()) {
        lines.add("Decisions:")
        decisions.forEach { (k, v) -> lines.add("  - $k: $v") }
    }
    profile?.takeIf { !it.isEmpty() }?.let { lines.add(it.renderBlock()) }
    return lines.joinToString("\n")
}

/**
 * Секция инвариантов проекта (день 14): жёсткие правила, которые ассистент не имеет права
 * нарушать (defense-in-depth слой 1 — в промпте; слой 2 — программная проверка через
 * [com.cliagent.agent.InvariantGuard]). Рендерится отдельно от секции long-term memory, чтобы
 * акцентировать внимание модели (заголовок «MUST NOT violate»).
 *
 * Порядок блоков в system prompt: base → long-term → working → project invariants
 * (инварианты последними — recency для модели).
 */
internal fun LongTermMemory.renderInvariantsBlock(): String? =
    invariants.takeIf { it.isNotEmpty() }?.let { list ->
        buildString {
            append("[Project invariants — you MUST NOT propose solutions that violate these]")
            list.forEach { iv ->
                append("\n  - [${iv.id}] ${iv.rule}  (${iv.category.name.lowercase()})")
            }
        }
    }

/** Секция рабочей памяти: данные текущей задачи. */
internal fun WorkingMemory.renderBlock(): String {
    val lines = mutableListOf<String>()
    lines.add("[Working memory — current task]")
    currentTask?.let { lines.add("Task: $it") }
    plan?.let { lines.add("Plan: $it") }
    scratchNotes?.let { lines.add("Notes: $it") }
    if (taskDecisions.isNotEmpty()) {
        lines.add("Decisions:")
        taskDecisions.forEach { lines.add("  - $it") }
    }
    taskState?.let { ts ->
        lines.add("Task state:")
        lines.add("  Stage: ${ts.stage.name.lowercase()}")
        ts.currentStep?.let { lines.add("  Current step: $it") }
        ts.expectedAction?.let { lines.add("  Expected action: $it") }
        ts.approvedPlan?.let { lines.add("  Approved plan: $it") }
        ts.implementation?.let { lines.add("  Implementation: $it") }
        ts.verdict?.let { lines.add("  Verdict: $it") }
    }
    return lines.joinToString("\n")
}

/** Секция профиля пользователя (Day 12 наполняет, Day 11 уже рендерит). */
internal fun UserProfile.renderBlock(): String {
    val lines = mutableListOf<String>()
    lines.add("User profile:")
    style?.let { lines.add("  Style: $it") }
    format?.let { lines.add("  Format: $it") }
    about?.let { lines.add("  About: $it") }
    if (constraints.isNotEmpty()) {
        lines.add("  Constraints:")
        constraints.forEach { lines.add("    - $it") }
    }
    return lines.joinToString("\n")
}

/**
 * Секция retrieved-контекста RAG (день 22 → усилено день 24): топ-K чанков, найденных по запросу в
 * индексе корпуса. Лекция недели 5: чанки **комбинируются с промптом** — модель отвечает из них, а не
 * из общей тренировочной базы (анти-галлюцинации).
 *
 * **День 24** (цитаты, источники, анти-галлюцинации): инструкция усилена — модель ОБЯЗАНА вернуть
 * структурированный ответ (Ответ / Источники / Цитаты) и сказать «не знаю», если чанки не содержат
 * ответа. Метаданные `source › section (chunk_id, score)` кладутся явно для цитирования.
 * Пост-чек выполнения инструкции — в [com.cliagent.agent.ContextAwareAgent.finalizeAssistant]
 * через [com.cliagent.rag.CitationDetector] (warning only, не re-prompt).
 */
internal fun List<ScoredChunk>.renderRetrievedBlock(): String {
    val lines = mutableListOf<String>()
    lines.add("[Retrieved context — ответь СТРОГО по этим источникам, не выдумывай. Формат ответа:]")
    lines.add("  1) Ответ: суть по чанкам своими словами")
    lines.add("  2) Источники: перечисли каждый использованный source › section (chunk_id)")
    lines.add("  3) Цитаты: дословные фрагменты из чанков в кавычках «...» с указанием источника")
    lines.add("  Если чанки не содержат ответа на вопрос — так и скажи: «не знаю, уточните вопрос».")
    lines.add("Найденные чанки:")
    forEachIndexed { i, sc ->
        val chunk = sc.chunk
        lines.add("${i + 1}. ${chunk.text}")
        lines.add("   — Source: ${chunk.source} › ${chunk.section} (${chunk.chunkId}, score ${String.format("%.3f", sc.score)})")
    }
    return lines.joinToString("\n")
}
