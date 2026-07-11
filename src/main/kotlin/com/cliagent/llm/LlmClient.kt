package com.cliagent.llm

import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.StreamChunk
import kotlinx.coroutines.flow.Flow

interface LlmClient {
    suspend fun chat(request: ChatRequest): LlmResult<ChatResponse>

    /**
     * День 30 (streaming SSE): серверный стриминг ответа. Токены идут по мере генерации (через
     * SSE `data:`-фреймы), без материализации всего ответа до завершения. Решает проблему thinking-
     * моделей (qwen3:14b: 40-90с «пустоты» до первого токена) — пользователь видит контент сразу.
     *
     * [request.stream] форсируется в `true` реализацией (см. [OpenAiCompatibleClient.chatStream]).
     * Возвращает cold [Flow] — выполняется только при collect. Без retry (retry после первого токена
     * = дубликат prefix в выводе caller'а); сетевые ошибки ДО первого токена идут как [StreamChunk.Error].
     *
     * Гарантии потока: эмиссия [StreamChunk.Done] ровно одна (при отсутствии явного `data: [DONE]`
     * реализация эмитит Done из finally). [kotlinx.coroutines.CancellationException] НЕ глотается
     * (AGENTS.md) — пробрасывается для отмены корутины caller'а.
     */
    fun chatStream(request: ChatRequest): Flow<StreamChunk>
}
