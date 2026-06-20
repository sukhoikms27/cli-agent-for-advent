package com.cliagent.state

import kotlinx.serialization.Serializable

/**
 * Режим взаимодействия для стадийного потока задачи (день 15, доп. п.3).
 *
 * Управляет степенью автоматизации жизненного цикла (clarify→plan→execute→validate→done) — как
 * hot-key режимы в Claude Code. Хранится в [com.cliagent.memory.WorkingMemory.interactionMode]
 * (per-chat, персистится, default [PLAN]).
 *
 * - [MANUAL] — свободный текст = обычный чат; FSM только через явный `/task start`/`next`/`set`.
 *   Авто-роутинг интента отключён. Полный контроль пользователя.
 * - [PLAN]   — текущее поведение (день 13): stage-поток с подтверждением каждого перехода
 *   (`awaitingAdvance`; «да» = переход). Авто-роутинг активен (QUESTION→чат, TASK→автостарт).
 * - [AUTO]   — полная автоматизация: переходы без подтверждения (авто-advance после готовности
 *   артефакта). Авто-роутинг активен. Пользователь наблюдает прогресс через StageAnnouncer (п.2).
 *
 * Во ВСЕХ режимах `TransitionGuard` соблюдается: перепрыгивание этапа блокируется (AUTO не даёт
 * обойти контролируемые переходы — только автоматизирует подтверждение).
 */
@Serializable
enum class InteractionMode { MANUAL, PLAN, AUTO }
