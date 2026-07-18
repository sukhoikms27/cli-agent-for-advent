package com.cliagent.cli

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.agent.StatefulAgent
import com.cliagent.agent.ToolExecutor
import com.cliagent.agent.stage.IntentClassifier
import com.cliagent.agent.stage.TaskOrchestrator
import com.cliagent.context.ContextManager
import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmProvider
import com.cliagent.rag.RagRetriever
import com.cliagent.state.invariant.LlmInvariantChecker

/**
 * День 26: именованный holder всего, что строится в [ChatCommand.buildSession]. Раньше wiring был
 * размазан по локальным `val` в `run()` (:110-251) — REPL-loop замыкался на них напрямую. С приходом
 * команды `/local` (live-switch cloud ↔ Ollama без рестарта) сессию нужно уметь **пересоздавать**:
 * закрыть старый toolExecutor, пересобрать агента/оркестратора/RAG на новом client+model. Локальные
 * `val` так пересоздать нельзя — нужен holder.
 *
 * [AgentSession] группирует references, но **не владеет** всеми ресурсами:
 *  - [toolExecutor] — persistent MCP-соединение, закрывается в [close] (как в прежнем finally).
 *  - [ragEmbedder] намеренно **ВНЕ** сессии (shared across switches, живёт в `run()` scope),
 *    поэтому [close] его НЕ трогает — его закрывает внешний `finally`.
 *
 * @param client          LLM-клиент текущего провайдера (cloud z.ai / local Ollama). Меняется на switch.
 * @param resolvedProvider дискриминатор для отображения в баннере/UI.
 * @param model           effective-модель (из flag/config) — нужна для метрик/баннера.
 * @param config          снапшот [com.cliagent.config.AppConfig], из которого собрана сессия. Хранится
 *   чтобы `/local off` мог восстановить cloud без повторной загрузки config (env перебил бы overlay).
 * @param agent           stateful ContextAwareAgent — основная точка чата/команд.
 * @param statefulAgent   декоратор с инвариантами (оркестратор/RAG берут chat-делегат отсюда).
 * @param orchestrator    stage-поток (FSM) — `/task`, авто-определение интента.
 * @param intentClassifier авто-определение QUESTION vs TASK (свободный текст).
 * @param ragCommands     обработчик `/rag` (eval, index, toggle). Зависит от agent + retriever.
 * @param ragRetriever    RAG-retrieval (top-K чанков). Shared HttpClient у embedder'а — вне сессии.
 * @param contextManager  стратегия контекста (sliding/facts/summary/branch).
 * @param checker         LLM-judge инвариантов (opt-in, null без --invariants).
 * @param toolExecutor    MCP tool-executor (null = tools off). Закрывается в [close].
 * @param maxToolRounds   лимит раундов tool-use loop (для баннера/отчёта).
 * @param mcpServerCount  кол-во активных MCP-серверов (для баннера).
 */
internal data class AgentSession(
    val client: LlmClient,
    val resolvedProvider: LlmProvider,
    val model: String,
    val config: com.cliagent.config.AppConfig,
    val agent: ContextAwareAgent,
    val statefulAgent: StatefulAgent,
    val orchestrator: TaskOrchestrator,
    val intentClassifier: IntentClassifier,
    val ragCommands: RagCommands,
    val ragRetriever: RagRetriever,
    val contextManager: ContextManager,
    val checker: LlmInvariantChecker?,
    val toolExecutor: ToolExecutor?,
    val maxToolRounds: Int,
    val mcpServerCount: Int,
) {
    /**
     * Закрывает ресурсы, принадлежащие **этой** сессии: только [toolExecutor] (persistent MCP).
     * RAG-embedder **НЕ закрываем** — он shared across switches (создаётся один раз в `run()` и
     * передаётся в каждый [buildSession]). Его закрывает внешний finally при выходе из REPL.
     *
     * Ошибки закрытия глотаем (runCatching) — switch не должен ронять REPL из-за утечки в MCP.
     */
    suspend fun close() {
        runCatching { toolExecutor?.close() }
    }
}
