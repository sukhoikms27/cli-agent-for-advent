package com.cliagent.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * День 29: unit-тесты [ModelDefaultsRegistry]. Зеркалирует coverage [ModelLimitsTest] (exact-match,
 * prefix-match, DEFAULT fallback, case-insensitive). Тuned-значения захардкожены в ассертах —
 * регрессия на случай случайного изменения (особенно numCtx=32K для qwen3:14b RAG).
 */
class ModelDefaultsTest {

    @Test
    fun `qwen3 14b exact match returns RAG-tuned values`() {
        val d = ModelDefaultsRegistry.forModel("qwen3:14b")
        assertEquals(0.3, d.temperature, "temperature: factual sweet spot для RAG")
        assertEquals(0.9, d.topP, "top_p nucleus sampling")
        assertEquals(40, d.topK, "top_k")
        assertEquals(1.1, d.repeatPenalty, "repeat_penalty анти-зацикливание 14B")
        assertEquals(32_768, d.numCtx, "numCtx: 32K вмещает RAG-context (server-default ~4096 режет)")
        assertEquals(2_048, d.numPredict, "numPredict")
        assertEquals("30m", d.keepAlive, "keepAlive: покрывает типичную dev-сессию")
    }

    @Test
    fun `qwen3 7b prefix match resolves to generic qwen3 entry`() {
        // "qwen3:7b" нет в registry как точная запись → prefix "qwen3" (те же tuned defaults).
        val d = ModelDefaultsRegistry.forModel("qwen3:7b")
        assertEquals(0.3, d.temperature)
        assertEquals(40, d.topK)
        assertEquals(32_768, d.numCtx, "generic qwen3 — те же numCtx=32K, что и qwen3:14b")
        assertEquals("30m", d.keepAlive)
    }

    @Test
    fun `qwen2 5 with tag prefix matches qwen2 5 entry`() {
        // "qwen2.5:32b-instruct-q5_K_M" → prefix "qwen2.5".
        val d = ModelDefaultsRegistry.forModel("qwen2.5:32b-instruct-q5_K_M")
        assertEquals(0.3, d.temperature)
        assertEquals(40, d.topK)
        assertEquals(32_768, d.numCtx)
        assertEquals("30m", d.keepAlive)
    }

    @Test
    fun `unknown model falls back to DEFAULT with numCtx 8192`() {
        // Неизвестная модель → DEFAULT (консервативный numCtx=8K, не завышаем VRAM).
        val d = ModelDefaultsRegistry.forModel("llama3.1:70b")
        assertEquals(0.3, d.temperature, "DEFAULT sampling те же, что у Qwen-семейства")
        assertEquals(40, d.topK)
        assertEquals(8_192, d.numCtx, "DEFAULT numCtx=8192 (безопасно для неизвестного context-window)")
        assertEquals(2_048, d.numPredict)
        assertEquals("30m", d.keepAlive)
    }

    @Test
    fun `forModel is case insensitive`() {
        // "QWEN3:14B" → точное совпадение (lowercase) → tuned entry.
        val upper = ModelDefaultsRegistry.forModel("QWEN3:14B")
        val lower = ModelDefaultsRegistry.forModel("qwen3:14b")
        assertEquals(lower.temperature, upper.temperature)
        assertEquals(lower.numCtx, upper.numCtx)
        assertEquals(lower.keepAlive, upper.keepAlive)
        // Prefix-match тоже регистронезависимый.
        val mixed = ModelDefaultsRegistry.forModel("Qwen3:7b")
        assertEquals(0.3, mixed.temperature)
        assertEquals(32_768, mixed.numCtx)
    }

    @Test
    fun `formatCompact lists only non-null fields with native wire keys`() {
        val d = ModelDefaultsRegistry.forModel("qwen3:14b")
        val compact = d.formatCompact()
        // Порядок фиксирован: temp, top_p, top_k, repeat_penalty, num_ctx, num_predict, keep_alive.
        assertEquals(
            "temp=0.3 top_p=0.9 top_k=40 repeat_penalty=1.1 num_ctx=32768 num_predict=2048 keep_alive=30m",
            compact,
        )
    }

    @Test
    fun `formatCompact omits null fields`() {
        val d = ModelDefaults(temperature = 0.5, numCtx = 4_096)
        assertEquals("temp=0.5 num_ctx=4096", d.formatCompact())
    }

    @Test
    fun `DEFAULT is non-null across sampling fields`() {
        // DEFAULT должен иметь заполненные sampling-поля (не всё null) — иначе fallback бесполезен.
        val d = ModelDefaultsRegistry.DEFAULT
        assertNotNull(d.temperature)
        assertNotNull(d.topK)
        assertNotNull(d.numCtx)
        assertNotNull(d.keepAlive)
    }
}
