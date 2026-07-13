package com.cliagent.cli

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 31: clikt-парсинг новых sampling-флагов [ChatCommand] (`--top-p`, `--top-k`, `--seed`,
 * `--stop`, `--frequency-penalty`, `--presence-penalty`). Паттерн AGENTS.md: `cmd.parse(...)` +
 * assert на свойствах / ошибках валидации.
 *
 * `parse()` только разбирает аргументы (НЕ вызывает `run()` — там config/network), поэтому тесты
 * безопасны без моков. Поведенческая проверка (sampling-поля попадают в [com.cliagent.llm.model.ChatRequest])
 * — в [com.cliagent.agent.ContextAwareAgentSamplingTest].
 */
class ChatCommandSamplingTest {

    private val cmd = ChatCommand()

    @Test
    fun `parse accepts all sampling flags together without error`() {
        // Все 7 sampling-флагов распознаются clikt и принимают корректные типы (double/int/long/str).
        assertDoesNotThrow {
            cmd.parse(listOf(
                "--top-p", "0.9",
                "--top-k", "40",
                "--seed", "42",
                "--temperature", "0.5",
                "--stop", "END,DONE",
                "--frequency-penalty", "0.3",
                "--presence-penalty", "0.2",
            ))
        }
    }

    @Test
    fun `parse accepts sampling flags alongside legacy flags`() {
        // Семейсво sampling-флагов не конфликтует с legacy (-m, -s, --context).
        assertDoesNotThrow {
            cmd.parse(listOf("-m", "qwen3:14b", "--top-p", "0.9", "-s", "direct", "--top-k", "40"))
        }
    }

    @Test
    fun `parse with no sampling flags succeeds (all nullable, optional)`() {
        // Все sampling-флаги nullable — запуск без них валиден (config/провайдер решают).
        assertDoesNotThrow { cmd.parse(emptyList()) }
    }

    @Test
    fun `parse rejects non-integer top-k`() {
        // clikt int()-тип должен отклонять не-числовые значения (BadParameterValue — subtype UsageError).
        val ex = assertThrows(BadParameterValue::class.java) {
            cmd.parse(listOf("--top-k", "not-a-number"))
        }
        // clikt-format message: "not-a-number is not a valid integer / --top-k". Проверяем, что имя
        // опции присутствует (paramName non-null или сообщение ссылается на top-k — без учёта регистра).
        val refersToTopK = ex.paramName?.contains("top-k", ignoreCase = true) == true ||
            ex.message?.contains("top-k", ignoreCase = true) == true ||
            ex.message?.contains("top_k", ignoreCase = true) == true
        assertTrue(refersToTopK, "error should reference --top-k; got: ${ex.message} / ${ex.paramName}")
    }

    @Test
    fun `parse rejects non-numeric top-p`() {
        assertThrows(BadParameterValue::class.java) {
            cmd.parse(listOf("--top-p", "abc"))
        }
    }

    @Test
    fun `parse rejects non-long seed`() {
        assertThrows(BadParameterValue::class.java) {
            cmd.parse(listOf("--seed", "xyz"))
        }
    }
}
