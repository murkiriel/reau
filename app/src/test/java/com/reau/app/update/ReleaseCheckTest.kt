package com.reau.app.update

import com.reau.app.update.ReleaseCheck.Verdict
import com.reau.app.update.ReleaseCheck.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The manual check for a newer release on GitHub: how the answer is read and how the versions are compared (nothing is downloaded). */
class ReleaseCheckTest {
    private fun page(tag: String) = "https://github.com/Murkiriel/reau/releases/tag/$tag"

    @Test
    fun `the latest release is read from its tag and its page`() {
        val latest = ReleaseCheck.fromFields("v0.2.0", page("v0.2.0")) as ReleaseCheck.Result.Latest
        assertEquals("v0.2.0", latest.tag)
        assertEquals("https://github.com/Murkiriel/reau/releases/tag/v0.2.0", latest.pageUrl)
    }

    @Test
    fun `a page that is not the project's own is not opened, the project's releases are`() {
        val latest = ReleaseCheck.fromFields("v0.2.0", "https://evil.example/download") as ReleaseCheck.Result.Latest
        assertEquals("https://github.com/Murkiriel/reau/releases", latest.pageUrl)
        val other = ReleaseCheck.fromFields("v0.2.0", "https://github.com/Someone/else/releases/tag/v0.2.0") as ReleaseCheck.Result.Latest
        assertTrue(other.pageUrl.startsWith(ReleaseCheck.RELEASES_PREFIX))
        val none = ReleaseCheck.fromFields("v0.2.0", "") as ReleaseCheck.Result.Latest
        assertEquals("https://github.com/Murkiriel/reau/releases", none.pageUrl)
    }

    @Test
    fun `a tag that is no version is a bad answer, never a crash`() {
        for (bad in listOf("", "  ", "nightly", "latest", "v")) {
            assertEquals(bad, ReleaseCheck.Result.Failed(Why.BAD_ANSWER), ReleaseCheck.fromFields(bad, page("x")))
        }
    }

    @Test
    fun `the status of the answer says what it is before the body is read`() {
        assertNull(ReleaseCheck.failureOf(200))
        assertEquals(ReleaseCheck.Result.NoRelease, ReleaseCheck.failureOf(404))
        assertEquals(ReleaseCheck.Result.Failed(Why.LIMITED), ReleaseCheck.failureOf(403))
        assertEquals(ReleaseCheck.Result.Failed(Why.LIMITED), ReleaseCheck.failureOf(429))
        assertEquals(ReleaseCheck.Result.Failed(Why.SERVER), ReleaseCheck.failureOf(503))
        assertEquals(ReleaseCheck.Result.Failed(Why.BAD_ANSWER), ReleaseCheck.failureOf(301))
    }

    @Test
    fun `the numbers of a version come from a tag, a release name or a debug build's name`() {
        assertEquals(listOf(0, 1, 0), ReleaseCheck.coreOf("v0.1.0"))
        assertEquals(listOf(0, 1, 0), ReleaseCheck.coreOf("0.1.0-dev+26.10.21-07.53"))
        assertEquals(listOf(1, 2, 0), ReleaseCheck.coreOf("v1.2"))
        assertEquals(listOf(0, 10, 3), ReleaseCheck.coreOf("0.10.3"))
        for (bad in listOf("", "nightly", "v", "1.x.0", "1..0", "1.2.3.4", "99999999.0.0")) assertNull(bad, ReleaseCheck.coreOf(bad))
    }

    @Test
    fun `a newer release is told, a same or older one is up to date, a debug build is its own case`() {
        assertEquals(Verdict.NEWER, ReleaseCheck.verdict("0.1.0", "v0.1.1"))
        assertEquals(Verdict.NEWER, ReleaseCheck.verdict("0.1.9", "v0.2.0"))
        assertEquals("ten is more than nine", Verdict.NEWER, ReleaseCheck.verdict("0.9.0", "v0.10.0"))
        assertEquals(Verdict.NEWER, ReleaseCheck.verdict("0.9.9", "v1.0.0"))
        assertEquals(Verdict.UP_TO_DATE, ReleaseCheck.verdict("0.1.0", "v0.1.0"))
        assertEquals("ahead of the published one", Verdict.UP_TO_DATE, ReleaseCheck.verdict("0.2.0", "v0.1.0"))
        assertEquals(Verdict.DEV_BUILD, ReleaseCheck.verdict("0.1.0-dev+26.10.21-07.53", "v0.1.0"))
        assertEquals("a debug build before the published release still hears of it", Verdict.NEWER, ReleaseCheck.verdict("0.1.0-dev+26.10.21-07.53", "v0.2.0"))
        assertEquals(Verdict.UNKNOWN, ReleaseCheck.verdict("weird", "v0.1.0"))
        assertEquals(Verdict.UNKNOWN, ReleaseCheck.verdict("0.1.0", "nightly"))
    }

    @Test
    fun `the address asked is the project's latest release on GitHub's API, over https`() {
        assertEquals("https://api.github.com/repos/Murkiriel/reau/releases/latest", ReleaseCheck.URL)
        assertTrue(ReleaseCheck.RELEASES_PREFIX.startsWith("https://"))
    }
}
