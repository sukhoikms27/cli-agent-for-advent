package com.cliagent.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Ollama-специфичные настройки, применяемые только [com.cliagent.llm.OllamaNativeClient]. Часть
 * [AppConfig.ollama] — persistent defaults в config.json. День 31: вынесено из [SamplingTunables],
 * т.к. эти поля семантически привязаны к native Ollama wire-формату (`/api/chat`), а не к кросс-
 * провайдерному sampling. Для cloud-провайдеров (z.ai / OpenAI-compat) поле игнорируется.
 *
 * **Schema evolution** (AGENTS.md): все поля nullable + default `null` → старые config.json грузятся
 * без ошибок (поле `ollama` отсутствует → default `OllamaTunables()` → все поля null → прежнее
 * поведение дней 1–30, Ollama-дефолты wire). Новые поля добавлять только nullable.
 *
 * @param keepAlive сколько держать модель загруженной в VRAM после ответа (`"5m"`, `"10m"`, `"0"` для
 *   немедленной выгрузки). null → Ollama default (5m). Полезно для `/local` команд: `"0"` освобождает
 *   VRAM после каждого запроса (медленно, но экономит память), `"30m"` — модель остаётся горячей.
 * @param think включить thinking-режим для reasoning-моделей (qwen3, deepseek-r1). Response содержит
 *   отдельное поле `thinking` (internal reasoning) помимо `content`. null → поведение модели по умолчанию.
 * @param options произвольные native Ollama `options` (escape-hatch для параметров без явного поля:
 *   `num_ctx`, `repeat_penalty`, `mirostat`, `top_k` и пр.). null → не отправляется.
 */
@Serializable
data class OllamaTunables(
    @SerialName("keep_alive") val keepAlive: String? = null,
    val think: Boolean? = null,
    val options: Map<String, JsonElement>? = null,
)
