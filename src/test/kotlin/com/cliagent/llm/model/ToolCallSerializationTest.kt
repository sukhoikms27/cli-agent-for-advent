package com.cliagent.llm.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 17: сериализация function-calling. Запрос с tools сериализуется; ответ с tool_calls
 * (и content:null) парсится — null-coercion content→"" (см. OpenAiCompatibleClient coerceInputValues).
 */
class ToolCallSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `ChatRequest with tools serializes tools and tool_choice`() {
        val req = ChatRequest(
            model = "m",
            messages = emptyList(),
            tools = listOf(ToolDefinition(function = FunctionDef(name = "get_repo", description = "d"))),
            toolChoice = "auto"
        )
        val s = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue(s.contains("\"tools\""), "expected tools field: $s")
        assertTrue(s.contains("\"get_repo\""), "expected tool name: $s")
        assertTrue(s.contains("\"tool_choice\":\"auto\""), "expected tool_choice: $s")
    }

    @Test
    fun `ChatRequest without tools omits tools field`() {
        val req = ChatRequest(model = "m", messages = emptyList())
        val s = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue(!s.contains("\"tools\""), "tools should be omitted when null: $s")
    }

    @Test
    fun `response with tool_calls and null content parses`() {
        val resp = """
            {"id":"r","choices":[{"index":0,
              "message":{"role":"assistant","content":null,
                "tool_calls":[{"id":"call_1","type":"function",
                  "function":{"name":"get_repo","arguments":"{\"owner\":\"X\"}"}}]},
              "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimIndent()
        val parsed = json.decodeFromString<ChatResponse>(resp)
        val msg = parsed.choices.first().message
        assertEquals("assistant", msg.role)
        assertEquals("", msg.content)   // null coerced to "" (content non-null with default)
        assertEquals(1, msg.toolCalls?.size)
        val call = msg.toolCalls!!.first()
        assertEquals("call_1", call.id)
        assertEquals("get_repo", call.function.name)
        assertEquals("""{"owner":"X"}""", call.function.arguments)
    }

    /**
     * Регрессия error 1214 "Tool type cannot be empty" (z.ai strict-валидация): каждая запись
     * `tool_calls` в эхо-возвращаемой assistant-истории обязана содержать `type:"function"`.
     * Без поля `type` в [ToolCall] агент получал 1214 на втором запросе (history с tool_calls).
     */
    @Test
    fun `assistant tool_calls serialize with type function`() {
        val msg = ChatMessage(
            role = "assistant",
            content = "",
            toolCalls = listOf(
                ToolCall(id = "call_1", function = ToolCallFunction(name = "get_repo", arguments = "{}"))
            )
        )
        val s = json.encodeToString(ChatMessage.serializer(), msg)
        assertTrue(s.contains("\"tool_calls\""), "expected tool_calls: $s")
        assertTrue(s.contains("\"type\":\"function\""), "each tool_call must have type=function (z.ai 1214): $s")
    }
}
