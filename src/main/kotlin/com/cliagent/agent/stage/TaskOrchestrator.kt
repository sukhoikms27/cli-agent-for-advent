package com.cliagent.agent.stage

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.agent.swarm.SwarmStageAgent
import com.cliagent.llm.LlmCallException
import com.cliagent.llm.LlmClient
import com.cliagent.memory.UserProfile
import com.cliagent.state.InteractionMode
import com.cliagent.state.TaskKind
import com.cliagent.state.TaskStage
import com.cliagent.state.TaskState
import com.cliagent.state.TaskStateMachine

/**
 * Координатор стадийного потока (доработка Day 13 + день 15).
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
 * **День 15 (фикс #3/#4): mode-aware drive-модель.** В AUTO оркестратор сам прогоняет все готовые
 * стадии до DONE в одном ходе ([drive] рекурсивно чейнится), выдавая по каждой стадии заголовок +
 * артефакт + `⏭ → <next>` ([StageAnnouncer]) — без запроса подтверждения. В PLAN — одна стадия за
 * ход с предложением перехода. Фикс #1: на старте классифицируется [TaskKind] ([TaskKindClassifier])
 * и хранится в [TaskState.taskKind] → EXECUTION пишет код только для программных задач.
 *
 * Поток:
 * ```
 * /task start <desc>  → startTask(desc, mode)
 *   classifier → CLARIFY | PLANNING; taskKindClassifier → TaskKind?
 *   drive(startStage) → артефакт (+ clarify[CLEAR]→planning) + AUTO-чейн до DONE
 *
 * свободный текст при активной задаче → handleUserInput(text, mode)
 *   awaitingAdvance=true:
 *     AUTO | isYes → advanceAndDrive → drive(следующая) (+ AUTO-чейн)
 *     иначе        → drive(текущая, feedback) — уточнение артефакта
 *   awaitingAdvance=false (clarify ждёт ответы):
 *     → drive(текущая, feedback) → loop пока [CLEAR]; в AUTO — чейн дальше
 * ```
 */
class TaskOrchestrator(
    private val agent: ContextAwareAgent,
    llmClient: LlmClient,
    model: String,
    private val classifier: EntryStageClassifier = EntryStageClassifier(llmClient, model),
    private val taskKindClassifier: TaskKindClassifier = TaskKindClassifier(llmClient, model),
    private val confirmationClassifier: ConfirmationClassifier = ConfirmationClassifier(llmClient, model),
    /** День 21 (W2): классификатор сложности задачи — определяет, где рой окупается. */
    private val complexityClassifier: TaskComplexityClassifier = TaskComplexityClassifier(llmClient, model),
    /**
     * День 21 (волна W1): режим роя. [com.cliagent.agent.swarm.SwarmMode.AUTO] — адаптивный гейт
     * (рой только на PLANNING/EXECUTION/VALIDATION; CLARIFY/DONE — single; TRIVIAL → все single).
     * [SwarmMode.ON] — рой на всех стадиях (дебаг). [SwarmMode.OFF] — простые агенты везде.
     *
     * **Дефолт OFF** — сохраняет детерминизм в тестах (где оркестратор часто строится без явного
     * режима). ChatCommand прокидывает реальный режим из `--swarm-mode` (AUTO по умолчанию в CLI).
     *
     * **Важно:** complexity (волна W2) определяется в [startTask] и может переопределить выбор
     * агентов: TRIVIAL → все single-agent даже при AUTO. Поэтому [agents] пересобирается в
     * [startTask], если не задан явно.
     */
    private val swarmMode: com.cliagent.agent.swarm.SwarmMode = com.cliagent.agent.swarm.SwarmMode.OFF,
    /**
     * Legacy boolean (backward-compat с тестами). Если [swarmMode] не задан явно (AUTO), `swarm=true`
     * форсирует [SwarmMode.ON]. Новый код должен использовать [swarmMode].
     */
    swarm: Boolean = false,
    agents: Map<TaskStage, StageAgent>? = null,
    /**
     * День 15 (gap №6): провайдер LLM-чата для stage-вызовов. Default — agent.chat (текущее
     * поведение). Wiring (ChatCommand) подставляет StatefulAgent.chat → инварианты покрывают
     * stage-flow, а не только свободный чат.
     */
    private val chat: suspend (String) -> String = { userMsg -> agent.chat(userMsg) }
) {
    // effective swarm mode: swarmMode имеет приоритет; legacy swarm=true → ON только если swarmMode=OFF.
    private val effectiveSwarmMode: com.cliagent.agent.swarm.SwarmMode =
        if (swarm && swarmMode == com.cliagent.agent.swarm.SwarmMode.OFF) com.cliagent.agent.swarm.SwarmMode.ON
        else swarmMode
    private val agentsExplicitlySet: Boolean = agents != null
    private var agents: Map<TaskStage, StageAgent> = agents ?: defaultAgents(effectiveSwarmMode)

    /**
     * День 19 (debug): текущая стадия для дампа raw-ответов в [chatWithContinue]. Ставится в
     * [runOneStage] перед вызовом stage-агента (как currentSpinnerStage в ChatCommand). null вне
     * stage-потока (свободный чат не дампится через этот шов — он идёт в обход chatWithContinue).
     */
    private var debugStage: TaskStage? = null

    /** Включён ли debug-дамп stage-LLM-вызовов (env `CLI_AGENT_DEBUG`). */
    private val debugEnabled: Boolean = System.getenv("CLI_AGENT_DEBUG") != null

    /**
     * Старт задачи: классификатор entry-стадии + тип задачи → стартовое состояние → drive.
     * Особый случай: старт на CLARIFY с немедленным `[CLEAR]` — обрабатывается в [drive]
     * (clarify→planning в одном ходе, далее в AUTO — чейн до DONE).
     *
     * @return markdown-текст для показа пользователю
     */
    suspend fun startTask(
        taskDescription: String,
        mode: InteractionMode = InteractionMode.PLAN,
        onEmit: suspend (String) -> Unit = {},
        onStageStart: (TaskStage) -> Unit = {}
    ): String {
        val startStage = classifier.classify(taskDescription)
        val kind = taskKindClassifier.classify(taskDescription)
        // День 21 (W2): сложность задачи → пересобираем реестр агентов (если AUTO и не задан явно).
        // TRIVIAL → все single-agent; MODERATE/COMPLEX → применяем AUTO-гейт. OFF/ON игнорируют complexity.
        val complexity = complexityClassifier.classify(taskDescription)
        if (effectiveSwarmMode == com.cliagent.agent.swarm.SwarmMode.AUTO && !agentsExplicitlySet) {
            agents = defaultAgents(effectiveSwarmMode, complexity)
        }
        agent.setTaskState(
            TaskState(
                stage = startStage,
                currentStep = taskDescription,
                awaitingAdvance = false,
                taskKind = kind,
                complexity = complexity
            )
        )
        return drive(startStage, taskDescription, feedback = null, mode = mode, onEmit = onEmit, onStageStart = onStageStart)
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
        mode: InteractionMode = InteractionMode.PLAN,
        onEmit: suspend (String) -> Unit = {},
        onStageStart: (TaskStage) -> Unit = {}
    ): String? {
        val state = agent.getTaskState() ?: return null
        val desc = state.currentStep ?: ""

        return if (state.awaitingAdvance) {
            // День 15 (AUTO): не ждём подтверждения — авто-advance (если артефакт готов; guard проверит).
            // День 15 (доп. п.2 — гибрид): в PLAN/MANUAL подтверждение = быстрый путь [isYes]
            // (детерминированно, без LLM) либо LLM-интерпретация [ConfirmationClassifier] для
            // естественных фраз. Safe-bias: только CONFIRM продвигает; REFINE/AMBIGUOUS → feedback.
            if (mode == InteractionMode.AUTO || isYes(text) ||
                confirmationClassifier.classify(text) == ConfirmationIntent.CONFIRM
            ) {
                advanceAndDrive(state, desc, mode, onEmit, onStageStart)
            } else {
                // Любой иной текст (включая «нет», уточнения, оговорки) → доработка артефакта
                drive(state.stage, desc, feedback = text, mode = mode, onEmit = onEmit, onStageStart = onStageStart)
            }
        } else {
            // awaitingAdvance=false → clarify ждёт ответы пользователя (все режимы);
            // в AUTO после [CLEAR] drive сам чейнится дальше.
            drive(state.stage, desc, feedback = text, mode = mode, onEmit = onEmit, onStageStart = onStageStart)
        }
    }

    /**
     * Быстрый путь подтверждения (день 15, доп. п.2 — гибрид): точное совпадение с очевидными
     * короткими подтверждениями, детерминированно и без LLM-вызова. Естественные фразы и оговорки,
     * не попавшие сюда, разбираются [ConfirmationClassifier]. Точное совпадение намеренно —
     * «да, но…» не матчится и уходит в LLM/feedback (safe-bias).
     */
    private fun isYes(text: String): Boolean {
        val t = text.trim().lowercase()
        return t in setOf(
            "да", "yes", "y", "ок", "ok", "окей", "угу", "ага", "давай", "поехали", "го", "go",
            "продолжай", "далее", "дальше", "готово", "подтверждаю", "хорошо", "отлично", "супер",
            "конечно", "верно"
        )
    }

    /**
     * Единый драйв стадии (фикс #3/#4 + progressive emission).
     *
     * 1. `onStageStart(stage)` — клиент ставит лейбл спиннера.
     * 2. Прогоняет [StageAgent] текущей стадии ([runOneStage]).
     * 3. Особый случай CLARIFY[CLEAR] → авто-advance на PLANNING + прогон planning в том же ходе.
     * 4. Формирует [StageAnnouncer.stageBlock] и **эміттит его сразу** через [onEmit] (progressive),
     *    параллельно копит в возврат.
     * 5. **AUTO-чейнинг**: если артефакт готов и стадия не терминальна — advance + рекурсивный
     *    [drive] следующей стадии. Рекурсия ограничена числом стадий (≤5) — бесконечного цикла нет.
     */
    private suspend fun drive(
        stage: TaskStage,
        taskDescription: String,
        feedback: String?,
        mode: InteractionMode,
        onEmit: suspend (String) -> Unit,
        onStageStart: (TaskStage) -> Unit
    ): String {
        onStageStart(stage)
        val result = runOneStage(stage, taskDescription, feedback)

        var display = result.display
        var curStage = stage
        var ready = result.readyToAdvance

        // Особый случай: CLARIFY дал [CLEAR] → авто-advance на PLANNING и прогон planning в том же ходе.
        if (stage == TaskStage.CLARIFY && ready) {
            val planned = agent.advanceTaskState()   // CLARIFY → PLANNING
            if (planned != null) {
                onStageStart(TaskStage.PLANNING)
                val planResult = runOneStage(TaskStage.PLANNING, taskDescription, feedback = null)
                display = display + SEP + planResult.display
                curStage = TaskStage.PLANNING
                ready = planResult.readyToAdvance
            }
        }

        // DONE — терминальная: readyToAdvance=false по семантике, но для UI трактуем как готовую.
        val effectiveReady = ready || curStage == TaskStage.DONE
        val nextStage = TaskStateMachine.next(curStage)
        val block = StageAnnouncer.stageBlock(curStage, display, effectiveReady, nextStage, mode)

        // Progressive: блок стадии готов — эміттим сразу, до старта следующей.
        onEmit(block)
        var acc = block

        // AUTO-чейнинг: готовый артефакт + не терминальная → advance + drive следующей.
        if (mode == InteractionMode.AUTO && effectiveReady && curStage != TaskStage.DONE && nextStage != null) {
            val advanced = agent.advanceTaskState()
            if (advanced != null) {
                acc += SEP + drive(advanced.stage, taskDescription, feedback = null, mode = mode,
                    onEmit = onEmit, onStageStart = onStageStart)
            }
        }
        return acc
    }

    /**
     * Переход на следующую стадию + drive её агента (FIX #3: используется и в AUTO-чейне, и в
     * подтверждённом advance). Guards: DONE → «уже завершена»; неготовый артефакт → блокировка.
     */
    private suspend fun advanceAndDrive(
        state: TaskState,
        desc: String,
        mode: InteractionMode,
        onEmit: suspend (String) -> Unit,
        onStageStart: (TaskStage) -> Unit
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
        return drive(next.stage, desc, feedback = null, mode = mode, onEmit = onEmit, onStageStart = onStageStart)
    }

    /**
     * Прогон одного [StageAgent]: строит [StageContext] из текущего [TaskState] (включая [TaskKind]),
     * вызывает агент, кладёт артефакт в нужное поле, выставляет [TaskState.awaitingAdvance],
     * персистит. Без форматирования и без чейнинга — чистый исполнитель одной стадии.
     */
    private suspend fun runOneStage(
        stage: TaskStage,
        taskDescription: String,
        feedback: String?
    ): StageResult {
        val current = agent.getTaskState()!!
        val profile = agent.getProfile()
        val ctx = StageContext(
            taskDescription = taskDescription,
            requirements = current.requirements,
            approvedPlan = current.approvedPlan,
            implementation = current.implementation,
            verdict = current.verdict,
            profile = profile,
            feedback = feedback,
            taskKind = current.taskKind,
            complexity = current.complexity,
            tokenCounter = agent.tokenCounter()
        )

        val agentImpl = agents[stage]
            ?: return StageResult(
                artifact = null,
                display = "⚠️ Нет агента для стадии $stage. Используй `/task next`.",
                readyToAdvance = false
            )

        // LLM-сбой (таймаут/HTTP/мусор) — НЕ трактуем как готовый артефакт: не персистим,
        // не предлагаем переход. Стадия остаётся awaitingAdvance=false → пользователь может
        // повторить отправкой текста (drive перезапустит стадию с feedback) или через `/task`.
        // Мера B: chat-делегат обёрнут в [chatWithContinue] — обрыв по finish_reason=length
        // сшивается auto-continue; сюда попадает только исчерпание продолжений или иной сбой.
        return try {
            debugStage = stage   // для дампа raw-ответов в chatWithContinue (CLI_AGENT_DEBUG)
            val result = agentImpl.run(ctx) { userMsg -> chatWithContinue(userMsg) }

            // Сохраняем артефакт в соответствующее поле + awaitingAdvance
            val updated = storeArtifact(current, stage, result.artifact)
                .copy(awaitingAdvance = result.readyToAdvance)
            agent.setTaskState(updated)
            result
        } catch (e: LlmCallException) {
            agent.setTaskState(current.copy(awaitingAdvance = false))
            val hint = if (e.isTruncated) {
                "⚠️ Ответ модели слишком длинный — не удалось завершить за $MAX_CONTINUATIONS " +
                    "продолжений. Уточните задачу/разбейте стадию или используйте `/task`."
            } else {
                "⚠️ Ошибка запроса к LLM: ${e.message}"
            }
            StageResult(
                artifact = null,
                display = "$hint\nСтадия не завершена. Отправьте сообщение, чтобы повторить, " +
                    "или используйте `/task`.",
                readyToAdvance = false
            )
        }
    }

    /**
     * Мера B: chat-делегат с auto-continue. Ловит обрыв ответа по `finish_reason=length`
     * ([LlmCallException.isTruncated]), копит частичный ответ и досылает continue-промпт.
     * После [MAX_CONTINUATIONS] продолжений — повторно бросает truncated (попадёт в catch
     * [runOneStage]). Не-truncated ошибки прокидывает как есть.
     */
    private suspend fun chatWithContinue(userMsg: String): String {
        var accumulated = ""
        var prompt = userMsg
        var continuations = 0
        while (true) {
            if (debugEnabled) debugDumpPrompt(debugStage, prompt, continuations)
            val chunk: String = try {
                chat(prompt)
            } catch (e: LlmCallException) {
                if (!e.isTruncated) throw e
                if (continuations >= MAX_CONTINUATIONS) throw e   // исчерпали попытки
                accumulated += e.partial.orEmpty()
                continuations++
                prompt = continuePrompt(userMsg, accumulated)
                continue
            }
            if (debugEnabled) debugDumpResponse(debugStage, chunk)
            return accumulated + chunk   // полный (или финальный) ответ
        }
    }

    /**
     * День 19 (debug): дамплы raw-промпта/ответа stage-LLM-вызовов в **stderr** (env `CLI_AGENT_DEBUG`).
     * stderr отделён от REPL-вывода/stdout — не перемешивается со stage-блоками и не затирается
     * спиннером mordant. Видно, что реально уходит/приходит на каждой стадии — диагноз пустых
     * артефактов (как verdict с REWORK без текста), обрывов, tool-call'ов во время валидации.
     */
    private fun debugDumpPrompt(stage: TaskStage?, prompt: String, continuation: Int) {
        val tag = stage?.name?.lowercase() ?: "stage"
        val suf = if (continuation > 0) " (continue #$continuation)" else ""
        System.err.println("\n[DEBUG][$tag] >>> PROMPT$suf (${prompt.length} chars):\n${prompt.take(MAX_DEBUG_CHARS)}")
        if (prompt.length > MAX_DEBUG_CHARS) System.err.println("[DEBUG][$tag] ... (${prompt.length - MAX_DEBUG_CHARS} chars truncated)")
    }

    private fun debugDumpResponse(stage: TaskStage?, response: String) {
        val tag = stage?.name?.lowercase() ?: "stage"
        System.err.println("\n[DEBUG][$tag] <<< RESPONSE (${response.length} chars):\n${response.take(MAX_DEBUG_CHARS)}")
        if (response.length > MAX_DEBUG_CHARS) System.err.println("[DEBUG][$tag] ... (${response.length - MAX_DEBUG_CHARS} chars truncated)")
    }

    private fun continuePrompt(originalMessage: String, accumulated: String): String = buildString {
        append("Продолжи ответ с места обрыва. НЕ повторяй уже написанное.\n\n")
        append("Исходный запрос:\n").append(originalMessage.take(ORIGINAL_MSG_CHARS))
        append("\n\nУже сгенерированная часть:\n").append(accumulated.take(ACCUMULATED_CHARS))
        append("\n\nПродолжай дальше с того места, где обрывилось, не дублируя написанное.")
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
        /** Разделитель между стадиями в AUTO-чейне (и в clarify→planning склейке). */
        private const val SEP = "\n\n---\n\n"

        /** Мера B: максимум auto-continue попыток при обрыве ответа по длине. */
        private const val MAX_CONTINUATIONS = 2
        private const val ORIGINAL_MSG_CHARS = 4000
        private const val ACCUMULATED_CHARS = 8000
        /** День 19 (debug): потолок дампа промпта/ответа в stderr (чтобы не затапливать терминал). */
        private const val MAX_DEBUG_CHARS = 2000

        /**
         * Реестр стадийных агентов по умолчанию.
         *
         * День 21 (волна W1): вместо бинарного `swarm` принимается [SwarmMode].
         * - [SwarmMode.OFF]  — простые агенты на всех стадиях (бывший swarm=false).
         * - [SwarmMode.ON]   — рой на всех стадиях (бывший swarm=true; для дебага).
         * - [SwarmMode.AUTO] — адаптивный гейт: рой только на PLANNING/EXECUTION/VALIDATION;
         *   CLARIFY/DONE — простые агенты (фан-аут на уточнении/суммаризации неоправдан → экономия
         *   ~5 LLM-вызовов на задачу).
         *
         * [complexity] (волна W2): при TRIVIAL — простые агенты на ВСЕХ стадиях (рой не окупается);
         *   учитывается только в AUTO. null трактуется как MODERATE.
         */
        fun defaultAgents(
            swarmMode: com.cliagent.agent.swarm.SwarmMode,
            complexity: com.cliagent.state.TaskComplexity? = null,
        ): Map<TaskStage, StageAgent> {
            // Stage→swarm decision. ON = рой везде; OFF = нигде; AUTO = адаптивно.
            val swarmStages: Set<TaskStage> = when (swarmMode) {
                com.cliagent.agent.swarm.SwarmMode.OFF -> emptySet()
                com.cliagent.agent.swarm.SwarmMode.ON -> TaskStage.entries.toSet()
                com.cliagent.agent.swarm.SwarmMode.AUTO -> {
                    // W2: TRIVIAL → рой нигде (весь пайплайн single-agent).
                    if (complexity == com.cliagent.state.TaskComplexity.TRIVIAL) {
                        emptySet()
                    } else {
                        // W1.1+W1.2: CLARIFY/DONE — простые; PLANNING/EXECUTION/VALIDATION — рой.
                        setOf(TaskStage.PLANNING, TaskStage.EXECUTION, TaskStage.VALIDATION)
                    }
                }
            }
            return TaskStage.entries.associateWith { stage ->
                if (stage in swarmStages) SwarmStageAgent(stage) else simpleAgent(stage)
            }
        }

        /** Простые (single-pass) агенты по стадии — для [SwarmMode.OFF] и AUTO-гейтов (CLARIFY/DONE). */
        private fun simpleAgent(stage: TaskStage): StageAgent = when (stage) {
            TaskStage.CLARIFY -> ClarifyStageAgent()
            TaskStage.PLANNING -> PlanningStageAgent()
            TaskStage.EXECUTION -> ExecutionStageAgent()
            TaskStage.VALIDATION -> ValidationStageAgent()
            TaskStage.DONE -> DoneStageAgent()
        }

        /**
         * Legacy-перегрузка (boolean) — backward-compat для тестов и внешних вызовов. Не удалять.
         * swarm=true → [SwarmMode.ON]; false → [SwarmMode.OFF].
         */
        fun defaultAgents(swarm: Boolean = false): Map<TaskStage, StageAgent> =
            defaultAgents(if (swarm) com.cliagent.agent.swarm.SwarmMode.ON else com.cliagent.agent.swarm.SwarmMode.OFF)
    }
}
