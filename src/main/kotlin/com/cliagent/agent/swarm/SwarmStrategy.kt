package com.cliagent.agent.swarm

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
        /** Дефолтные стратегии по стадиям (см. план, Часть 3). */
        fun specFor(stage: TaskStage): SwarmSpec = when (stage) {
            TaskStage.CLARIFY -> SwarmSpec(SwarmStrategy.SPECIALISTS, 3)   // грани неоднозначности
            TaskStage.PLANNING -> SwarmSpec(SwarmStrategy.PARTITION, 5)    // модули задачи
            TaskStage.EXECUTION -> SwarmSpec(SwarmStrategy.PARTITION, 5)   // группы шагов плана
            TaskStage.VALIDATION -> SwarmSpec(SwarmStrategy.PARTITION, 5)  // слайсы implementation
            TaskStage.DONE -> SwarmSpec(SwarmStrategy.PARTITION, 3)        // аспекты итога
        }
    }
}
