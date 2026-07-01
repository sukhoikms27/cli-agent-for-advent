package com.cliagent.agent.swarm

/**
 * Режим работы роя (день 21, волна W1). Управляет тем, какие стадии выполняются через
 * `SwarmStageAgent` (lead→workers→integrate), а какие — single-pass агентами.
 *
 * - [OFF]  — рой выключен полностью; все стадии — простые последовательные агенты (бывший `--no-swarm`).
 * - [ON]   — рой на ВСЕХ стадиях (бывший дефолт). Для дебага/сравнения; расточителен по токенам.
 * - [AUTO] — **новый дефолт**. Адаптивный гейт: рой включается только там, где он окупается.
 *   На CLARIFY/DONE — простые агенты (фан-аут на суммаризации/уточнении неоправдан). На
 *   PLANNING/EXECUTION/VALIDATION — рой. Плюс учитывается complexity задачи (W2): TRIVIAL →
 *   весь пайплайн single-agent.
 *
 * Выбор режима — через CLI `--swarm-mode off|on|auto` (вместо `--no-swarm`). `AUTO` по умолчанию.
 */
enum class SwarmMode {
    OFF,
    ON,
    AUTO;

    val label: String get() = name.lowercase()

    companion object {
        /** Парсинг строкового значения CLI/env в [SwarmMode]. Unknown → [AUTO] (безопасный дефолт). */
        fun fromString(value: String?): SwarmMode =
            entries.firstOrNull { it.label.equals(value?.trim(), ignoreCase = true) } ?: AUTO
    }
}
