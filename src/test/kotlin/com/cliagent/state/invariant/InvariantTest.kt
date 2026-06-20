package com.cliagent.state.invariant

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InvariantTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `Invariant round-trips through JSON`() {
        val inv = Invariant(id = "no-compose", rule = "UI только View-based", category = InvariantCategory.BAN)
        val encoded = json.encodeToString(Invariant.serializer(), inv)
        val decoded = json.decodeFromString<Invariant>(encoded)
        assertEquals(inv, decoded)
    }

    @Test
    fun `default category is BUSINESS`() {
        val inv = Invariant(id = "x", rule = "правило")
        assertEquals(InvariantCategory.BUSINESS, inv.category)
    }

    @Test
    fun `all categories serialize and round-trip`() {
        InvariantCategory.values().forEach { cat ->
            val inv = Invariant(id = "x", rule = "r", category = cat)
            val decoded = json.decodeFromString<Invariant>(json.encodeToString(Invariant.serializer(), inv))
            assertEquals(cat, decoded.category)
        }
    }

    @Test
    fun `legacy JSON without category loads with default BUSINESS`() {
        // Инвариант, записанный без поля category (гипотетический legacy)
        val legacyJson = """{"id":"no-compose","rule":"no Compose"}"""
        val inv = json.decodeFromString<Invariant>(legacyJson)
        assertEquals("no-compose", inv.id)
        assertEquals("no Compose", inv.rule)
        assertEquals(InvariantCategory.BUSINESS, inv.category)
    }

    @Test
    fun `InvariantResult Valid is singleton`() {
        assertEquals(InvariantResult.Valid, InvariantResult.Valid)
    }

    @Test
    fun `InvariantResult Violated is data class with equality`() {
        val v1 = InvariantResult.Violated("no-compose", "no Compose", "содержит setContent{}")
        val v2 = InvariantResult.Violated("no-compose", "no Compose", "содержит setContent{}")
        val v3 = InvariantResult.Violated("no-compose", "no Compose", "другое объяснение")
        assertEquals(v1, v2)
        assertNotEquals(v1, v3)
    }

    @Test
    fun `Valid and Violated are distinct cases`() {
        val valid: InvariantResult = InvariantResult.Valid
        val violated: InvariantResult = InvariantResult.Violated("x", "y", "z")
        assertNotEquals(valid, violated)
    }
}
