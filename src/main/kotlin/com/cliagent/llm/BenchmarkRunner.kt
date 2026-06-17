package com.cliagent.llm

import com.cliagent.llm.model.BenchmarkResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ModelInfo
import com.cliagent.llm.pricing.Pricing
import kotlinx.coroutines.delay

class BenchmarkRunner(private val client: LlmClient) {

    suspend fun runBenchmark(
        models: List<ModelInfo>,
        prompt: String,
        temperature: Double = 0.0,
        delayBetweenMs: Long = 1000
    ): List<BenchmarkResult> {
        return models.mapIndexed { index, modelInfo ->
            if (index > 0) delay(delayBetweenMs)

            val request = ChatRequest(
                model = modelInfo.id,
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                temperature = temperature
            )

            val startTime = System.currentTimeMillis()
            val result = client.chat(request)
            val elapsed = System.currentTimeMillis() - startTime

            when (result) {
                is LlmResult.Success -> {
                    val usage = result.data.usage
                    BenchmarkResult(
                        modelId = modelInfo.id,
                        tier = modelInfo.tier,
                        responseText = result.data.choices.first().message.content,
                        responseTimeMs = elapsed,
                        promptTokens = usage?.promptTokens ?: 0,
                        completionTokens = usage?.completionTokens ?: 0,
                        totalTokens = usage?.totalTokens ?: 0,
                        estimatedCost = Pricing.calculateCost(modelInfo.id, usage)
                    )
                }
                is LlmResult.Error -> BenchmarkResult(
                    modelId = modelInfo.id,
                    tier = modelInfo.tier,
                    responseText = "ERROR: ${result.message}",
                    responseTimeMs = elapsed,
                    promptTokens = 0,
                    completionTokens = 0,
                    totalTokens = 0,
                    estimatedCost = null
                )
            }
        }
    }
}
