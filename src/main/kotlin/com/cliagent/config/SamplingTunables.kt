package com.cliagent.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Sampling-параметры LLM основного цикла (cross-provider). Часть [AppConfig.sampling] — persistent
 * defaults в config.json, переопределяемые per-invocation CLI-флагами (`--top-p`, `--top-k`, …) в
 * [com.cliagent.cli.ChatCommand.buildSession]. День 31: вынос sampling в отдельный блок вместо плоских
 * полей [AppConfig] (группировка, симметрия с [com.cliagent.rag.RagConfig]).
 *
 * **Schema evolution** (AGENTS.md): все поля nullable + default `null` → старые config.json грузятся
 * без ошибок (поле `sampling` отсутствует → default `SamplingTunables()` → все поля null → прежнее
 * поведение дней 1–30, провайдерские дефолты). Новые поля добавлять только nullable.
 *
 * Семантика null: «config не диктует значение — пусть решает CLI-флаг или провайдерский дефолт».
 * Если поле non-null в config → это persistent default; CLI-флаг (тоже nullable) перекрывает его.
 * Итоговый priority: CLI-флаг > config.sampling.field > null (провайдерский дефолт в wire).
 *
 * @param temperature сэмплинг-температура (0.0–2.0). null → провайдерский дефолт.
 * @param topP nucleus sampling (0.0–1.0). null → не отправляется.
 * @param topK top-k ограничение (K наиболее вероятных токенов). null → не отправляется. Кросс-провайдерно.
 * @param maxTokens лимит output-токенов. null → провайдерский дефолт (или [com.cliagent.llm.token.OutputBudget]).
 * @param seed детерминизм (одинаковый seed → одинаковый output при тех же параметрах). null → случайно.
 * @param stop stop-sequences (завершают генерацию при появлении). null/empty → не отправляется.
 * @param frequencyPenalty штраф за повторение токенов (по частоте). null → не отправляется.
 * @param presencePenalty штраф за повторение токенов (по присутствию). null → не отправляется.
 * @param ollamaOptions Ollama escape-hatch: произвольные native `options` (num_ctx, repeat_penalty,
 *   mirostat, …) как Map<String, JsonElement>. null → не отправляется. Читается только Ollama-провайдером.
 * @param keepAlive Ollama: сколько держать модель в VRAM после ответа (`"5m"`, `"0"`). null → Ollama default.
 * @param think Ollama thinking-режим (qwen3/deepseek-r1). null → поведение модели по умолчанию.
 */
@Serializable
data class SamplingTunables(
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val seed: Long? = null,
    val stop: List<String>? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    /** Ollama native options escape-hatch (см. [com.cliagent.llm.model.ChatRequest.options]). */
    val ollamaOptions: Map<String, JsonElement>? = null,
    @SerialName("keep_alive") val keepAlive: String? = null,
    val think: Boolean? = null,
)
