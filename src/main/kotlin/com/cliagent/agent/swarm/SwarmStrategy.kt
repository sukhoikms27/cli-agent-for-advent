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
 * Спецификация роя для стадии: стратегия + число workers + таймаут worker'а (≤5; на малых стадиях
 * меньше — overhead роя там превышает выгоду bounded-контекста).
 *
 * [workerTimeoutMs] — максимальное время на один worker (default 90с). Для FILE_OP увеличен до 240с:
 * worker с tool-loop'ом (3-4 LLM-вызова × 30с на thinking-моделях = 90-120с) не должен таймаутить.
 */
data class SwarmSpec(
    val strategy: SwarmStrategy,
    val maxWorkers: Int,
    val workerTimeoutMs: Long = 90_000L,
) {
    companion object {
        /** Таймаут для file-задач (worker с tool-loop'ом = 3-4 LLM-вызова). */
        const val FILE_OP_WORKER_TIMEOUT_MS = 240_000L

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
         * - FILE_OP → PARTITION с увеличенным таймаутом (см. ниже).
         * - null → PARTITION (безопасный дефолт при неизвестном типе).
         *
         * Для остальных стадий [kind] игнорируется (используется [specFor] без kind).
         *
         * День 34: FILE_OP — PARTITION по **независимым файлам/директориям**. Lead сначала изучает
         * структуру проекта (shared-research через list_project_files), потом декомпозирует на
         * независимые подзадачи (по одному файлу/директории на worker). Таймаут увеличен до 240с —
         * worker с tool-loop'ом (read → analyze → write) требует 3-4 LLM-вызова, каждый ~30-60с
         * на thinking-моделях.
         */
        fun specFor(stage: TaskStage, kind: TaskKind?): SwarmSpec {
            if (stage == TaskStage.EXECUTION && kind != null) {
                return when (kind) {
                    TaskKind.CODE -> SwarmSpec(SwarmStrategy.PARTITION, 5)
                    TaskKind.REASONING -> SwarmSpec(SwarmStrategy.REDUNDANCY, 3)
                    TaskKind.WRITING -> SwarmSpec(SwarmStrategy.REDUNDANCY, 3)
                    TaskKind.EXPLANATION -> SwarmSpec(SwarmStrategy.SPECIALISTS, 3)
                    // День 34: PARTITION по независимым файлам/директориям + увеличенный таймаут.
                    TaskKind.FILE_OP -> SwarmSpec(
                        SwarmStrategy.PARTITION, 5, workerTimeoutMs = FILE_OP_WORKER_TIMEOUT_MS
                    )
                    TaskKind.PR_REVIEW -> SwarmSpec(SwarmStrategy.PARTITION, 2)
                }
            }
            return specFor(stage)
        }
    }
}
