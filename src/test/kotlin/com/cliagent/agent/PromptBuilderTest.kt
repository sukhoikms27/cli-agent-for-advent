package com.cliagent.agent

import com.cliagent.llm.model.ChatMessage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import com.cliagent.rag.RagChunk
import com.cliagent.rag.ScoredChunk
import com.cliagent.state.TaskStage
import com.cliagent.state.TaskState
import com.cliagent.state.invariant.Invariant
import com.cliagent.state.invariant.InvariantCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderTest {

    private val base = ChatMessage(role = "system", content = "You are a helpful assistant.")

    @Test
    fun `empty layers produce base content unchanged`() {
        val built = PromptBuilder(base, null, null).build()
        assertEquals(base.content, built.content)
    }

    @Test
    fun `empty LongTermMemory and WorkingMemory also produce base content`() {
        val built = PromptBuilder(base, LongTermMemory(), WorkingMemory()).build()
        assertEquals(base.content, built.content)
    }

    @Test
    fun `only working layer renders working block without long-term`() {
        val working = WorkingMemory(currentTask = "auth service", plan = "1) routes")
        val built = PromptBuilder(base, null, working).build()
        assertTrue(built.content.contains("[Working memory — current task]"))
        assertTrue(built.content.contains("auth service"))
        assertFalse(built.content.contains("[Long-term memory]"))
    }

    @Test
    fun `only long-term layer renders long-term block without working`() {
        val lt = LongTermMemory(knowledge = mapOf("stack" to "Kotlin"))
        val built = PromptBuilder(base, lt, null).build()
        assertTrue(built.content.contains("[Long-term memory]"))
        assertTrue(built.content.contains("Kotlin"))
        assertFalse(built.content.contains("[Working memory"))
    }

    @Test
    fun `both layers render in order base then long-term then working`() {
        val lt = LongTermMemory(knowledge = mapOf("stack" to "Kotlin"))
        val working = WorkingMemory(currentTask = "auth service")
        val built = PromptBuilder(base, lt, working).build()
        val ltIdx = built.content.indexOf("[Long-term memory]")
        val workIdx = built.content.indexOf("[Working memory")
        assertTrue(ltIdx > 0)
        assertTrue(workIdx > ltIdx)
    }

    @Test
    fun `user profile renders inside long-term block — day 12 readiness`() {
        val lt = LongTermMemory(profile = UserProfile(style = "concise", constraints = listOf("no RxJava")))
        val built = PromptBuilder(base, lt, null).build()
        assertTrue(built.content.contains("User profile:"))
        assertTrue(built.content.contains("concise"))
        assertTrue(built.content.contains("no RxJava"))
    }

    @Test
    fun `profile about field renders — day 12`() {
        val lt = LongTermMemory(profile = UserProfile(about = "backend dev, Ktor"))
        val built = PromptBuilder(base, lt, null).build()
        assertTrue(built.content.contains("About: backend dev, Ktor"))
    }

    @Test
    fun `task state renders inside working block — day 13`() {
        val working = WorkingMemory(
            taskState = TaskState(
                stage = TaskStage.EXECUTION,
                currentStep = "wire accessors",
                expectedAction = "tests pass"
            )
        )
        val built = PromptBuilder(base, null, working).build()
        assertTrue(built.content.contains("Task state:"))
        assertTrue(built.content.contains("Stage: execution"))
        assertTrue(built.content.contains("Current step: wire accessors"))
        assertTrue(built.content.contains("Expected action: tests pass"))
    }

    @Test
    fun `task state approved plan renders when set — day 13`() {
        val working = WorkingMemory(taskState = TaskState(stage = TaskStage.PLANNING, approvedPlan = "1) model 2) CLI"))
        val built = PromptBuilder(base, null, working).build()
        assertTrue(built.content.contains("Approved plan: 1) model 2) CLI"))
    }

    @Test
    fun `null task state does not render task block — day 13`() {
        val working = WorkingMemory(currentTask = "auth service")   // taskState == null
        val built = PromptBuilder(base, null, working).build()
        assertTrue(built.content.contains("auth service"))
        assertFalse(built.content.contains("Task state:"))
    }

    @Test
    fun `task state implementation and verdict render — доработка day 13`() {
        val working = WorkingMemory(
            taskState = TaskState(
                stage = TaskStage.VALIDATION,
                implementation = "Reducer + ViewModel готовы",
                verdict = "тесты зелёные"
            )
        )
        val built = PromptBuilder(base, null, working).build()
        assertTrue(built.content.contains("Implementation: Reducer + ViewModel готовы"))
        assertTrue(built.content.contains("Verdict: тесты зелёные"))
    }

    @Test
    fun `invariants render as project invariants block — day 14`() {
        val lt = LongTermMemory(
            invariants = listOf(
                Invariant("no-compose", "UI только View-based, запрещён Compose", InvariantCategory.BAN)
            )
        )
        val built = PromptBuilder(base, lt, null).build()
        assertTrue(built.content.contains("[Project invariants"))
        assertTrue(built.content.contains("MUST NOT"))
        assertTrue(built.content.contains("[no-compose] UI только View-based, запрещён Compose"))
        assertTrue(built.content.contains("(ban)"))
    }

    @Test
    fun `empty invariants do not render the block — day 14 zero regression`() {
        val lt = LongTermMemory()   // нет инвариантов
        val built = PromptBuilder(base, lt, null).build()
        assertFalse(built.content.contains("[Project invariants"))
    }

    @Test
    fun `null longTerm does not render invariants block`() {
        val built = PromptBuilder(base, null, null).build()
        assertFalse(built.content.contains("[Project invariants"))
    }

    @Test
    fun `invariants block comes after working block — day 14 ordering`() {
        val lt = LongTermMemory(invariants = listOf(Invariant("x", "rule")))
        val working = WorkingMemory(currentTask = "task")
        val built = PromptBuilder(base, lt, working).build()
        val workIdx = built.content.indexOf("[Working memory")
        val invIdx = built.content.indexOf("[Project invariants")
        assertTrue(workIdx > 0)
        assertTrue(invIdx > workIdx)   // инварианты последними (recency)
    }

    // ── День 22: блок [Retrieved context] ─────────────────────────────────────────

    @Test
    fun `null retrieved context produces base content — day 22 zero regression`() {
        // Без retrievedContext контент байт-идентичен дням 11–21 (4-й параметр по умолчанию null)
        val builtNoArg = PromptBuilder(base, null, null).build()
        val builtExplicitNull = PromptBuilder(base, null, null, null).build()
        assertEquals(builtNoArg.content, builtExplicitNull.content)
        assertFalse(builtExplicitNull.content.contains("[Retrieved context"))
    }

    @Test
    fun `empty retrieved context list does not render the block`() {
        val built = PromptBuilder(base, null, null, emptyList()).build()
        assertFalse(built.content.contains("[Retrieved context"))
    }

    @Test
    fun `retrieved context renders block with source and section`() {
        val chunks = listOf(
            ScoredChunk(chunk("c1", "SlidingWindow", "context/SlidingWindow.kt", "keep last N"), 0.9f),
            ScoredChunk(chunk("c2", "SummaryStrategy", "context/SummaryStrategy.kt", "auto-summarize"), 0.7f),
        )
        val built = PromptBuilder(base, null, null, chunks).build()
        assertTrue(built.content.contains("[Retrieved context"))
        assertTrue(built.content.contains("SlidingWindow.kt › SlidingWindow"))
        assertTrue(built.content.contains("keep last N"))
        assertTrue(built.content.contains("SummaryStrategy.kt › SummaryStrategy"))
    }

    @Test
    fun `retrieved context block is between working and invariants — day 22 ordering`() {
        val lt = LongTermMemory(invariants = listOf(Invariant("x", "rule")))
        val working = WorkingMemory(currentTask = "task")
        val chunks = listOf(ScoredChunk(chunk("c1", "S", "a.md", "text"), 0.5f))
        val built = PromptBuilder(base, lt, working, chunks).build()
        val workIdx = built.content.indexOf("[Working memory")
        val ragIdx = built.content.indexOf("[Retrieved context")
        val invIdx = built.content.indexOf("[Project invariants")
        assertTrue(workIdx > 0)
        assertTrue(ragIdx > workIdx, "retrieved after working")
        assertTrue(invIdx > ragIdx, "invariants after retrieved")
    }

    // ── День 24: усиленная инструкция retrieved-блока (цитаты, источники, «не знаю») ────

    @Test
    fun `retrieved block requires structured answer format — день 24`() {
        val chunks = listOf(ScoredChunk(chunk("c1", "SlidingWindow", "a.md", "keep last N"), 0.9f))
        val built = PromptBuilder(base, null, null, chunks).build()
        // Анти-галлюцинации: модель обязана вернуть структурированный ответ.
        assertTrue(built.content.contains("Ответ:"), "должен требовать раздел Ответ")
        assertTrue(built.content.contains("Источники:"), "должен требовать раздел Источники")
        assertTrue(built.content.contains("Цитаты:"), "должен требовать раздел Цитаты")
    }

    @Test
    fun `retrieved block includes dont-know instruction — день 24`() {
        val chunks = listOf(ScoredChunk(chunk("c1", "S", "a.md", "text"), 0.5f))
        val built = PromptBuilder(base, null, null, chunks).build()
        // Усиление задания day-24: при отсутствии ответа в чанках — «не знаю, уточните».
        assertTrue(built.content.contains("не знаю"), "должна быть инструкция «не знаю»")
        assertTrue(built.content.contains("уточните"), "должна быть просьба уточнить")
    }

    @Test
    fun `retrieved block still renders source section chunkId score — no regression`() {
        // Backward-compat: формат метаданных чанка не сломан усилением инструкции.
        val chunks = listOf(ScoredChunk(chunk("c1", "SlidingWindow", "SlidingWindow.kt", "keep last N"), 0.9f))
        val built = PromptBuilder(base, null, null, chunks).build()
        assertTrue(built.content.contains("SlidingWindow.kt › SlidingWindow"))
        assertTrue(built.content.contains("c1"))
        // score рендерится через %.3f (Locale-dependent на JVM — может быть 0.900 или 0,900);
        // проверяем наличие score-подстроки толерантно к разделителю.
        assertTrue(
            built.content.contains("score 0.900") || built.content.contains("score 0,900"),
            "score должен рендериться с 3 знаками (0.900 или 0,900 в зависимости от locale)"
        )
    }

    private fun chunk(id: String, section: String, source: String, text: String): RagChunk =
        RagChunk(
            chunkId = id, documentId = "d", source = source, title = "T", section = section,
            text = text, index = 0, tokenCount = 5,
        )
}
