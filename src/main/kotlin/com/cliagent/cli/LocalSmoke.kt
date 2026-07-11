package com.cliagent.cli

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.github.ajalt.mordant.table.table
import kotlinx.coroutines.CancellationException

/**
 * День 26: smoke-тест локальной LLM через команду `/local smoke`. Прогоняет 3 репрезентативных
 * промпта **напрямую** через [LlmClient.chat] (без агента, без history, без tools) — как
 * [com.cliagent.llm.BenchmarkRunner]. Цель: быстро убедиться, что модель отвечает на арифметику,
 * reasoning и code, с метриками latency/токенов. НЕ пишет в history сессии.
 *
 * Промпты покрывают 3 категории:
 *  1. Арифметика/факт (детерминированный ответ).
 *  2. Reasoning (объяснение концепции).
 *  3. Code (генерация функции).
 *
 * @param client LLM-клиент (обычно временный [com.cliagent.llm.OpenAiCompatibleClient] на
 *   `http://localhost:11434/v1`, либо session.client если уже local).
 * @param model  имя модели для [ChatRequest.model].
 */
object LocalSmoke {

    /** Результат одного smoke-промпта. */
    data class SmokeResult(
        val prompt: String,
        val response: String,
        val responseTimeMs: Long,
        val promptTokens: Int,
        val completionTokens: Int,
    )

    /** Три репрезентативных промпта: арифметика, reasoning, code. */
    private val prompts = listOf(
        "Сколько будет 17 умножить на 23? Ответь только числом.",
        "Объясни принцип инкапсуляции в ООП в 2-3 предложениях.",
        "Напиши функцию на Kotlin, которая проверяет, является ли строка палиндромом. Только код, без объяснений.",
    )

    /**
     * Прогоняет все 3 промпта через прямой [client.chat]. Печатает (через [AppTerminal]) номер,
     * промпт, ответ, latency, usage tokens для каждого; в конце — сводную mordant-таблицу.
     *
     * `CancellationException` не глотается (AGENTS.md) — пробрасывается, прерывая прогон.
     * Ошибки LLM (LlmResult.Error) фиксируются в [SmokeResult] как response="ERROR: ...", метрики 0;
     * прогон продолжается, чтобы показать сводку по всем промптам.
     *
     * @return список из 3 результатов (по порядку промптов).
     */
    suspend fun runSmoke(client: LlmClient, model: String): List<SmokeResult> {
        val results = mutableListOf<SmokeResult>()
        prompts.forEachIndexed { index, prompt ->
            AppTerminal.println()
            AppTerminal.println("─".repeat(60))
            AppTerminal.println("[${index + 1}/${prompts.size}] Prompt:")
            AppTerminal.println("  $prompt")
            val request = ChatRequest(
                model = model,
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                temperature = 0.0,
                // День 26: thinking-модели (qwen3:14b) тратят токены на внутренний reasoning ДО ответа.
                // Без явного max_tokens Ollama обрывает рано → content пустой. 2048 хватает на thinking
                // + ответ для коротких smoke-промптов (qwen3: "hello" → 185 completion tokens).
                maxTokens = 2048,
            )
            val start = System.currentTimeMillis()
            val result = try {
                client.chat(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val elapsed = System.currentTimeMillis() - start
                AppTerminal.err("Запрос упал: ${e.message}")
                val r = SmokeResult(
                    prompt = prompt,
                    response = "ERROR: ${e.message}",
                    responseTimeMs = elapsed,
                    promptTokens = 0,
                    completionTokens = 0,
                )
                results.add(r)
                return@forEachIndexed
            }
            val elapsed = System.currentTimeMillis() - start
            val sr = when (result) {
                is LlmResult.Success -> {
                    val usage = result.data.usage
                    val text = result.data.choices.firstOrNull()?.message?.content ?: "(empty)"
                    AppTerminal.println("Response (${elapsed}ms):")
                    AppTerminal.markdown(text)
                    AppTerminal.println(
                        "Tokens: prompt=${usage?.promptTokens ?: 0}, " +
                            "completion=${usage?.completionTokens ?: 0}, " +
                            "total=${usage?.totalTokens ?: 0}"
                    )
                    SmokeResult(
                        prompt = prompt,
                        response = text,
                        responseTimeMs = elapsed,
                        promptTokens = usage?.promptTokens ?: 0,
                        completionTokens = usage?.completionTokens ?: 0,
                    )
                }
                is LlmResult.Error -> {
                    AppTerminal.err("LLM error (code=${result.code}): ${result.message}")
                    SmokeResult(
                        prompt = prompt,
                        response = "ERROR: ${result.message}",
                        responseTimeMs = elapsed,
                        promptTokens = 0,
                        completionTokens = 0,
                    )
                }
            }
            results.add(sr)
        }

        // Сводная таблица
        AppTerminal.println()
        val totalMs = results.sumOf { it.responseTimeMs }
        val totalPrompt = results.sumOf { it.promptTokens }
        val totalCompletion = results.sumOf { it.completionTokens }
        val summary = table {
            captionTop("🧪 Local Smoke — $model")
            header { style(bold = true); row("#", "Latency", "Prompt", "Completion", "Status") }
            body {
                results.forEachIndexed { i, r ->
                    val status = if (r.response.startsWith("ERROR")) "✗" else "✓"
                    row(
                        "${i + 1}",
                        "${r.responseTimeMs}ms",
                        "${r.promptTokens}",
                        "${r.completionTokens}",
                        status,
                    )
                }
            }
            footer {
                row("TOTAL", "${totalMs}ms", "$totalPrompt", "$totalCompletion", "${results.size} prompts")
            }
        }
        AppTerminal.println(summary)
        return results
    }
}
