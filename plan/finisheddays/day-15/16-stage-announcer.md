# Задача 16. `StageAnnouncer` — уведомления по стадиям (п.2)

## Цель
Структурированные уведомления о каждой стадии жизненного цикла: заголовок стадии, готовность
артефакта, переход, вердикт. Пользователь видит прогресс и принятые решения даже в авто-режиме (без
явного подтверждения каждого перехода).

## Зависимости
`TaskStage`, `StageResult` (день 13, доработка). Семантика стадий — из `StagePromptTemplates`.

## Файл (новый)
`src/main/kotlin/com/cliagent/cli/StageAnnouncer.kt`

## Что реализовать

```kotlin
package com.cliagent.cli

import com.cliagent.agent.stage.StageResult
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

    /**
     * Заголовок начала стадии: `⚙️ Реализация` (когда stage-агент начинает работу).
     * Показывается ПЕРЕД [StageResult.display].
     */
    fun stageHeader(stage: TaskStage): String =
        "${stageEmoji(stage)} **${stageName(stage)}**"

    /**
     * Уведомление о готовности артефакта: `📦 Реализация готова`.
     * Показывается ПОСЛЕ [StageResult.display], когда [StageResult.readyToAdvance] = true.
     */
    fun artifactReady(stage: TaskStage): String =
        "📦 ${artifactName(stage)} готов(а)"

    /**
     * Уведомление о неготовности (артефакт требует доработки): `⏳ Требуется доработка`.
     * Показывается ПОСЛЕ [StageResult.display], когда [StageResult.readyToAdvance] = false.
     */
    fun artifactNeedsWork(stage: TaskStage): String =
        "⏳ ${artifactName(stage)} требует доработки — уточните текстом"

    /**
     * Уведомление о переходе: `⏭ → Реализация`.
     * Показывается при авто-advance (AUTO-режим) или перед запросом подтверждения (PLAN-режим).
     */
    fun transitionTo(toStage: TaskStage): String =
        "⏭ → **${stageName(toStage)}**"

    /**
     * Уведомление о терминальной блокировке перехода (из [com.cliagent.state.TransitionOutcome]).
     * `⛔ Нельзя перейти: …`.
     */
    fun blocked(reason: String): String =
        "⛔ $reason"

    /**
     * Полный блок уведомления для одного stage-шага: заголовок + (display артефакта) + статус.
     *
     * @param stage       текущая стадия
     * @param display     содержимое артефакта ([StageResult.display]) — будет между заголовком и статусом
     * @param readyToAdvance готов ли артефакт к переходу
     * @return markdown-строка для AppTerminal.markdown
     */
    fun stageBlock(stage: TaskStage, display: String, readyToAdvance: Boolean): String = buildString {
        appendLine(stageHeader(stage))
        appendLine()
        appendLine(display)
        appendLine()
        append(if (readyToAdvance) artifactReady(stage) else artifactNeedsWork(stage))
    }

    /**
     * Полный блок с предложением перехода (PLAN-режим): stage-блок + «Перейти к …?».
     */
    fun stageBlockWithPrompt(
        stage: TaskStage,
        display: String,
        readyToAdvance: Boolean,
        nextStage: TaskStage?
    ): String = buildString {
        append(stageBlock(stage, display, readyToAdvance))
        if (readyToAdvance && nextStage != null) {
            appendLine()
            appendLine()
            append("___")
            appendLine()
            append("✅ Перейти к стадии **${stageName(nextStage)}**? " +
                "Ответьте **да** — продолжить, либо напишите уточнение для доработки.")
        }
    }
}
```

## Ключевые инварианты
- **`object`, чистые функции** — форматирование без состояния; тестируется тривиально (ввод → строка).
- **Не заменяет `StageResult.display`** — артефакт (план/реализация/вердикт) показывается полностью;
  announcer добавляет структурные префиксы/суффиксы.
- **Эмодзи-маркеры** — визуальная навигация (📋 план, ⚙️ реализация, 🔍 валидация). Консистентны с
  `StagePromptTemplates`-семантикой (clarify=вопросы, planning=план, execution=код, validation=проверка).
- **`stageBlockWithPrompt`** — замена хардкоженого prompt-блока в `TaskOrchestrator.runStageAndDisplay`
  (строки 152–158). Выносим форматирование из оркестратора в announcer (разделение ответственности).
- **Обычный чат не затрагивается** — announcer используется только в stage-потоке (оркестратор) и при
  переходах (`TransitionOutcome`). Простой ответ на вопрос (`IntentClassifier.QUESTION`) идёт без
  announcer.

## Решения
- **`object` в `cli/`** — форматирование вывода — ответственность CLI-слоя. Оркестратор
  (`agent/stage/`) не должен знать про эмодзи/markdown — он отдаёт `StageResult`, CLI решает, как
  рендерить. Это уважает слоистость.
- **Человекочитаемые русские имена** — пользователь видит «Планирование», не «PLANNING». Консистентно
  с русскоязычным UX REPL («Thinking…» / «да» — продолжить).
- **Эмодзи для быстрой визуальной навигации** — не обязательны, но улучшают читаемость в терминале с
  поддержкой UTF-8. `AppTerminal` уже использует эмодзи (`✓`, `⛔`, `⚠️`).

## Критерии готовности
- `StageAnnouncer` с методами `stageName`/`stageEmoji`/`artifactName`/`stageHeader`/`artifactReady`/
  `artifactNeedsWork`/`transitionTo`/`blocked`/`stageBlock`/`stageBlockWithPrompt`.
- Все 5 стадий покрыты человекочитаемыми именами.
- `stageBlockWithPrompt` заменяет хардкоженый prompt-блок оркестратора (задача 17).

## Зависимости (задачи)
Используется в 17 (orchestrator + ChatCommand рендеринг). Демо E в 25.
