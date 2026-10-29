package com.reau.app.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.update.ReleaseCheck.Why
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The JSON of GitHub's answer, read by org.json (which the test JVM lacks): the rest of the check is in ReleaseCheckTest. */
@RunWith(AndroidJUnit4::class)
class ReleaseCheckJsonTest {
    /** The shape of GitHub's "latest release": other `html_url`s nest in the author and the assets, the release's own is on top. */
    private val answer = """{"url":"https://api.github.com/repos/Murkiriel/reau/releases/1","html_url":"https://github.com/Murkiriel/reau/releases/tag/v0.2.0",
        "id":1,"author":{"login":"x","html_url":"https://github.com/x"},"tag_name":"v0.2.0","name":"REau 0.2.0","draft":false,"prerelease":false,
        "assets":[{"name":"REau-0.2.0-completa.apk","browser_download_url":"https://github.com/Murkiriel/reau/releases/download/v0.2.0/REau-0.2.0-completa.apk"}],
        "body":"a \"quoted\" note, with { braces } and a tag_name text"}"""

    @Test
    fun theReleaseIsReadFromTheTopOfTheAnswer() {
        val latest = ReleaseCheck.parse(answer) as ReleaseCheck.Result.Latest
        assertEquals("v0.2.0", latest.tag)
        assertEquals("https://github.com/Murkiriel/reau/releases/tag/v0.2.0", latest.pageUrl)
    }

    @Test
    fun whatIsNotAReleaseIsABadAnswerAndNeverACrash() {
        for (bad in listOf("", "not json", "{}", """{"tag_name":""}""", """{"tag_name":"nightly"}""", "[]", """{"message":"Not Found"}""")) {
            assertEquals(bad, ReleaseCheck.Result.Failed(Why.BAD_ANSWER), ReleaseCheck.parse(bad))
        }
    }
}
