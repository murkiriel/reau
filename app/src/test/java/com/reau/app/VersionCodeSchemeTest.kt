package com.reau.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The versionCode of a release is fixed in app/build.gradle.kts (it used to be the minutes of the clock, so two builds of one commit were
 * two different APKs). The scheme is `30_000_000 + major * 1_000_000 + minor * 10_000 + patch * 100`: Android only installs over an app
 * whose versionCode is lower, so every number has to stay above the highest one already published and grow with the version. When the
 * version changes, the two lines of the build file change together and this test says if they do not agree.
 */
class VersionCodeSchemeTest {
    private val build = File("build.gradle.kts").readText()

    /** versionCode of releases already published (read from the APKs of the Releases page with aapt2): 0.1.0, 0.2.0, 0.3.0 and 0.3.1. */
    private val published = mapOf("0.1.0" to 29_873_950, "0.2.0" to 29_887_215, "0.3.0" to 30_030_000, "0.3.1" to 30_030_100)

    private fun scheme(name: String): Int {
        val (major, minor, patch) = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(name)!!.destructured
        require(minor.toInt() < 100 && patch.toInt() < 100) { "minor and patch have two digits in the scheme: $name" }
        return 30_000_000 + major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100
    }

    private fun buildName() = Regex("""val appVersionName = "([^"]+)"""").find(build)!!.groupValues[1]
    private fun buildCode() = Regex("""val releaseVersionCode = ([0-9_]+)""").find(build)!!.groupValues[1].replace("_", "").toInt()

    /** The debug code as the build file writes it, `releaseVersionCode + N`, with the release code put in. */
    private fun debugCode(): Int {
        val (plus) = Regex("""val debugVersionCode = releaseVersionCode \+ (\d+)\s*$""", RegexOption.MULTILINE).find(build)!!.destructured
        return buildCode() + plus.toInt()
    }

    @Test
    fun `the fixed code of the build is the one the scheme gives to its version name`() {
        assertEquals(buildName(), scheme(buildName()), buildCode())
        assertEquals("0.3.0 is the first release with the fixed scheme", 30_030_000, scheme("0.3.0"))
    }

    @Test
    fun `the code is above every code already published, and so is every later version`() {
        val highest = published.getValue("0.2.0")
        assertEquals("0.2.0 is the highest code of the minutes era", 29_887_215, highest)
        assertTrue("every scheme code published is the one the scheme gives", listOf("0.3.0", "0.3.1").all { published.getValue(it) == scheme(it) })
        assertTrue("${buildCode()} must be above $highest or the phones with a published release refuse the update", buildCode() > highest)
        for (later in listOf("0.3.1", "0.4.0", "0.10.0", "1.0.0", "1.2.3")) assertTrue(later, scheme(later) > highest)
    }

    @Test
    fun `the code grows with the version and a patch does not collide with a minor`() {
        val versions = listOf("0.3.0", "0.3.1", "0.3.99", "0.4.0", "0.4.1", "0.99.99", "1.0.0", "1.0.1", "1.1.0")
        val codes = versions.map(::scheme)
        assertEquals("one code per version", codes.size, codes.toSet().size)
        assertEquals("in the order of the versions", codes.sorted(), codes)
        assertTrue("room for 99 rebuilds of one version", scheme("0.3.1") - scheme("0.3.0") >= 100)
        assertTrue("fits an Int for a long time", scheme("99.99.99") > 0)
    }

    @Test
    fun `a minor or a patch of three digits is refused, since it would collide with the next one`() {
        for (name in listOf("0.100.0", "0.3.100")) {
            try {
                scheme(name)
                fail("$name collides and must be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue("the build's own version is within the limits", buildName().split(".").drop(1).all { it.toInt() < 100 })
    }

    @Test
    fun `the release takes the fixed code and the debug build has its own`() {
        assertTrue("defaultConfig takes the fixed code", Regex("""versionCode = releaseVersionCode\b""").containsMatchIn(build))
        assertFalse("the old code taken from the clock is gone", "buildVersionCode" in build)
        assertFalse("no code is taken from the clock any more", "currentTimeMillis" in build)
        assertTrue("the debug build has its own code", Regex("""withBuildType\("debug"\)[\s\S]{0,200}versionCode\.set\(debugVersionCode\)""").containsMatchIn(build))
        assertFalse("a release never takes the debug code", Regex("""withBuildType\("release"\)""").containsMatchIn(build))
    }

    @Test
    fun `the debug code is the release code plus 99, above its release and below the next patch`() {
        val release = buildCode()
        assertEquals("the top of the 99 numbers kept for a rebuild", release + 99, debugCode())
        assertTrue("above the release of the same version, which the debug installs over", debugCode() > release)
        val (major, minor, patch) = buildName().split(".").map { it.toInt() }
        assertTrue("below the next patch, which installs over the debug", debugCode() < scheme("$major.$minor.${patch + 1}"))
        assertTrue("below the next minor too", debugCode() < scheme("$major.${minor + 1}.0"))
        // The case that broke the old scheme (the minutes of the clock): a debug build installed in 2027 blocked the releases.
        val minutesIn2027 = (java.time.LocalDate.of(2027, 3, 1).toEpochDay() * 24 * 60).toInt()
        assertTrue("the old code of the clock would pass 0.4.1 and block it: $minutesIn2027", minutesIn2027 > scheme("0.4.1"))
    }
}
