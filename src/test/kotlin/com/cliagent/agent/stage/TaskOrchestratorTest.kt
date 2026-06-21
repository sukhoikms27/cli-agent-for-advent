package com.cliagent.agent.stage

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.agent.ProfileExtractor
import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmCallException
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import com.cliagent.memory.WorkingMemory
import com.cliagent.state.InteractionMode
import com.cliagent.state.TaskStage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskOrchestratorTest {

    private fun fakeResponse(content: String): ChatResponse = ChatResponse(
        id = "resp",
        choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = content))),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
    )

    /** LLM, который возвращает [content] на любой запрос (используется агентом стадии). */
    private fun stubLlm(content: String): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Success(fakeResponse(content))
    }

    /**
     * LLM, который возвращает разные ответы: [classifierContent] — на запрос классификатора
     * (последнее сообщение = «ответь строго CLARIFY или PLANNING»), [stageContent] — прочие.
     */
    private fun routingLlm(classifierContent: String, stageContent: String): LlmClient = mockk {
        coEvery { chat(any()) } answers {
            val req = firstArg<ChatRequest>()
            val last = req.messages.lastOrNull()?.content.orEmpty()
            if (last.contains("ответь строго", ignoreCase = true)) {
                LlmResult.Success(fakeResponse(classifierContent))
            } else {
                LlmResult.Success(fakeResponse(stageContent))
            }
        }
    }

    private fun storeMock(): MemoryStore {
        var stored: WorkingMemory? = null
        return mockk {
            coEvery { loadHistory(any()) } returns emptyList()
            coEvery { loadWorkingMemory(any()) } answers { stored }
            coEvery { saveWorkingMemory(any(), any()) } answers { stored = secondArg() }
            coEvery { loadLongTermMemory() } returns LongTermMemory()
            coEvery { loadSummary(any()) } returns null
            coEvery { saveMessage(any(), any()) } returns Unit
            coEvery { saveLongTermMemory(any()) } returns Unit
            coEvery { saveFacts(any(), any()) } returns Unit
            coEvery { loadFacts(any()) } returns emptyMap()
        }
    }

    private fun makeOrchestrator(
        llm: LlmClient,
        agents: Map<TaskStage, StageAgent> = TaskOrchestrator.defaultAgents()
    ): ContextAwareAgent {
        // Возвращаем сам agent; оркестратор соберём в тесте с нужными agents.
        // (helper существует для общей настройки store/agent.)
        return ContextAwareAgent(llm, storeMock(), "m", "chat-1")
    }

    @Test
    fun `startTask with PLANNING classification generates plan artifact and awaits advance`() = runTest {
        val llm = routingLlm(classifierContent = "PLANNING", stageContent = "1) Setup\n2) Impl")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        val out = orch.startTask("сделай калькулятор")
        val state = agent.getTaskState()

        assertEquals(TaskStage.PLANNING, state?.stage)
        assertEquals("1) Setup\n2) Impl", state?.approvedPlan)
        assertEquals(true, state?.awaitingAdvance)
        assertTrue(out.contains("План"))
        assertTrue(out.contains("Перейти к стадии"))
    }

    @Test
    fun `startTask with CLARIFY and CLEAR auto-advances to PLANNING`() = runTest {
        // Классификатор → CLARIFY; первый запуск clarify сразу даёт [CLEAR]
        val llm = routingLlm(classifierContent = "CLARIFY", stageContent = "[CLEAR] стек Kotlin")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")

        val state = agent.getTaskState()
        // Ожидали авто-advance: финальная стадия — PLANNING (clarify→planning в одном ходе)
        assertEquals(TaskStage.PLANNING, state?.stage)
        assertNotNull(state?.approvedPlan)
    }

    @Test
    fun `startTask with CLARIFY and ASK stays on CLARIFY awaiting answers`() = runTest {
        val llm = routingLlm(classifierContent = "CLARIFY", stageContent = "[ASK] какой стек?")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        val out = orch.startTask("сделай калькулятор")
        val state = agent.getTaskState()

        assertEquals(TaskStage.CLARIFY, state?.stage)
        assertEquals(false, state?.awaitingAdvance)   // ждёт ответы
        assertTrue(out.contains("Вопросы"))
    }

    @Test
    fun `handleUserInput yes advances to next stage and runs its agent`() = runTest {
        // Старт на PLANNING → план готов; затем «да» → EXECUTION генерирует реализацию
        val llm = routingLlm(classifierContent = "PLANNING", stageContent = "1) Setup\n2) Impl")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")
        val out = orch.handleUserInput("да")!!
        val state = agent.getTaskState()

        assertEquals(TaskStage.EXECUTION, state?.stage)
        assertNotNull(state?.implementation)
        assertTrue(out.contains("Реализация"))
    }

    @Test
    fun `handleUserInput arbitrary text regenerates current stage artifact with feedback`() = runTest {
        val llm = routingLlm(classifierContent = "PLANNING", stageContent = "1) Setup\n2) Impl")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")
        val originalPlan = agent.getTaskState()!!.approvedPlan

        val out = orch.handleUserInput("добавь шаг про тесты")!!
        val state = agent.getTaskState()

        // Стадия не сменилась, awaitingAdvance всё ещё true, но артефакт мог обновиться
        assertEquals(TaskStage.PLANNING, state?.stage)
        assertEquals(true, state?.awaitingAdvance)
        assertNotNull(state?.approvedPlan)
    }

    @Test
    fun `handleUserInput no stays on stage and treats as feedback`() = runTest {
        val llm = routingLlm(classifierContent = "PLANNING", stageContent = "1) Setup")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")
        orch.handleUserInput("нет")

        // «нет» — это не подтверждение → стадия та же
        assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)
    }

    @Test
    fun `handleUserInput on clarify with answers loops until CLEAR`() = runTest {
        // Классификатор → CLARIFY; clarify даёт [ASK], затем после ответа — [CLEAR]→planning
        var clarifyCall = 0
        val llm = mockk<LlmClient> {
            coEvery { chat(any()) } answers {
                val req = firstArg<ChatRequest>()
                val last = req.messages.lastOrNull()?.content.orEmpty()
                when {
                    last.contains("ответь строго", ignoreCase = true) ->
                        LlmResult.Success(fakeResponse("CLARIFY"))
                    last.contains("CLARIFY", ignoreCase = true) || last.contains("неоднозначности", ignoreCase = true) -> {
                        clarifyCall++
                        // первый clarify-вызов задаёт вопросы, второй (с ответами) — CLEAR
                        if (clarifyCall == 1) LlmResult.Success(fakeResponse("[ASK] какой стек?"))
                        else LlmResult.Success(fakeResponse("[CLEAR] стек Kotlin"))
                    }
                    else -> LlmResult.Success(fakeResponse("1) Setup"))
                }
            }
        }
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")          // → CLARIFY, [ASK]
        assertEquals(TaskStage.CLARIFY, agent.getTaskState()?.stage)

        orch.handleUserInput("Kotlin")                 // ответы → [CLEAR] → авто PLANNING
        assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)
    }

    @Test
    fun `full pipeline planning-execution-validation-done via yes confirmations`() = runTest {
        // LLM, который различает стадию по сигнатуре промпта и даёт корректные артефакты.
        val llm = stageAwareLlm()
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")     // PLANNING
        orch.handleUserInput("да")               // → EXECUTION
        assertEquals(TaskStage.EXECUTION, agent.getTaskState()?.stage)
        orch.handleUserInput("да")               // → VALIDATION
        assertEquals(TaskStage.VALIDATION, agent.getTaskState()?.stage)
        orch.handleUserInput("да")               // → DONE (validation дал PASS)
        assertEquals(TaskStage.DONE, agent.getTaskState()?.stage)
        assertEquals(false, agent.getTaskState()?.awaitingAdvance)   // терминальная
    }

    @Test
    fun `handleUserInput returns null when no active task`() = runTest {
        val llm = stubLlm("ok")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        assertNull(orch.handleUserInput("что угодно"))
    }

    // ── День 15 фикс #3/#4: AUTO-чейнинг всех стадий до DONE в одном ходе ──

    @Test
    fun `AUTO startTask chains all stages to DONE in one call with intermediate results`() = runTest {
        val llm = stageAwareLlm()
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        val out = orch.startTask("сделай калькулятор", InteractionMode.AUTO)
        val state = agent.getTaskState()

        // Один ход — дошли до терминальной стадии
        assertEquals(TaskStage.DONE, state?.stage)
        // Промежуточные результаты каждой стадии присутствуют
        assertTrue(out.contains("Реализация"), "AUTO должен показать артефакт execution")
        assertTrue(out.contains("Валидация"), "AUTO должен показать стадию validation")
        // Фикс #3: в AUTO нет вопроса подтверждения перехода
        assertFalse(out.contains("Перейти к стадии"), "AUTO не должен спрашивать подтверждение")
        // Сообщения о переходе есть
        assertTrue(out.contains("⏭ →"), "AUTO должен показывать переходы между стадиями")
    }

    @Test
    fun `AUTO pauses when clarify asks questions`() = runTest {
        // clarify даёт [ASK] → не ready → AUTO не чейнится, ждёт ответы пользователя
        val llm = routingLlm(classifierContent = "CLARIFY", stageContent = "[ASK] какой стек?")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор", InteractionMode.AUTO)

        assertEquals(TaskStage.CLARIFY, agent.getTaskState()?.stage)
        assertEquals(false, agent.getTaskState()?.awaitingAdvance)   // ждёт ответы
    }

    @Test
    fun `AUTO startTask emits one block per stage progressively via onEmit`() = runTest {
        // Фикс progressive: каждая стадия эміттит свой блок сразу по готовности (>=4 блоков:
        // planning/execution/validation/done), а не сцепленной строкой в конце.
        val llm = stageAwareLlm()
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        val emitted = mutableListOf<String>()
        val stagesSeen = mutableListOf<TaskStage>()
        orch.startTask(
            "сделай калькулятор", InteractionMode.AUTO,
            onEmit = { emitted += it },
            onStageStart = { stagesSeen += it }
        )

        assertTrue(emitted.size >= 4, "ожидали >=4 progressive-блоков, получили ${emitted.size}")
        val all = emitted.joinToString("\n---\n")
        assertTrue(all.contains("Реализация"), "execution-блок должен быть эміттнут")
        assertTrue(all.contains("Валидация"), "validation-блок должен быть эміттнут")
        // onStageStart зовётся перед каждой стадией — фикс per-stage спиннера
        assertTrue(stagesSeen.contains(TaskStage.EXECUTION))
        assertTrue(stagesSeen.contains(TaskStage.VALIDATION))
        assertEquals(TaskStage.DONE, agent.getTaskState()?.stage)
    }

    @Test
    fun `DONE is terminal - confirmation says already finished`() = runTest {
        val llm = stageAwareLlm()
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")
        orch.handleUserInput("да")   // → EXECUTION
        orch.handleUserInput("да")   // → VALIDATION
        orch.handleUserInput("да")   // → DONE
        val out = orch.handleUserInput("да")!!   // уже DONE

        assertEquals(TaskStage.DONE, agent.getTaskState()?.stage)
        assertTrue(out.contains("уже завершена") || out.contains("завершена"))
    }

    /**
     * LLM, маршрутизируемый по сигнатуре промпта стадии:
     *  - классификатор → PLANNING
     *  - planning (просит «пошаговый план») → план
     *  - execution (упоминает «реализуй») → реализация
     *  - validation (просит «Проверь ... PASS») → PASS-вердикт
     *  - done (просит «Подведи итог») → summary
     */
    private fun stageAwareLlm(): LlmClient = mockk {
        coEvery { chat(any()) } answers {
            val req = firstArg<ChatRequest>()
            val last = req.messages.lastOrNull()?.content.orEmpty()
            val content = when {
                last.contains("ответь строго", ignoreCase = true) -> "PLANNING"
                last.contains("Проверь", ignoreCase = true) -> "Всё реализовано. PASS"
                last.contains("Подведи итог", ignoreCase = true) -> "Задача выполнена."
                else -> "1) Setup\n2) Impl"
            }
            LlmResult.Success(fakeResponse(content))
        }
    }

    // ── День 15, доп. п.2: гибридное подтверждение (isYes + ConfirmationClassifier) ──

    /**
     * LLM с явным управлением confirmation-классификатором: [confirmation] возвращается на запрос
     * подтверждения (промпт содержит «CONFIRM, REFINE»), [classifierContent] — на entry-классификатор,
     * [stageContent] — на стадийные вызовы.
     */
    private fun confirmationRoutingLlm(
        confirmation: String,
        classifierContent: String = "PLANNING",
        stageContent: String = "1) Setup\n2) Impl"
    ): LlmClient = mockk {
        coEvery { chat(any()) } answers {
            val req = firstArg<ChatRequest>()
            val last = req.messages.lastOrNull()?.content.orEmpty()
            val content = when {
                last.contains("CONFIRM, REFINE", ignoreCase = true) -> confirmation
                last.contains("ответь строго", ignoreCase = true) -> classifierContent
                else -> stageContent
            }
            LlmResult.Success(fakeResponse(content))
        }
    }

    @Test
    fun `natural-language confirmation via classifier advances to next stage`() = runTest {
        // «согласен, продолжаем» — не ловится быстрым путём isYes → LLM-классификатор → CONFIRM → advance
        val llm = confirmationRoutingLlm(confirmation = "CONFIRM")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")          // PLANNING, awaitingAdvance=true
        orch.handleUserInput("согласен, продолжаем")

        assertEquals(TaskStage.EXECUTION, agent.getTaskState()?.stage)
    }

    @Test
    fun `conditional reply classified as REFINE stays on stage as feedback`() = runTest {
        // «да, но без тестов» — оговорка: классификатор → REFINE → доработка, стадия не меняется
        val llm = confirmationRoutingLlm(confirmation = "REFINE")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")          // PLANNING
        orch.handleUserInput("да, но без тестов")

        assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)
        assertEquals(true, agent.getTaskState()?.awaitingAdvance)
    }

    @Test
    fun `classifier not consulted when isYes fast path matches`() = runTest {
        // «да» ловится isYes → LLM-классификатор подтверждения не должен вызываться.
        // Если бы он вызывался и возвращал REFINE — перехода бы не было; проверяем переход.
        val llm = confirmationRoutingLlm(confirmation = "REFINE")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")
        orch.handleUserInput("да")

        assertEquals(TaskStage.EXECUTION, agent.getTaskState()?.stage)
    }

    @Test
    fun `classifier ambiguity treated as non-confirm - stays on stage`() = runTest {
        val llm = confirmationRoutingLlm(confirmation = "AMBIGUOUS")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(agent, llm, "m")

        orch.startTask("сделай калькулятор")
        orch.handleUserInput("ну не знаю")

        assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)
    }

    // ── Обработка LLM-ошибок (таймаут/HTTP): сбой не трактуется как готовый артефакт ──

    @Test
    fun `LLM error during stage yields error result without advancing or persisting artifact`() = runTest {
        // Классификатор → PLANNING (нужно для startTask); stage-вызов падает с LlmCallException.
        val llm = routingLlm(classifierContent = "PLANNING", stageContent = "irrelevant")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        // chat-делегат имитирует сбой LLM (как ContextAwareAgent.chat на LlmResult.Error).
        val orch = TaskOrchestrator(
            agent, llm, "m",
            chat = { _ -> throw LlmCallException(0, "Request timeout has expired") }
        )

        val out = orch.startTask("сделай калькулятор")
        val state = agent.getTaskState()

        // Стадия осталась PLANNING (не avanzed), артефакт НЕ персистился (план null),
        // переход не предлагается.
        assertEquals(TaskStage.PLANNING, state?.stage)
        assertEquals(null, state?.approvedPlan)
        assertEquals(false, state?.awaitingAdvance)
        assertTrue(out.contains("Ошибка запроса к LLM"), "должен показать ошибку: $out")
        assertFalse(out.contains("Перейти к стадии"), "не должен предлагать переход: $out")
    }

    @Test
    fun `LLM error on handleUserInput keeps stage and lets user retry via feedback`() = runTest {
        // Старт проходит нормально (план готов); затем повторный запуск стадии падает.
        var fail = false
        val llm = routingLlm(classifierContent = "PLANNING", stageContent = "1) Setup\n2) Impl")
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val orch = TaskOrchestrator(
            agent, llm, "m",
            chat = { _ -> if (fail) throw LlmCallException(0, "boom") else "1) Setup\n2) Impl" }
        )

        orch.startTask("сделай калькулятор")          // PLANNING, план готов
        val beforePlan = agent.getTaskState()!!.approvedPlan
        fail = true
        val out = orch.handleUserInput("перепиши план")!!   // feedback → перегенерация → сбой
        val state = agent.getTaskState()

        assertEquals(TaskStage.PLANNING, state?.stage)
        assertEquals(false, state?.awaitingAdvance)
        // Прежний план сохранён (не затёрт мусором/ null при сбое)
        assertEquals(beforePlan, state?.approvedPlan)
        assertTrue(out.contains("Ошибка запроса к LLM"))
    }
}
