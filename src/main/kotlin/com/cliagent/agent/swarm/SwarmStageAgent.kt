package com.cliagent.agent.swarm

import com.cliagent.agent.stage.PlanParser
import com.cliagent.agent.stage.StageAgent
import com.cliagent.agent.stage.StageContext
import com.cliagent.agent.stage.StageResult
import com.cliagent.llm.LlmCallException
import com.cliagent.state.TaskStage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** W6.1: таймаут на один worker-вызов (90с). Зависший worker не вешает стадию. */
private const val WORKER_TIMEOUT_MS = 90_000L

/**
 * Рой-агент стадии (V4: lead → ≤N workers → integrate), гибрид с V1/V2/V3 через [SwarmStrategy].
 *
 * Lead дробит работу (PARTITION/SPECIALISTS) или движок реплицирует задачу (REDUNDANCY); workers
 * исполняют параллельно (корутины, ≤[SwarmSpec.maxWorkers]); integrator собирает финальный артефакт.
 * Каждый worker-выов имеет bounded-контекст (мера C) → структурно убирает обрыв длинных артефактов.
 *
 * Все LLM-вызовы идут через единый `chat`-делегат оркестратора → покрыты max_tokens (A),
 * детекцией `finish_reason=length` + auto-continue (B), усечением history (D1).
 *
 * Отказ одного worker'а не валит стадию: ловим [LlmCallException] per-worker → стаб-заглушка,
 * integrator работает с тем, что есть. Lead/integrator отказ → проброс (поймает [com.cliagent.agent.stage.TaskOrchestrator.runOneStage]).
 *
 * Не [StageAgent] по простой реализации — это альтернативная реализация [StageAgent] для роя.
 */
class SwarmStageAgent(
    override val stage: TaskStage,
    private val spec: SwarmSpec = SwarmSpec.specFor(stage)
) : StageAgent {

    override suspend fun run(ctx: StageContext, chat: suspend (String) -> String): StageResult {
        // День 21 (W4.2): стратегия EXECUTION зависит от taskKind. Если ctx.taskKind задан и отличается
        // от дефолтного spec'а — пересобираем spec. Для остальных стадий kind игнорируется.
        val effectiveSpec = if (stage == TaskStage.EXECUTION && ctx.taskKind != null) {
            SwarmSpec.specFor(stage, ctx.taskKind)
        } else {
            spec
        }
        // 1. Lead: декомпозиция (PARTITION/SPECIALISTS). REDUNDANCY — реплицируем полную задачу.
        val subtasks: List<String> = when (effectiveSpec.strategy) {
            SwarmStrategy.REDUNDANCY -> List(effectiveSpec.maxWorkers) { ctx.taskDescription }
            else -> {
                val leadOut = chat(SwarmPrompts.leadPrompt(stage, effectiveSpec, ctx))
                val parsed = PlanParser.parse(leadOut)
                if (parsed.isEmpty()) listOf(ctx.taskDescription) else parsed.take(effectiveSpec.maxWorkers)
            }
        }

        // 2. Fan-out: workers параллельно. Отказ worker'а → стаб (стадия не валится).
        // W5.1: shared pre-fetch — единый research-вызов собирает общие данные (через tools) перед
        // fan-out, чтобы workers не дублировали одни и те же tool-вызовы. Гейт: только EXECUTION+COMPLEX
        // (где tool-calls вероятны и overhead research окупается). MODERATE/TRIVIAL — пропускаем.
        val sharedResearch: String? = if (stage == TaskStage.EXECUTION &&
            ctx.complexity == com.cliagent.state.TaskComplexity.COMPLEX
        ) {
            try {
                val out = chat(SwarmPrompts.researchPrompt(ctx))
                out.takeIf { it.isNotBlank() }
            } catch (e: LlmCallException) {
                null   // research упал — workers будут работать без shared-данных (graceful)
            }
        } else null

        val workerOutputs: List<String> = coroutineScope {
            subtasks.mapIndexed { idx, sub ->
                async {
                    // W6.1: withTimeout на каждый worker — зависший worker не вешает стадию.
                    // Таймаут → stub (как при LlmCallException); остальные workers продолжают.
                    try {
                        withTimeoutOrNull(WORKER_TIMEOUT_MS) {
                            chat(SwarmPrompts.workerPrompt(stage, effectiveSpec.strategy, ctx, sub, idx + 1, sharedResearch))
                        } ?: "⚠️ Worker ${idx + 1} превысил таймаут (${WORKER_TIMEOUT_MS}мс)."
                    } catch (e: LlmCallException) {
                        "⚠️ Worker ${idx + 1} не смог завершить: ${e.message}"
                    }
                }
            }.awaitAll()
        }

        // 3. Integrate: единый финальный артефакт.
        // W6.2: fallback integrator'а — если chat(integrate) бросает/таймаутит, не валить стадию,
        // а отдать degraded-артефакт (конкатенация worker-выводов с заголовками). Качество ниже, но
        // стадия завершается, и пользователь может доработать/перейти дальше.
        val integrated: String = try {
            chat(SwarmPrompts.integratePrompt(stage, effectiveSpec.strategy, ctx, workerOutputs))
        } catch (e: LlmCallException) {
            "⚠️ Integrator не смог завершить (${e.message}); показаны результаты worker'ов:\n\n" +
                workerOutputs.mapIndexed { i, o -> "=== Worker ${i + 1} ===\n$o" }.joinToString("\n\n")
        }

        return StageResult(
            artifact = SwarmPrompts.artifactFor(stage, integrated),
            display = SwarmPrompts.display(stage, integrated),
            readyToAdvance = SwarmPrompts.readiness(stage, integrated)
        )
    }
}
