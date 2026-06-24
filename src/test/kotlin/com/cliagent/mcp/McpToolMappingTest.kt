package com.cliagent.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** День 17: маппинг McpTool → OpenAI ToolDefinition (inputSchema-строка → parameters JsonElement). */
class McpToolMappingTest {

    @Test
    fun `maps name description and parameters from inputSchema`() {
        val schema = """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"}},"required":["owner","repo"]}"""
        val td = McpTool(name = "get_repo", description = "GitHub repo metadata", inputSchema = schema)
            .toToolDefinition()

        assertEquals("function", td.type)
        assertEquals("get_repo", td.function.name)
        assertEquals("GitHub repo metadata", td.function.description)

        val params = td.function.parameters
        assertNotNull(params)
        assertTrue(params is JsonObject)
        val obj = params as JsonObject
        assertNotNull(obj["properties"])
        assertNotNull(obj["required"])
        val required = (obj["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }
        assertEquals(listOf("owner", "repo"), required)
    }

    @Test
    fun `null inputSchema yields null parameters`() {
        val td = McpTool(name = "t", description = null, inputSchema = null).toToolDefinition()
        assertEquals("t", td.function.name)
        assertNull(td.function.description)
        assertNull(td.function.parameters)
    }

    @Test
    fun `malformed inputSchema yields null parameters without throwing`() {
        val td = McpTool(name = "t", description = "d", inputSchema = "not-json{").toToolDefinition()
        assertEquals("t", td.function.name)
        assertNull(td.function.parameters)   // runCatching → null, не падает
    }
}
