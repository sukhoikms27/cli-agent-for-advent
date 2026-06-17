package com.cliagent.memory

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UserProfileSchemaTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `round-trip with about`() {
        val profile = UserProfile(style = "concise", about = "backend dev", constraints = listOf("no RxJava"))
        val encoded = json.encodeToString(UserProfile.serializer(), profile)
        val decoded = json.decodeFromString<UserProfile>(encoded)
        assertEquals(profile, decoded)
    }

    @Test
    fun `pre-Day-12 profile JSON without about loads with null`() {
        val legacyJson = """
            {
              "style": "concise",
              "format": null,
              "constraints": ["no RxJava"]
            }
        """.trimIndent()
        val decoded = json.decodeFromString<UserProfile>(legacyJson)
        assertEquals("concise", decoded.style)
        assertEquals(listOf("no RxJava"), decoded.constraints)
        assertNull(decoded.about)
    }

    @Test
    fun `embedded in LongTermMemory round-trips`() {
        val ltm = LongTermMemory(profile = UserProfile(about = "goal: learn"))
        val encoded = json.encodeToString(LongTermMemory.serializer(), ltm)
        val decoded = json.decodeFromString<LongTermMemory>(encoded)
        assertEquals("goal: learn", decoded.profile?.about)
    }
}
