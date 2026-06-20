package com.cliagent.agent

import com.cliagent.llm.model.ChatMessage
import com.cliagent.state.invariant.Invariant
import com.cliagent.state.invariant.InvariantChecker

/**
 * Полная сборка stateful-агента (день 15, global-plan 4.5).
 *
 * Decorator поверх [ContextAwareAgent], реализующий [Agent] и объединяющий три столпа Недели 3:
 * профиль (день 12) + состояние задачи (день 13) + инварианты (день 14). Именованная сущность
 * сборки вместо анонимного wiring в `ChatCommand`.
 *
 * Внутри использует [InvariantGuard] через композицию (opt-in): если передан [checker], оборачивает
 * базового агента в guard; иначе работает как прозрачный делегат. Сам [InvariantGuard] не меняется —
 * его поведение (отказ запроса-нарушителя без LLM, retry ответа-нарушителя) сохраняется полностью.
 *
 * **Зачем отдельный класс (а не `val chatAgent = if (...) InvariantGuard(...) else agent` в CLI):**
 * 1. Именованная точка сборки stateful-агента — тестируется изолированно, документируется.
 * 2. Единое место для будущих слоёв (e.g. `ActingAgent` из file-operations extension).
 * 3. Закрывает gap «stage-поток идёт через `base.chat`, минуя инварианты»: оркестратор (задача 14)
 *    получает `chat`-провайдер от `StatefulAgent.chat`, а не от голого `ContextAwareAgent` — значит,
 *    инварианты проверяются и на stage-LLM-вызовах.
 *
 * @param base        базовый stateful-агент с аксессорами (профиль/стейт/инварианты)
 * @param checker     опциональный LLM-judge инвариантов; null — инварианты не проверяются (текущее
 *                    поведение без `--invariants`)
 * @param invariantsProvider колбэк-провайдер списка инвариантов (из `base.getInvariants()`)
 */
class StatefulAgent(
    private val base: ContextAwareAgent,
    checker: InvariantChecker?,
    private val invariantsProvider: suspend () -> List<Invariant>
) : Agent {

    /** Внутренний чат-агент: с инвариант-проверкой (opt-in) или прозрачный делегат. */
    private val chatAgent: Agent =
        if (checker != null) InvariantGuard(base, checker, invariantsProvider) else base

    /**
     * Чат через защищённый путь (инварианты, если включены). Это единая точка LLM-чата, которую
     * оркестратор использует как `chat`-провайдер для stage-вызовов → инварианты покрывают stage-flow.
     */
    override suspend fun chat(userMessage: String): String = chatAgent.chat(userMessage)

    override suspend fun getHistory(): List<ChatMessage> = chatAgent.getHistory()

    override suspend fun reset() = chatAgent.reset()

    /**
     * Доступ к базовому [ContextAwareAgent] для аксессоров профиля/состояния/инвариантов.
     *
     * Нужен оркестратору и CLI: они вызывают `getTaskState`/`setTaskState`/`attemptTransition`/
     * `getProfile`/`getInvariants` — это не часть интерфейса `Agent`, а специфичные аксессоры
     * `ContextAwareAgent`. Без expose пришлось бы дублировать их в `StatefulAgent` или расширять
     * интерфейс `Agent` (нежелательно — утяжеляет контракт).
     */
    val contextAware: ContextAwareAgent get() = base
}
