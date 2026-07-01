package com.cliagent.agent.swarm

import com.cliagent.state.TaskKind
import com.cliagent.state.TaskStage

/**
 * Стратегия работы роя (V4 = lead+workers+integrate; V1/V2/V3 — стратегии workers/integrator).
 *
 * - [PARTITION]   (V1): lead дробит артефакт/работу на ≤N слайсов; каждый worker обрабатывает свой
 *                  слайс; integrator конкатенирует/reduce. Слайцы независимы → bounded-контекст на
 *                  worker → структурно убирает обрыв.
 * - [REDUNDANCY]  (V2): N workers независимо генерируют полный кандидат; integrator-judge выбирает
 *                  лучший/сливает. Мера качества, не решает обрыв сама по себе.
 * - [SPECIALISTS] (V3): lead назначает N ролей-граней; каждый worker со своей стороны; integrator
 *                  синтезирует находки.
 */
enum class SwarmStrategy { PARTITION, REDUNDANCY, SPECIALISTS }

/**
 * Спецификация роя для стадии: стратегия + число workers (≤5; на малых стадиях меньше — overhead
 * роя там превышает выгоду bounded-контекста).
 */
data class SwarmSpec(
    val strategy: SwarmStrategy,
    val maxWorkers: Int
) {
    companion object {
        /**
         * Дефолтные стратегии по стадиям.
         *
         * День 21 (волна W4.1): VALIDATION → [SwarmStrategy.REDUNDANCY] (3 workers) вместо PARTITION.
         * Валидация по природе целостная (работает ли всё вместе?), а PARTITION по слайсам пропускает
         * интеграционные дефекты. REDUNDANCY — независимые целостные проверки + integrator сливает
         * находки: ловит дефекты, которые слайсовая проверка пропускала.
         */
        fun specFor(stage: TaskStage): SwarmSpec = when (stage) {
            TaskStage.CLARIFY -> SwarmSpec(SwarmStrategy.SPECIALISTS, 3)   // грани неоднозначности
            TaskStage.PLANNING -> SwarmSpec(SwarmStrategy.PARTITION, 5)    // модули задачи
            TaskStage.EXECUTION -> SwarmSpec(SwarmStrategy.PARTITION, 5)   // группы шагов плана
            TaskStage.VALIDATION -> SwarmSpec(SwarmStrategy.REDUNDANCY, 3) // целостные проверки (W4.1)
            TaskStage.DONE -> SwarmSpec(SwarmStrategy.PARTITION, 3)        // аспекты итога
        }

        /**
         * День 21 (волна W4.2): стратегия EXECUTION зависит от [TaskKind].
         * - CODE → PARTITION (модули кода, интерфейсы между частями).
         * - REASONING → REDUNDANCY (независимые решения + integrator выбирает лучший).
         * - WRITING → REDUNDANCY (варианты текста + выбор).
         * - EXPLANATION → SPECIALISTS (грани темы).
         * - null → PARTITION (безопасный дефолт при неизвестном типе).
         *
         * Для остальных стадий [kind] игнорируется (используется [specFor] без kind).
         */
        fun specFor(stage: TaskStage, kind: TaskKind?): SwarmSpec {
            if (stage == TaskStage.EXECUTION && kind != null) {
                return when (kind) {
                    TaskKind.CODE -> SwarmSpec(SwarmStrategy.PARTITION, 5)
                    TaskKind.REASONING -> SwarmSpec(SwarmStrategy.REDUNDANCY, 3)
                    TaskKind.WRITING -> SwarmSpec(SwarmStrategy.REDUNDANCY, 3)
                    TaskKind.EXPLANATION -> SwarmSpec(SwarmStrategy.SPECIALISTS, 3)
                }
            }
            return specFor(stage)
        }
    }
}
