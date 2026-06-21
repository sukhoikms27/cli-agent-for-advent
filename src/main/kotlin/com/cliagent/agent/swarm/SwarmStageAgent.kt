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
        // 1. Lead: декомпозиция (PARTITION/SPECIALISTS). REDUNDANCY — реплицируем полную задачу.
        val subtasks: List<String> = when (spec.strategy) {
            SwarmStrategy.REDUNDANCY -> List(spec.maxWorkers) { ctx.taskDescription }
            else -> {
                val leadOut = chat(SwarmPrompts.leadPrompt(stage, spec, ctx))
                val parsed = PlanParser.parse(leadOut)
                if (parsed.isEmpty()) listOf(ctx.taskDescription) else parsed.take(spec.maxWorkers)
            }
        }

        // 2. Fan-out: workers параллельно. Отказ worker'а → стаб (стадия не валится).
        val workerOutputs: List<String> = coroutineScope {
            subtasks.mapIndexed { idx, sub ->
                async {
                    try {
                        chat(SwarmPrompts.workerPrompt(stage, spec.strategy, ctx, sub, idx + 1))
                    } catch (e: LlmCallException) {
                        "⚠️ Worker ${idx + 1} не смог завершить: ${e.message}"
                    }
                }
            }.awaitAll()
        }

        // 3. Integrate: единый финальный артефакт.
        val integrated = chat(SwarmPrompts.integratePrompt(stage, spec.strategy, ctx, workerOutputs))

        return StageResult(
            artifact = SwarmPrompts.artifactFor(stage, integrated),
            display = SwarmPrompts.display(stage, integrated),
            readyToAdvance = SwarmPrompts.readiness(stage, integrated)
        )
    }
}
