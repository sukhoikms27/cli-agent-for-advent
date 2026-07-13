package com.cliagent.llm.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 31: сериализация новых nullable-полей [ChatRequest] (top_k, options, keep_alive, think).
 *
 * Контракт (AGENTS.md): единый Json с `explicitNulls=false` → null-поля НЕ пишутся в wire
 * (backward-compat дней 1–30; провайдеры не получают неожиданных null-ключей). Non-null → пишутся
 * с правильными wire-именами (`top_k`, `keep_alive` через @SerialName; `options`/`think` as-is).
 */
class ChatRequestSerializationTest {

    /** Production-shape Json: тот же набор флагов, что у клиентских Json (AGENTS.md «единый Json»). */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun emptyRequest() = ChatRequest(model = "m", messages = emptyList())

    @Test
    fun `null top_k options keep_alive think are omitted from wire`() {
        val req = emptyRequest()   // все 4 поля = null (default)
        val s = json.encodeToString(ChatRequest.serializer(), req)
        // explicitNulls=false → null-поля не пишутся. backward-compat: wire идентичен дням 1–30.
        assertFalse(s.contains("\"top_k\""), "top_k must be omitted when null: $s")
        assertFalse(s.contains("\"options\""), "options must be omitted when null: $s")
        assertFalse(s.contains("\"keep_alive\""), "keep_alive must be omitted when null: $s")
        assertFalse(s.contains("\"think\""), "think must be omitted when null: $s")
    }

    @Test
    fun `non-null top_k serializes with snake_case wire name`() {
        val req = emptyRequest().copy(topK = 40)
        val s = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue(s.contains("\"top_k\":40"), "top_k must serialize with snake_case name: $s")
    }

    @Test
    fun `non-null options serializes as json element`() {
        val opts: JsonObject = buildJsonObject {
            put("num_ctx", 8192)
            put("repeat_penalty", 1.1)
        }
        val req = emptyRequest().copy(options = opts)
        val s = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue(s.contains("\"options\""), "options must serialize: $s")
        assertTrue(s.contains("\"num_ctx\":8192"), "nested num_ctx must appear: $s")
        assertTrue(s.contains("\"repeat_penalty\":1.1"), "nested repeat_penalty must appear: $s")
    }

    @Test
    fun `non-null keep_alive serializes with snake_case wire name`() {
        val req = emptyRequest().copy(keepAlive = "5m")
        val s = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue(s.contains("\"keep_alive\":\"5m\""), "keep_alive must serialize as snake_case string: $s")
    }

    @Test
    fun `non-null think serializes as boolean`() {
        val req = emptyRequest().copy(think = true)
        val s = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue(s.contains("\"think\":true"), "think must serialize as boolean: $s")
    }

    @Test
    fun `all four new fields together serialize correctly`() {
        val opts = buildJsonObject { put("num_ctx", 4096) }
        val req = emptyRequest().copy(topK = 40, options = opts, keepAlive = "10m", think = true)
        val s = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue(s.contains("\"top_k\":40"))
        assertTrue(s.contains("\"options\""))
        assertTrue(s.contains("\"keep_alive\":\"10m\""))
        assertTrue(s.contains("\"think\":true"))
    }

    @Test
    fun `options accepts JsonPrimitive too (scalar escape-hatch)`() {
        // options declared as JsonElement (not JsonObject) — scalar values are valid JSON elements.
        val req = emptyRequest().copy(options = JsonPrimitive("scalar"))
        val s = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue(s.contains("\"options\":\"scalar\""), "options as JsonPrimitive should serialize: $s")
    }
}
