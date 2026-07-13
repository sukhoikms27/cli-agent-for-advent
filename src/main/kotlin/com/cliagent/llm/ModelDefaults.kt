package com.cliagent.llm

/**
 * День 29: tuned defaults для локальных моделей. Применяются ВНУТРИ [OllamaNativeClient.toWire()]
 * как fallback, когда соответствующее поле [com.cliagent.llm.model.ChatRequest] == null (т.е. не
 * задано явно через CLI flag или config.sampling). Это держит provider-агностику агента:
 * [com.cliagent.agent.ContextAwareAgent] не знает про эти defaults — они резолвятся в wire-слое
 * Ollama, симметрично тому, как [ModelLimitsRegistry] инкапсулирует знание о context-окнах.
 *
 * Приоритет: explicit CLI flag > explicit config.sampling.* > ModelDefaults.forModel(model) > null.
 *
 * @param temperature 0.3 — factual sweet spot для 14B RAG (0.7 слишком креативно → галлюцинации).
 * @param topP nucleus sampling потолок (0.9).
 * @param topK top-K наиболее вероятных токенов (40).
 * @param repeatPenalty анти-зацикливание 14B — главная беда локальных моделей (1.1; 1.2 уже ломает
 *   валидные повторы в коде). НЕ путать с OpenAI frequency_penalty (диапазон -2..2) — это разные
 *   параметры; Ollama использует llama.cpp repeat_penalty (диапазон ~0.5..2.0, дефолт 1.1).
 * @param numCtx окно контекста для KV-cache (КРИТИЧНО: server-default Ollama ~4096 молчаливо режет
 *   RAG-context; 32K = нативный 40960 qwen3:14b с запасом RAM, KV-cache ~5-6GB при Q4_K_M).
 * @param numPredict потолок output-токенов на ответ (95% ответов < 2048; registry ceiling
 *   [ModelLimitsRegistry.maxOutput] = 8192 остаётся хард-пределом, но runtime-дефолт ниже → быстрее
 *   и меньше RAM).
 * @param keepAlive сколько держать модель в VRAM после ответа ("30m" покрывает типичную dev-сессию;
 *   -1 = вечно не делаем default — постоянное потребление RAM).
 *
 * Симметричен [ModelLimitsRegistry]: exact-match → prefix-match → DEFAULT.
 */
data class ModelDefaults(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val repeatPenalty: Double? = null,
    val numCtx: Int? = null,
    val numPredict: Int? = null,
    val keepAlive: String? = null,
) {
    /**
     * Compact-представление non-null полей для баннера [com.cliagent.cli.ChatCommand.printBanner]:
     * `temp=0.3 top_k=40 num_ctx=32768 keep_alive=30m`. null-поля опускаются (compact wire-вид).
     * Имена полей соответствуют native Ollama wire-ключам (top_k, num_ctx, keep_alive) — пользователь
     * видит те же имена, что в `/api/chat` options.
     */
    fun formatCompact(): String = buildList {
        temperature?.let { add("temp=$it") }
        topP?.let { add("top_p=$it") }
        topK?.let { add("top_k=$it") }
        repeatPenalty?.let { add("repeat_penalty=$it") }
        numCtx?.let { add("num_ctx=$it") }
        numPredict?.let { add("num_predict=$it") }
        keepAlive?.let { add("keep_alive=$it") }
    }.joinToString(" ")
}

/**
 * Реестр tuned defaults по моделям. День 29: provider-специфичное знание (Ollama-локальные модели),
 * инкапсулированное отдельно от cross-provider [com.cliagent.config.SamplingTunables] — чтобы
 * [com.cliagent.agent.ContextAwareAgent] оставался provider-агностиком.
 *
 * Симметричен [ModelLimitsRegistry]: exact-match → prefix (самая длинная запись-ключ) → DEFAULT.
 */
object ModelDefaultsRegistry {
    private val registry = mapOf(
        // qwen3:14b — основной кейс курса (M3 Pro / 36GB, RAG + code help). Точная запись —
        // forModel("qwen3:14b") резолвится сюда (exact match приоритетнее prefix "qwen3").
        "qwen3:14b" to ModelDefaults(
            temperature = 0.3,
            topP = 0.9,
            topK = 40,
            repeatPenalty = 1.1,
            numCtx = 32_768,
            numPredict = 2_048,
            keepAlive = "30m",
        ),
        // generic qwen3 (7b/32b) — те же разумные defaults, пригодные для семейства.
        "qwen3" to ModelDefaults(
            temperature = 0.3,
            topP = 0.9,
            topK = 40,
            repeatPenalty = 1.1,
            numCtx = 32_768,
            numPredict = 2_048,
            keepAlive = "30m",
        ),
        // qwen2.5 — те же defaults (128K context-window, но 32K numCtx достаточно для RAG + запас RAM).
        "qwen2.5" to ModelDefaults(
            temperature = 0.3,
            topP = 0.9,
            topK = 40,
            repeatPenalty = 1.1,
            numCtx = 32_768,
            numPredict = 2_048,
            keepAlive = "30m",
        ),
    )

    /**
     * Консервативный default для неизвестных моделей. numCtx=8192 (меньше RAM, чем 32K — безопасно
     * для моделей с меньшим context-window); остальные sampling-поля те же, что для Qwen-семейства.
     */
    val DEFAULT = ModelDefaults(
        temperature = 0.3,
        topP = 0.9,
        topK = 40,
        repeatPenalty = 1.1,
        numCtx = 8_192,
        numPredict = 2_048,
        keepAlive = "30m",
    )

    /**
     * Defaults по modelId. Exact-match сначала, затем prefix (самая длинная запись-ключ), затем
     * DEFAULT. Регистронезависимо. Симметрично [ModelLimitsRegistry.forModel].
     */
    fun forModel(modelId: String): ModelDefaults {
        val lower = modelId.trim().lowercase()
        registry[lower]?.let { return it }
        val prefixMatch = registry.keys
            .filter { lower.startsWith(it) }
            .maxByOrNull { it.length }
        return prefixMatch?.let { registry[it] } ?: DEFAULT
    }
}
