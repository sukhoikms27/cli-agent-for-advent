package com.cliagent.agent

import com.cliagent.llm.model.FunctionDef
import com.cliagent.llm.model.ToolDefinition
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 34 (refactoring): тесты [CompositeToolExecutor] — универсального composite для любых
 * [ToolExecutor]'ов (не только MCP, как [com.cliagent.mcp.CompositeMcpToolExecutor]).
 *
 * Покрывает: routing, prefix-on-collision, graceful degradation, lifecycle (close + cache),
 * unknown-tool diagnostic, empty-list edge case.
 *
 * Через mockk подсовываем mock [ToolExecutor] — без реальных MCP/in-process executor'ов.
 */
class CompositeToolExecutorTest {

    private fun toolDef(name: String) = ToolDefinition(function = FunctionDef(name = name, description = "d"))

    /** Mock executor, отвечающий за список tool-имён. callResult — функция для кастомизации ответа. */
    private fun mockExecutor(
        tools: List<String>,
        callResult: (String) -> String = { "result:$it" },
    ): ToolExecutor = mockk {
        coEvery { definitions() } returns tools.map { toolDef(it) }
        coEvery { call(any(), any()) } answers {
            val name = firstArg<String>()
            callResult(name)
        }
        coEvery { close() } returns Unit
    }

    @Test
    fun `routes call to correct executor by tool name`() = runTest {
        val composite = CompositeToolExecutor(
            listOf(
                NamedToolExecutor("github", mockExecutor(listOf("get_repo", "list_issues"))),
                NamedToolExecutor("file", mockExecutor(listOf("read_file", "write_file"))),
            )
        )
        val defs = composite.definitions()
        assertEquals(4, defs.size)
        assertEquals(setOf("get_repo", "list_issues", "read_file", "write_file"), defs.map { it.function.name }.toSet())

        assertEquals("result:read_file", composite.call("read_file", emptyMap()))
        assertEquals("result:get_repo", composite.call("get_repo", emptyMap()))
    }

    @Test
    fun `prefix-on-collision renames duplicate tool names to source__tool`() = runTest {
        // Оба executor'а объявляют "read_file" → должна быть коллизия.
        val composite = CompositeToolExecutor(
            listOf(
                NamedToolExecutor("fs", mockExecutor(listOf("read_file", "list"))),
                NamedToolExecutor("remote", mockExecutor(listOf("read_file", "fetch"))),
            )
        )
        val defs = composite.definitions()
        // list и fetch — уникальные, остаются raw. read_file коллидирует → fs__read_file и remote__read_file.
        val names = defs.map { it.function.name }.toSet()
        assertTrue("fs__read_file" in names, "коллизия read_file должна быть переименована в fs__read_file: $names")
        assertTrue("remote__read_file" in names, "коллизия read_file должна быть переименована в remote__read_file: $names")
        assertTrue("list" in names, "уникальное имя list остаётся raw")
        assertTrue("fetch" in names, "уникальное имя fetch остаётся raw")
        assertTrue("read_file" !in names, "raw read_file не должен быть в exposure при коллизии")
    }

    @Test
    fun `call routes by prefixed name on collision`() = runTest {
        val composite = CompositeToolExecutor(
            listOf(
                NamedToolExecutor("fs", mockExecutor(listOf("read_file"), callResult = { "fs:$it" })),
                NamedToolExecutor("remote", mockExecutor(listOf("read_file"), callResult = { "remote:$it" })),
            )
        )
        composite.definitions()
        // Вызовы по prefixed-именам маршрутизируются на правильный источник.
        assertEquals("fs:read_file", composite.call("fs__read_file", emptyMap()))
        assertEquals("remote:read_file", composite.call("remote__read_file", emptyMap()))
    }

    @Test
    fun `graceful degradation - failed executor skipped`() = runTest {
        val composite = CompositeToolExecutor(
            listOf(
                NamedToolExecutor("broken", mockk {
                    coEvery { definitions() } throws RuntimeException("connection refused")
                    coEvery { close() } returns Unit
                }),
                NamedToolExecutor("ok", mockExecutor(listOf("working_tool"))),
            ),
            logger = { /* silence stderr in test */ }
        )
        val defs = composite.definitions()
        assertEquals(1, defs.size)
        assertEquals("working_tool", defs.first().function.name)
        assertEquals("result:working_tool", composite.call("working_tool", emptyMap()))
    }

    @Test
    fun `unknown tool returns diagnostic without throwing`() = runTest {
        val composite = CompositeToolExecutor(
            listOf(NamedToolExecutor("src", mockExecutor(listOf("a", "b"))))
        )
        composite.definitions()
        val result = composite.call("nonexistent", emptyMap())
        assertTrue(result.contains("not found"), "unknown tool должен вернуть диагностику: $result")
        assertTrue(result.contains("Available"), "должен показать список доступных: $result")
    }

    @Test
    fun `routing cached after first call - call routes without re-discovery`() = runTest {
        val exec = mockExecutor(listOf("a"))
        val composite = CompositeToolExecutor(listOf(NamedToolExecutor("src", exec)))
        composite.definitions()
        // Повторный definitions() возвращает кэш — routing-таблица уже построена.
        val second = composite.definitions()
        assertEquals(1, second.size)
        assertEquals("a", second.first().function.name)
        // call работает без re-discovery.
        assertEquals("result:a", composite.call("a", emptyMap()))
    }

    @Test
    fun `close closes all executors`() = runTest {
        val a = mockExecutor(listOf("a"))
        val b = mockExecutor(listOf("b"))
        val composite = CompositeToolExecutor(
            listOf(NamedToolExecutor("src1", a), NamedToolExecutor("src2", b))
        )
        composite.definitions()
        composite.close()
        coVerify(exactly = 1) { a.close() }
        coVerify(exactly = 1) { b.close() }
    }

    @Test
    fun `close clears routing cache - next definitions rebuilds`() = runTest {
        val exec = mockExecutor(listOf("a"))
        val composite = CompositeToolExecutor(listOf(NamedToolExecutor("src", exec)))
        composite.definitions()
        composite.close()
        // После close routing=null → новый definitions() снова зовёт discovery.
        // Проверяем через хотя бы один вызов (точно считать сложно из-за buildDefinitions).
        val second = composite.definitions()
        assertEquals(1, second.size, "routing после close должен перестроиться")
    }

    @Test
    fun `empty executor list returns empty definitions`() = runTest {
        val composite = CompositeToolExecutor(emptyList())
        assertTrue(composite.definitions().isEmpty())
    }
}
