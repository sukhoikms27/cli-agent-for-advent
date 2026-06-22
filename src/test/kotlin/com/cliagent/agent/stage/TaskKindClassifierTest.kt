package com.cliagent.agent.stage

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import com.cliagent.state.TaskKind
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TaskKindClassifierTest {

    private fun fakeResponse(content: String): ChatResponse = ChatResponse(
        id = "resp",
        choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = content))),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
    )

    private fun llmReturning(content: String): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Success(fakeResponse(content))
    }

    private fun errorLlm(): LlmClient = mockk {
        coEvery { chat(any()) } returns LlmResult.Error(code = 500, message = "boom")
    }

    @Test
    fun `classifies CODE for programming task`() = runTest {
        val classifier = TaskKindClassifier(llmReturning("CODE"), "m")
        assertEquals(TaskKind.CODE, classifier.classify("реализуй функцию калькулятора на Kotlin"))
    }

    @Test
    fun `classifies REASONING for logical task`() = runTest {
        val classifier = TaskKindClassifier(llmReturning("REASONING"), "m")
        assertEquals(TaskKind.REASONING, classifier.classify("реши логическую задачу про волка и козу"))
    }

    @Test
    fun `parses kind even with surrounding prose`() = runTest {
        val classifier = TaskKindClassifier(llmReturning("Ответ: WRITING"), "m")
        assertEquals(TaskKind.WRITING, classifier.classify("напиши статью"))
    }

    @Test
    fun `returns null on LLM error - does not fall back to CODE`() = runTest {
        // Ключевой инвариант фикса #1: при сбое классификации НЕ форсируем CODE
        val classifier = TaskKindClassifier(errorLlm(), "m")
        assertNull(classifier.classify("реши задачу"))
    }

    @Test
    fun `returns null on unparseable response`() = runTest {
        val classifier = TaskKindClassifier(llmReturning("не уверен"), "m")
        assertNull(classifier.classify("что-то"))
    }

    @Test
    fun `returns null on blank input`() = runTest {
        val classifier = TaskKindClassifier(llmReturning("CODE"), "m")
        assertNull(classifier.classify("   "))
    }
}
