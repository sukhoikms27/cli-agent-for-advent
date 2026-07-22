package com.cliagent.review.gh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PrUrlParserTest {

    @Test
    fun `parses canonical PR URL`() {
        val c = PrUrlParser.parse("https://github.com/sukhoikms27/review-bot-demo/pull/1")
        assertEquals("sukhoikms27", c?.owner)
        assertEquals("review-bot-demo", c?.repo)
        assertEquals(1, c?.number)
    }

    @Test
    fun `parses URL with trailing files path`() {
        val c = PrUrlParser.parse("https://github.com/foo/bar/pull/42/files")
        assertEquals("foo", c?.owner)
        assertEquals("bar", c?.repo)
        assertEquals(42, c?.number)
    }

    @Test
    fun `parses pulls typo and query`() {
        val c = PrUrlParser.parse("https://github.com/foo/bar/pulls/7?diff=unified")
        assertEquals(7, c?.number)
    }

    @Test
    fun `parses http scheme`() {
        val c = PrUrlParser.parse("http://github.com/foo/bar/pull/3")
        assertEquals("foo", c?.owner)
    }

    @Test
    fun `returns null for non-github URL`() {
        assertNull(PrUrlParser.parse("https://gitlab.com/foo/bar/pull/1"))
    }

    @Test
    fun `returns null for non-PR github URL`() {
        assertNull(PrUrlParser.parse("https://github.com/foo/bar"))
        assertNull(PrUrlParser.parse("https://github.com/foo/bar/issues/5"))
    }

    @Test
    fun `apiPath builds correct REST path`() {
        val c = PrCoordinates("owner", "repo", 10)
        assertEquals("repos/owner/repo/pulls/10", c.apiPath())
        assertEquals("repos/owner/repo/pulls/10/reviews", c.apiPath("/reviews"))
    }

    @Test
    fun `htmlUrl returns expected format`() {
        val c = PrCoordinates("owner", "repo", 10)
        assertEquals("https://github.com/owner/repo/pull/10", c.htmlUrl)
    }
}
