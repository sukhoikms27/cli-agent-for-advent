package com.cliagent.agent

/**
 * День 34 (stage/swarm интеграция): типобезопасная абстракция для подтверждения опасных операций.
 *
 * Заменяет разрозненные callback'и (например `confirmWrite: (suspend (String, String) -> Boolean)?`
 * в [FileToolExecutor]) единым интерфейсом с явными реализациями для разных контекстов.
 *
 * ## Зачем
 * Опасные операции (write_file, delete_file, git_commit, run_command) встречаются в разных
 * executor'ах. Каждая из них требует подтверждения, но способ подтверждения зависит от контекста:
 *  - **TTY/REPL** — y/N prompt с preview (человек видит, что подтверждает).
 *  - **CI/batch** — либо авто-одобрение (если доверяем), либо fail-safe отказ (небезопасно в слепую).
 *  - **Stage/FSM** — если план утверждён на PLANNING-стадии, входящие в план write-операции могут
 *    выполняться без повторного подтверждения (Plan-approved gate).
 *  - **Тесты** — детерминированный gate (всегда true/false) без I/O.
 *
 * ## Масштабируемость
 * Новая опасная операция = новый `op`-string (`"write_file"`, `"delete_file"`, `"git_commit"`).
 * Реализация gate'а решает по `op` и `args`, одобрить или нет. Не нужно менять контракт executor'а
 * или вводить новый callback-тип.
 *
 * ## Контракт
 * - `approve` никогда не бросает (возвращает false при любой ошибке ввода-вывода/таймаута).
 * - `CancellationException` пробрасывается (конвенция AGENTS.md).
 */
interface DangerousOpGate {
    /**
     * @param op  идентификатор операции: `"write_file"`, `"delete_file"`, `"git_commit"`, ...
     * @param args аргументы операции (для preview/логирования): path, content, и т.д.
     * @return true — операция одобрена; false — отклонена (executor вернёт диагностику).
     */
    suspend fun approve(op: String, args: Map<String, Any?>): Boolean
}

/**
 * Gate для не-интерактивного контекста: одобряет ВСЕ опасные операции без вопроса.
 *
 * **Небезопасен** — используйте только когда вы понимаете, что делает агент, и доверяете ему
 * (например: CI pipeline с уже утверждённым контентом, sandbox где write не может навредить).
 * В REPL/продакшене — предпочитайте [TtyGate] или [PlanApprovedGate].
 */
class AutoApproveGate : DangerousOpGate {
    override suspend fun approve(op: String, args: Map<String, Any?>): Boolean = true
}

/**
 * Fail-safe gate: отклоняет ВСЕ опасные операции. Default в незнакомом контексте.
 *
 * Например: batch-режим без явного `--auto-approve`, или swarm-worker без прав на write
 * (только lead имеет полномочия). Гарантия: ни одна опасная операция не пройдёт.
 */
class RejectAllGate : DangerousOpGate {
    override suspend fun approve(op: String, args: Map<String, Any?>): Boolean = false
}

/**
 * Gate для REPL/TTY: делегирует подтверждение произвольной suspend-функции (обычно y/N prompt
 * с preview). Это адаптер к существующим UI-функциям вроде `confirmWritePrompt(path, content)`.
 *
 * @param confirm реализация prompt'а: получает `op` и `args`, возвращает решение пользователя.
 *   Для UI может рендерить preview по `args["content"]`/`args["path"]`, для простого y/N — игнорировать.
 */
class TtyGate(
    private val confirm: suspend (op: String, args: Map<String, Any?>) -> Boolean,
) : DangerousOpGate {
    override suspend fun approve(op: String, args: Map<String, Any?>): Boolean = confirm(op, args)
}

/**
 * Gate для stage/FSM: одобряет операции, явно перечисленные в утверждённом плане.
 *
 * На PLANNING-стадии формируется план («обнови README.md»), пользователь его утверждает.
 * Дальше на EXECUTION write_file на `README.md` выполняется без повторного подтверждения —
 * потому что оно уже в плане. Запись в непредусмотренные файлы — отклоняется (или fallback
 * на [next] gate для интерактивного уточнения).
 *
 * @param approvedOps множество одобренных операций в формате `"op:path"` (например
 *   `"write_file:README.md"`). Можно использовать wildcard `"write_file:*"` для «любой write».
 * @param next fallback-gate для операций вне [approvedOps]: null = отклонять, иначе спросить.
 *   В REPL сюда передаётся [TtyGate], в CI — null (отклонение).
 */
class PlanApprovedGate(
    private val approvedOps: Set<String>,
    private val next: DangerousOpGate? = null,
) : DangerousOpGate {

    override suspend fun approve(op: String, args: Map<String, Any?>): Boolean {
        // Точный матч "op:path"
        val path = (args["path"] as? String)?.let { ":$it" }.orEmpty()
        val specific = "$op$path"
        val wildcard = "$op:*"
        if (specific in approvedOps || wildcard in approvedOps) return true
        return next?.approve(op, args) ?: false
    }
}
