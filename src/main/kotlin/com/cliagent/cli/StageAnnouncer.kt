package com.cliagent.cli

import com.cliagent.agent.stage.StageResult
import com.cliagent.state.InteractionMode
import com.cliagent.state.TaskStage

/**
 * Форматирование структурных уведомлений о ходе стадийного потока (день 15, доп. п.2).
 *
 * В автоматическом режиме (особенно AUTO) пользователь не подтверждает каждый переход — но должен
 * видеть прогресс: какая стадия выполняется, какой артефакт готов, какой переход произошёл, какой
 * вердикт. Иначе «чёрный ящик»: агент долго думает, пользователь не понимает, что происходит.
 *
 * Не заменяет [StageResult.display] (содержимое артефакта — план/реализация/вердикт), а добавляет
 * структурные префиксы-уведомления ВОКРУГ него. Обычный чат (не stage-поток) не затрагивается.
 *
 * Источник метаданных — [StageResult] (artifact, readyToAdvance) + [TaskStage]. Человекочитаемые
 * имена стадий отражают семантику [com.cliagent.llm.model.StagePromptTemplates].
 */
object StageAnnouncer {

    /** Человекочитаемое имя стадии для уведомлений. */
    fun stageName(stage: TaskStage): String = when (stage) {
        TaskStage.CLARIFY -> "Уточнение требований"
        TaskStage.PLANNING -> "Планирование"
        TaskStage.EXECUTION -> "Реализация"
        TaskStage.VALIDATION -> "Валидация"
        TaskStage.DONE -> "Завершение"
    }

    /** Эмодзи-маркер стадии. */
    fun stageEmoji(stage: TaskStage): String = when (stage) {
        TaskStage.CLARIFY -> "❓"
        TaskStage.PLANNING -> "📋"
        TaskStage.EXECUTION -> "⚙️"
        TaskStage.VALIDATION -> "🔍"
        TaskStage.DONE -> "🏁"
    }

    /** Название артефакта стадии (что производится). */
    fun artifactName(stage: TaskStage): String = when (stage) {
        TaskStage.CLARIFY -> "Уточнённые требования"
        TaskStage.PLANNING -> "Утверждённый план"
        TaskStage.EXECUTION -> "Реализация"
        TaskStage.VALIDATION -> "Вердикт"
        TaskStage.DONE -> "Итог"
    }

    /** Заголовок начала стадии: `⚙️ Реализация`. Показывается ПЕРЕД [StageResult.display]. */
    fun stageHeader(stage: TaskStage): String =
        "${stageEmoji(stage)} **${stageName(stage)}**"

    /** Уведомление о готовности артефакта: `📦 Реализация готова`. */
    fun artifactReady(stage: TaskStage): String =
        "📦 ${artifactName(stage)} готов(а)"

    /** Уведомление о неготовности (артефакт требует доработки): `⏳ Требуется доработка`. */
    fun artifactNeedsWork(stage: TaskStage): String =
        "⏳ ${artifactName(stage)} требует доработки — уточните текстом"

    /** Уведомление о переходе: `⏭ → Реализация`. */
    fun transitionTo(toStage: TaskStage): String =
        "⏭ → **${stageName(toStage)}**"

    /** Уведомление о блокировке перехода. */
    fun blocked(reason: String): String = "⛔ $reason"

    /**
     * Полный блок уведомления для одного stage-шага: заголовок + (display артефакта) + статус.
     * В PLAN-режиме добавляет предложение перехода; в AUTO — уведомление о переходе без вопроса.
     */
    fun stageBlock(
        stage: TaskStage,
        display: String,
        readyToAdvance: Boolean,
        nextStage: TaskStage?,
        mode: InteractionMode = InteractionMode.PLAN
    ): String = buildString {
        appendLine(stageHeader(stage))
        appendLine()
        appendLine(display)
        appendLine()
        append(if (readyToAdvance) artifactReady(stage) else artifactNeedsWork(stage))
        if (readyToAdvance && nextStage != null && stage != TaskStage.DONE) {
            appendLine()
            appendLine()
            if (mode == InteractionMode.AUTO) {
                append(transitionTo(nextStage))
            } else {
                append("✅ Перейти к стадии **${stageName(nextStage)}**? " +
                    "Ответьте **да** — продолжить, либо напишите уточнение для доработки.")
            }
        }
    }
}
