package com.cliagent.llm

import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String
) : LlmClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 120_000
        }
    }

    override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            // Read raw body text for safe deserialization
            val bodyText = response.bodyAsText()
            try {
                val chatResponse = json.decodeFromString<ChatResponse>(bodyText)
                LlmResult.Success(chatResponse)
            } catch (e: Exception) {
                System.err.println("[DEBUG] Raw API response:\n$bodyText")
                LlmResult.Error(0, "Failed to parse response: ${e.message}")
            }
        } catch (e: ClientRequestException) {
            val statusCode = e.response.status.value
            val errorBody = runCatching { e.response.bodyAsText() }.getOrDefault("")
            val message = when (statusCode) {
                401 -> "Invalid API key. Check CLI_AGENT_API_KEY environment variable."
                429 -> "Rate limit exceeded. Try again later."
                else -> "Client error: $errorBody"
            }
            LlmResult.Error(statusCode, message)
        } catch (e: ServerResponseException) {
            val statusCode = e.response.status.value
            LlmResult.Error(statusCode, "Server error: ${e.response.bodyAsText()}")
        } catch (e: Exception) {
            LlmResult.Error(0, "Request failed: ${e.message}")
        }
    }
}
