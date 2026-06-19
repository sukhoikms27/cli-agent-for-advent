package com.cliagent.state.invariant

import kotlinx.serialization.Serializable

/**
 * Категория инварианта — группировка для отображения и приоритизации (день 14).
 *
 * Отражает классы инвариантов из задания курса day14: стек / запреты / архитектура / бизнес-правила.
 */
@Serializable
enum class InvariantCategory {
    STACK,      // технологический стек (Kotlin, Ktor, ViewBinding)
    BAN,        // запрещённые технологии (no Compose, no RxJava, no XML)
    ARCH,       // архитектурные решения (MVI, MVVM, Repository)
    BUSINESS    // бизнес-правила (free-API-only, бюджет, лимиты)
}

/**
 * Инвариант — жёсткое правило проекта, которое ассистент не имеет права нарушать (день 14,
 * третий столп недели 3). Хранится отдельно от диалога в
 * [com.cliagent.memory.LongTermMemory.invariants] (global, переживает restart).
 *
 * В отличие от [com.cliagent.memory.UserProfile.constraints] (персонализация, soft, только в
 * промпте), инвариант — hard-правило: проверяется программно через [InvariantChecker].
 *
 * @param id       стабильный идентификатор (для `/invariants remove` и ссылок в [InvariantResult.Violated])
 * @param rule     текст правила на естественном языке (кормит judge-промпт и system prompt)
 * @param category группа для отображения/фильтрации
 */
@Serializable
data class Invariant(
    val id: String,
    val rule: String,
    val category: InvariantCategory = InvariantCategory.BUSINESS
)
