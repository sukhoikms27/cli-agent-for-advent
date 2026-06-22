# Задача 12. `StatefulAgent` — полная сборка stateful-агента (Этап B)

## Цель
Decorator-композер над `ContextAwareAgent`, реализующий интерфейс `Agent` и объединяющий три столпа
Недели 3: профиль (день 12) + состояние (день 13) + инварианты (день 14). Именованная сущность сборки
вместо анонимного wiring в `ChatCommand`. Закрывает extension point global-plan 4.5.

## Зависимости
`Agent` (интерфейс), `ContextAwareAgent` (база), `InvariantGuard`/`InvariantChecker` (день 14,
используются через композицию — не трогаются, их 9 тестов остаются зелёными).

## Файл (новый)
`src/main/kotlin/com/cliagent/agent/StatefulAgent.kt`

## Что реализовать

```kotlin
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
 * 2. Единое место для будущих слоёв (e.g. `ActingAgent` из file-operations extension, memory,
 *    reasoning strategy) — композиция decorators через `StatefulAgent.next(decorator)`.
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
```

## Ключевые инварианты
- **`chatAgent` вычисляется один раз в конструкторе** — `val`, не `fun`. Если `checker != null` —
  guard; иначе `base`. Это совпадает с текущим wiring в `ChatCommand.kt:117-121`.
- **`InvariantGuard` не модифицируется** — используется как готовый компонент. Его контракт (отказ
  без LLM при Violated-запросе, retry×3 при Violated-ответе, fallback) сохраняется полностью.
  9 тестов `InvariantGuardTest` остаются зелёными.
- **`chat()` делегирует в `chatAgent`** — единая точка. Оркестратор (задача 14) передаёт
  `{ msg -> statefulAgent.chat(msg) }` как `chat`-провайдер → stage-вызовы идут через защищённый путь.
- **`contextAware` expose** — намеренное решение (см. KDoc): аксессоры профиля/стейта/инвариантов
  специфичны для `ContextAwareAgent`, не интерфейса `Agent`. DRY через expose, а не дублирование.

## Поток
```
StatefulAgent(base, checker, provider):
  chatAgent = checker? InvariantGuard(base, checker, provider) : base

chat(msg) → chatAgent.chat(msg)
  ├─ checker==null → base.chat(msg)                          [прозрачно]
  └─ checker!=null → InvariantGuard.chat(msg)                [инварианты]
                      ├─ пусто → base.chat(msg)              [fast-path]
                      ├─ request Violated → ⛔ без LLM
                      ├─ response Valid → ответ
                      └─ response Violated → retry×3 → fallback

getHistory()/reset() → chatAgent.getHistory()/reset()        [делегирование]

contextAware → base                                           [аксессоры для CLI/orchestrator]
```

## Решения
- **Decorator, не наследование** — `ContextAwareAgent` и его 61+ тест не трогаются (Open-Closed).
  `StatefulAgent` оборачивает, тестируется изолированно (mock base).
- **Checker как `?` (opt-in)** — сохраняет текущее поведение без `--invariants` (ноль накладных).
  С `--invariants` — guard активен. Конвенция `chatAgent` = ternary в конструкторе, как сейчас.
- **`contextAware` публичный getter** — единственная «утечка» абстракции; оправдана, т.к. оркестратор
  и CLI управляют состоянием (это их ответственность, не интерфейса `Agent`). Альтернатива —
  расширить `Agent` аксессорами — отвергнута (утяжеляет контракт для всех реализаций).
- **Не вводит новый интерфейс** (`StatefulAgentInterface`) — избыточно для одной реализации. Если
  появятся альтернативные stateful-агенты, тогда рефакторинг.

## Критерии готовности
- `StatefulAgent : Agent` реализует `chat`/`getHistory`/`reset`.
- С `checker != null` — `chat` идёт через `InvariantGuard`; с `null` — прозрачный делегат.
- `contextAware` возвращает `base` для аксессоров.
- `InvariantGuard` не изменён, его тесты зелёные.

## Зависимости (задачи)
Тестируется в 13. Wiring в 15. Используется оркестратором в 14 (через `chat`-провайдер).
