package com.cliagent.agent.stage

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.llm.LlmClient
import com.cliagent.memory.UserProfile
import com.cliagent.state.InteractionMode
import com.cliagent.state.TaskStage
import com.cliagent.state.TaskState
import com.cliagent.state.TaskStateMachine

/**
 * Координатор автоматического стадийного потока (доработка Day 13).
 *
 * Реализует request #1: `/task start` → агент сам выбирает первую стадию ([EntryStageClassifier]),
 * генерирует её артефакт через соответствующий [StageAgent] (LLM-вызовы делегируются в
 * [ContextAwareAgent.chat], который уже подставляет stage system-промпт) и просит подтверждения
 * перехода. Пользователь только подтверждает; любой иной текст = уточнение артефакта (feedback).
 *
 * Реализует request #2 (гибрид): на каждую стадию FSM — отдельный [StageAgent] (реестр [agents]);
 * внутри execution — по [StepAgent] на каждый пункт плана (см. [ExecutionStageAgent]).
 *
 * Ручные `/task next|set|...` остаются как escape hatch — оркестратор их не дублирует.
 *
 * Поток:
 * ```
 * /task start <desc>  → startTask(desc)
 *   classifier → CLARIFY | PLANNING
 *   runStage → артефакт + awaitingAdvance
 *   display + "Перейти к <next>? (да — продолжить, иначе — уточнить)"
 *
 * свободный текст при активной задаче → handleUserInput(text)
 *   awaitingAdvance=true:
 *     isYes(text) → advanceTaskState + runStage(следующая) + артефакт
 *     иначе       → перегенерация артефакта текущей стадии с feedback=text
 *   awaitingAdvance=false (clarify ждёт ответы):
 *     → ClarifyStageAgent.run(feedback=text) → loop пока [CLEAR]
 * ```
 */
class TaskOrchestrator(
    private val agent: ContextAwareAgent,
    llmClient: LlmClient,
    model: String,
    private val classifier: EntryStageClassifier = EntryStageClassifier(llmClient, model),
    agents: Map<TaskStage, StageAgent> = defaultAgents(),
    /**
     * День 15 (gap №6): провайдер LLM-чата для stage-вызовов. Default — agent.chat (текущее
     * поведение). Wiring (ChatCommand) подставляет StatefulAgent.chat → инварианты покрывают
     * stage-flow, а не только свободный чат.
     */
    private val chat: suspend (String) -> String = { userMsg -> agent.chat(userMsg) }
) {
    private val agents: Map<TaskStage, StageAgent> = agents

    /**
     * Старт задачи: классификатор → стартовая стадия → генерация артефакта.
     * Особый случай: старт на CLARIFY с немедленным `[CLEAR]` (требования уже ясны из описания)
     * → авто-advance на PLANNING и запуск planning-агента в том же ходе.
     *
     * @return markdown-текст для показа пользователю
     */
    suspend fun startTask(taskDescription: String): String {
        val startStage = classifier.classify(taskDescription)
        agent.setTaskState(
            TaskState(
                stage = startStage,
                currentStep = taskDescription,
                awaitingAdvance = false
            )
        )
        return runStageAndDisplay(startStage, taskDescription, feedback = null)
    }

    /**
     * Единая диспетчеризация свободного текста при активной задаче.
     *
     * День 15 (п.3): учитывает [mode]. В AUTO при `awaitingAdvance=true` — авто-advance без ожидания
     * «да» (даже для произвольного текста). В PLAN/MANUAL — подтверждение или уточнение.
     *
     * @return markdown-текст; null — нет активной задачи (caller должен уйти в обычный чат)
     */
    suspend fun handleUserInput(
        text: String,
        mode: InteractionMode = InteractionMode.PLAN
    ): String? {
        val state = agent.getTaskState() ?: return null
        val desc = state.currentStep ?: ""

        return if (state.awaitingAdvance) {
            // День 15 (AUTO): не ждём подтверждения — авто-advance (если артефакт готов; guard проверит).
            if (mode == InteractionMode.AUTO || isYes(text)) {
                advanceAndRunNext(state, desc, mode)
            } else {
                // Любой иной текст (включая «нет») → уточнение артефакта текущей стадии
                runStageAndDisplay(state.stage, desc, feedback = text)
            }
        } else {
            // awaitingAdvance=false → clarify ждёт ответы пользователя (все режимы)
            runStageAndDisplay(state.stage, desc, feedback = text)
        }
    }

    /** Признаки подтверждения (да/yes/y/продолжай/далее/ок/готово). */
    private fun isYes(text: String): Boolean {
        val t = text.trim().lowercase()
        return t in setOf("да", "yes", "y", "ок", "ok", "продолжай", "далее", "дальше", "готово", "подтверждаю")
    }

    /**
     * Переход на следующую стадию + запуск её агента.
     * DONE — терминальная: завершаем.
     *
     * День 15: в AUTO после готовности артефакта — авто-advance к следующей стадии (рекурсивно),
     * пока не достигнем DONE или блокировки. Глубина ограничена числом стадий (макс 5: clarify→
     * planning→execution→validation→done) — бесконечного цикла нет.
     */
    private suspend fun advanceAndRunNext(
        state: TaskState,
        desc: String,
        mode: InteractionMode = InteractionMode.PLAN
    ): String {
        if (state.stage == TaskStage.DONE) {
            return "🏁 Задача уже завершена. Начни новую через `/task start <описание>`."
        }
        // canAdvance-проверка: артефакт текущей стадии должен быть готов
        if (!TaskStateMachine.canAdvance(state)) {
            val what = missingArtifactHint(state.stage)
            return "⚠️ Нельзя перейти дальше: $what.\n" +
                "Уточни артефакт текстом или задай его через `/task`."
        }
        val next = agent.advanceTaskState() ?: return "✓ Уже на финальной стадии."
        val display = runStageAndDisplay(next.stage, desc, feedback = null)

        // День 15 (AUTO): после генерации артефакта — авто-advance к следующей стадии без запроса.
        val updated = agent.getTaskState()
        if (mode == InteractionMode.AUTO && updated != null &&
            updated.awaitingAdvance && updated.stage != TaskStage.DONE
        ) {
            // авто-advance через ту же логику (guard внутри); рекурсия ограничена числом стадий.
            return display + "\n\n___\n\n" + advanceAndRunNext(updated, desc, mode)
        }
        return display
    }

    /**
     * Запускает [StageAgent] для стадии, сохраняет артефакт в нужное поле [TaskState],
     * выставляет [TaskState.awaitingAdvance], возвращает markdown-дисплей.
     */
    private suspend fun runStageAndDisplay(
        stage: TaskStage,
        taskDescription: String,
        feedback: String?
    ): String {
        val current = agent.getTaskState()!!
        val profile = agent.getProfile()
        val ctx = StageContext(
            taskDescription = taskDescription,
            requirements = current.requirements,
            approvedPlan = current.approvedPlan,
            implementation = current.implementation,
            verdict = current.verdict,
            profile = profile,
            feedback = feedback
        )

        val agentImpl = agents[stage]
            ?: return "⚠️ Нет агента для стадии $stage. Используй `/task next`."

        val result = agentImpl.run(ctx) { userMsg -> chat(userMsg) }

        // Сохраняем артефакт в соответствующее поле + awaitingAdvance
        val updated = storeArtifact(current, stage, result.artifact)
            .copy(awaitingAdvance = result.readyToAdvance)
        agent.setTaskState(updated)

        // Особый случай: CLARIFY дал [CLEAR] → авто-advance на PLANNING и запуск в том же ходе
        if (stage == TaskStage.CLARIFY && result.readyToAdvance && updated.stage != TaskStage.PLANNING) {
            val planned = agent.advanceTaskState()   // CLARIFY → PLANNING
            if (planned != null) {
                return result.display + "\n\n---\n\n" +
                    runStageAndDisplay(TaskStage.PLANNING, taskDescription, feedback = null)
            }
        }

        val prompt = if (result.readyToAdvance && stage != TaskStage.DONE) {
            val nextStage = TaskStateMachine.next(stage)
            if (nextStage != null) {
                "\n\n___\n\n✅ Перейти к стадии **${nextStage.name}**? " +
                    "Ответь **да** — продолжить, либо напиши уточнение для доработки."
            } else null
        } else null

        return result.display + (prompt ?: "")
    }

    /** Кладёт артефакт стадии в соответствующее поле TaskState. */
    private fun storeArtifact(
        state: TaskState,
        stage: TaskStage,
        artifact: String?
    ): TaskState = when (stage) {
        TaskStage.CLARIFY -> if (artifact != null) state.copy(requirements = artifact) else state
        TaskStage.PLANNING -> state.copy(approvedPlan = artifact)
        TaskStage.EXECUTION -> state.copy(implementation = artifact)
        TaskStage.VALIDATION -> state.copy(verdict = artifact)
        TaskStage.DONE -> state   // summary не персистим отдельным полем
    }

    private fun missingArtifactHint(stage: TaskStage): String = when (stage) {
        TaskStage.PLANNING -> "план (approvedPlan) пуст"
        TaskStage.EXECUTION -> "реализация (implementation) пуста"
        TaskStage.VALIDATION -> "вердикт (verdict) пуст"
        else -> "артефакт стадии не готов"
    }

    companion object {
        /** Реестр стадийных агентов по умолчанию (один агент на каждую стадию FSM). */
        fun defaultAgents(): Map<TaskStage, StageAgent> = mapOf(
            TaskStage.CLARIFY to ClarifyStageAgent(),
            TaskStage.PLANNING to PlanningStageAgent(),
            TaskStage.EXECUTION to ExecutionStageAgent(),
            TaskStage.VALIDATION to ValidationStageAgent(),
            TaskStage.DONE to DoneStageAgent()
        )
    }
}
