package com.cliagent.agent.stage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanParserTest {

    @Test
    fun `parses numbered list with parenthesis markers`() {
        val plan = """
            1) Setup Gradle project
            2) Implement Calculator class
            3) Add CLI parsing
        """.trimIndent()
        val steps = PlanParser.parse(plan)
        assertEquals(3, steps.size)
        assertEquals("Setup Gradle project", steps[0])
        assertEquals("Implement Calculator class", steps[1])
        assertEquals("Add CLI parsing", steps[2])
    }

    @Test
    fun `parses numbered list with dot markers`() {
        val plan = """
            1. Setup Gradle project
            2. Implement Calculator class
        """.trimIndent()
        val steps = PlanParser.parse(plan)
        assertEquals(2, steps.size)
    }

    @Test
    fun `parses bullet list`() {
        val plan = """
            - Setup Gradle project
            - Implement Calculator class
            * Add CLI parsing
        """.trimIndent()
        val steps = PlanParser.parse(plan)
        assertEquals(3, steps.size)
        assertEquals("Setup Gradle project", steps[0])
    }

    @Test
    fun `multi-line step joins continuation lines`() {
        val plan = """
            1) Setup Gradle project
               with Kotlin DSL
               and dependencies
            2) Implement Calculator
        """.trimIndent()
        val steps = PlanParser.parse(plan)
        assertEquals(2, steps.size)
        assertTrue(steps[0].contains("with Kotlin DSL"))
        assertTrue(steps[0].contains("and dependencies"))
        assertEquals("Implement Calculator", steps[1])
    }

    @Test
    fun `blank lines separate items`() {
        val plan = """
            1) First

            2) Second
        """.trimIndent()
        val steps = PlanParser.parse(plan)
        assertEquals(2, steps.size)
    }

    @Test
    fun `preamble without marker is skipped`() {
        val plan = """
            Here is my plan for the task:

            1) First step
            2) Second step
        """.trimIndent()
        val steps = PlanParser.parse(plan)
        assertEquals(2, steps.size)
        assertEquals("First step", steps[0])
    }

    @Test
    fun `empty plan returns empty list`() {
        assertEquals(emptyList<String>(), PlanParser.parse(null))
        assertEquals(emptyList<String>(), PlanParser.parse(""))
        assertEquals(emptyList<String>(), PlanParser.parse("   "))
    }

    @Test
    fun `non-list prose returns empty list`() {
        val prose = "We should just build a calculator with basic operations and be done."
        assertEquals(emptyList<String>(), PlanParser.parse(prose))
    }

    @Test
    fun `parseOrWhole returns steps when list parsed`() {
        val plan = "1) A\n2) B"
        val steps = PlanParser.parseOrWhole(plan)
        assertEquals(2, steps.size)
    }

    @Test
    fun `parseOrWhole falls back to whole plan when no list`() {
        val plan = "Just build a calculator."
        val steps = PlanParser.parseOrWhole(plan)
        assertEquals(1, steps.size)
        assertEquals("Just build a calculator.", steps[0])
    }

    @Test
    fun `colon markers are supported`() {
        val plan = "1: First\n2: Second"
        assertEquals(2, PlanParser.parse(plan).size)
    }
}
