package com.cliagent.cli

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.agent.StatefulAgent
import com.cliagent.agent.stage.IntentClassifier
import com.cliagent.agent.stage.TaskOrchestrator
import com.cliagent.agent.stage.UserIntent
import com.cliagent.llm.LlmClient
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
import com.cliagent.state.TaskState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты диспетчеризации свободного текста в REPL (день 15) — через извлечённый шов
 * [ChatCommand.dispatchFreeText]. Доказывает, что REPL-логика тестируема без мока терминала:
 * DONE-reset, авто-определение интента, автостарт FSM, AUTO-чейнинг, режимы.
 *
 * IntentClassifier мочится напрямую (не нужно маршрутизировать LLM под интент); оркестратор/агент
 * — реальные классы на стаб-LlmClient (паттерн TaskOrchestratorTest), чтобы пройти реальный FSM.
 */
class ChatCommandFreeTextTest {

    private val cmd = ChatCommand()

    private fun fakeResponse(content: String): ChatResponse = ChatResponse(
        id = "resp",
        choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = content))),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
    )

    /**
     * LLM, маршрутизируемый по сигнатуре промпта стадии (как stageAwareLlm в TaskOrchestratorTest),
     * плюс ordinary-chat ветка. IntentClassifier здесь не участвует — он мочится в тестах.
     */
    private fun stageAwareLlm(): LlmClient = mockk {
        coEvery { chat(any()) } answers {
            val req = firstArg<ChatRequest>()
            val last = req.messages.lastOrNull()?.content.orEmpty()
            val content = when {
                last.contains("ответь строго", ignoreCase = true) -> "PLANNING"      // entry classifier
                last.contains("CODE, REASONING", ignoreCase = true) -> "CODE"        // taskKind classifier
                last.contains("Проверь", ignoreCase = true) -> "Всё ок. PASS"        // validation
                last.contains("Подведи итог", ignoreCase = true) -> "Задача выполнена." // done
                last.contains("пошаговый план", ignoreCase = true) -> "1) Setup\n2) Impl" // planning
                last.contains("Текущий шаг", ignoreCase = true) -> "step-impl"       // execution step
                else -> "chat-answer"                                                // ordinary chat
            }
            LlmResult.Success(fakeResponse(content))
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

    /** Собирает полноценную тройку агент+stateful+оркестратор на стаб-LLM (как в проде, но без сети). */
    private fun deps(llm: LlmClient = stageAwareLlm()): Triple<ContextAwareAgent, StatefulAgent, TaskOrchestrator> {
        val agent = ContextAwareAgent(llm, storeMock(), "m", "chat-1")
        val stateful = StatefulAgent(agent, checker = null) { agent.getInvariants() }
        val orch = TaskOrchestrator(agent, llm, "m", chat = { stateful.chat(it) })
        return Triple(agent, stateful, orch)
    }

    private fun intent(returning: UserIntent): IntentClassifier = mockk {
        coEvery { classify(any()) } returns returning
    }

    @Test
    fun `DONE task in PLAN mode restarts on new TASK input`() = runTest {
        val (agent, stateful, orch) = deps()
        // Завершённая задача + режим PLAN
        agent.setWorkingMemory(WorkingMemory(interactionMode = InteractionMode.PLAN))
        agent.setTaskState(TaskState(stage = TaskStage.DONE, currentStep = "старая задача"))

        val out = cmd.dispatchFreeText("новая задача", agent, stateful, orch, intent(UserIntent.TASK))

        val state = agent.getTaskState()
        // Старая DONE сброшена, стартовала новая задача (PLAN — стоп на PLANNING)
        assertEquals(TaskStage.PLANNING, state?.stage)
        assertEquals("новая задача", state?.currentStep)
        assertNotNull(state?.approvedPlan)
        assertNotNull(out)
    }

    @Test
    fun `DONE task in AUTO mode with QUESTION resets FSM and answers in ordinary chat`() = runTest {
        val (agent, stateful, orch) = deps()
        agent.setWorkingMemory(WorkingMemory(interactionMode = InteractionMode.AUTO))
        agent.setTaskState(TaskState(stage = TaskStage.DONE, currentStep = "старая задача"))

        val out = cmd.dispatchFreeText("что такое X?", agent, stateful, orch, intent(UserIntent.QUESTION))

        // FSM сброшен → обычный чат с дефолтным system-промптом (не DONE)
        assertEquals(null, agent.getTaskState())
        assertEquals("chat-answer", out)
    }

    @Test
    fun `no active task in PLAN mode with TASK intent autostarts FSM`() = runTest {
        val (agent, stateful, orch) = deps()
        val classifier = intent(UserIntent.TASK)

        val out = cmd.dispatchFreeText("сделай калькулятор", agent, stateful, orch, classifier)

        val state = agent.getTaskState()
        assertEquals(TaskStage.PLANNING, state?.stage)
        assertNotNull(state?.approvedPlan)
        assertTrue(out!!.contains("План"))
        coVerify(exactly = 1) { classifier.classify(any()) }
    }

    @Test
    fun `active PLANNING task with yes in PLAN advances to EXECUTION without intent classification`() = runTest {
        val (agent, stateful, orch) = deps()
        val classifier = intent(UserIntent.TASK)   // не должно зваться
        agent.setWorkingMemory(WorkingMemory(interactionMode = InteractionMode.PLAN))
        orch.startTask("сделай калькулятор", InteractionMode.PLAN)   // → PLANNING, awaitingAdvance

        val out = cmd.dispatchFreeText("да", agent, stateful, orch, classifier)

        assertEquals(TaskStage.EXECUTION, agent.getTaskState()?.stage)
        assertTrue(out!!.contains("Реализация"))
        coVerify(exactly = 0) { classifier.classify(any()) }
    }

    @Test
    fun `AUTO mode auto-advances from awaitingAdvance and chains to DONE without confirmation`() = runTest {
        val (agent, stateful, orch) = deps()
        // Старт в PLAN — стоп на PLANNING (awaitingAdvance); затем переключаем в AUTO
        agent.setWorkingMemory(WorkingMemory(interactionMode = InteractionMode.PLAN))
        orch.startTask("сделай калькулятор", InteractionMode.PLAN)
        assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)
        agent.setWorkingMemory(
            (agent.getWorkingMemory() ?: WorkingMemory()).copy(interactionMode = InteractionMode.AUTO)
        )

        val out = cmd.dispatchFreeText("любой текст", agent, stateful, orch, intent(UserIntent.TASK))

        // AUTO сам доехал до DONE; промежуточные результаты есть; подтверждения перехода — нет
        assertEquals(TaskStage.DONE, agent.getTaskState()?.stage)
        assertTrue(out!!.contains("Реализация"), "AUTO должен показать артефакт execution")
        assertFalse(out.contains("Перейти к стадии"), "AUTO не должен спрашивать подтверждение")
        assertTrue(out.contains("⏭ →"), "AUTO должен показывать переходы между стадиями")
    }
}
