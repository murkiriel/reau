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
 *
 * One narrow exception, for the pilots' test series (28/11/2026): the names 0.3.100 to 0.3.108 would get 30_040_000 and up from the formula, the code of 0.4.0, which has to install
 * over every pilot build; so they take the "99 numbers for a rebuild" above 0.3.99, ten at a time ([pilotScheme]). Nothing else leaves the formula.
 */
class VersionCodeSchemeTest {
    private val build = File("build.gradle.kts").readText()

    private companion object {
        /** The first and the last name of the pilots' series after 0.3.99 that take [pilotScheme]'s code (0.3.100 to 0.3.108), and the step between their codes. */
        const val PILOT_FIRST = 100
        const val PILOT_LAST = 108
        const val PILOT_STEP = 10
    }

    /** versionCode of releases already published (read from the APKs of the Releases page with aapt2): 0.1.0 and 0.2.0. */
    private val published = mapOf("0.1.0" to 29_873_950, "0.2.0" to 29_887_215)

    private fun scheme(name: String): Int {
        val (major, minor, patch) = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(name)!!.destructured
        require(minor.toInt() < 100 && patch.toInt() < 100) { "minor and patch have two digits in the scheme: $name" }
        return 30_000_000 + major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100
    }

    /**
     * The code of a pilots' test build named 0.3.1xx, the only names that do not follow [scheme]: 30_039_900 + (patch - 99) * 10, so 0.3.100 is 30_039_910 and 0.3.108 is
     * 30_039_990, above 0.3.99 (30_039_900) and below 0.4.0 (30_040_000). Any other name (another major or minor, a patch outside 100..108) is refused.
     */
    private fun pilotScheme(name: String): Int {
        val (major, minor, patch) = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(name)!!.destructured
        require(major == "0" && minor == "3" && patch.toInt() in PILOT_FIRST..PILOT_LAST) { "only 0.3.$PILOT_FIRST to 0.3.$PILOT_LAST are pilots' names outside the formula: $name" }
        return scheme("0.3.99") + (patch.toInt() - 99) * PILOT_STEP
    }

    private fun isPilotName(name: String) = runCatching { pilotScheme(name) }.isSuccess

    /** What the build's version name must get: the pilots' rule for the 0.3.1xx names, the formula for every other one. */
    private fun expected(name: String) = if (isPilotName(name)) pilotScheme(name) else scheme(name)

    private fun buildName() = Regex("""val appVersionName = "([^"]+)"""").find(build)!!.groupValues[1]
    private fun buildCode() = Regex("""val releaseVersionCode = ([0-9_]+)""").find(build)!!.groupValues[1].replace("_", "").toInt()

    @Test
    fun `the fixed code of the build is the one the scheme gives to its version name`() {
        assertEquals(buildName(), expected(buildName()), buildCode())
        assertEquals("0.3.0 is the first release with the fixed scheme", 30_030_000, scheme("0.3.0"))
    }

    @Test
    fun `the code is above every code already published, and so is every later version`() {
        val highest = published.values.max()
        assertTrue("$highest is the 0.2.0 code", highest == 29_887_215)
        assertTrue("${buildCode()} must be above $highest or the phones with a published release refuse the update", buildCode() > highest)
        for (later in listOf("0.3.1", "0.4.0", "0.10.0", "1.0.0", "1.2.3")) assertTrue(later, scheme(later) > highest)
        assertTrue("and the pilots' codes after 0.3.99", pilotScheme("0.3.100") > highest)
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
    fun `a minor or a patch of three digits is refused by the formula, since it would collide with the next one`() {
        for (name in listOf("0.100.0", "0.3.100")) {
            try {
                scheme(name)
                fail("$name collides and must be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue("the build's own version is within the limits of the formula or is a pilots' name", isPilotName(buildName()) || buildName().split(".").drop(1).all { it.toInt() < 100 })
    }

    @Test
    fun `the pilots' names after 0_3_99 get codes above it and below 0_4_0, growing, and none that the formula gives to any version`() {
        val names = (PILOT_FIRST..PILOT_LAST).map { "0.3.$it" }
        val codes = names.map(::pilotScheme)
        assertEquals("0.3.100 is the first one", 30_039_910, pilotScheme("0.3.100"))
        assertEquals("0.3.108 is the last one", 30_039_990, pilotScheme("0.3.108"))
        assertEquals("growing, in the order of the names", codes.sorted(), codes)
        assertEquals("one code per name", codes.size, codes.toSet().size)
        assertTrue("above 0.3.99 (30_039_900): the phones with 0.3.99 take the update", codes.all { it > scheme("0.3.99") })
        assertTrue("below 0.4.0 (30_040_000): 0.4.0 installs over every pilot build", codes.all { it < scheme("0.4.0") })
        assertTrue("above 0.3.1: they are a test series on top of it", codes.all { it > scheme("0.3.1") })
        // The formula's own codes are all multiples of 100: a pilot's code is never one of them, so no release shares a code with a pilot build.
        assertTrue(codes.all { it % 100 != 0 })
    }

    @Test
    fun `the exception is narrow, it takes no other name`() {
        for (name in listOf("0.3.99", "0.3.109", "0.3.110", "0.3.200", "0.3.1000", "0.3.0", "0.4.100", "0.2.100", "1.3.100", "0.30.100", "0.3.10")) {
            assertFalse("$name is not a pilot's name outside the formula", isPilotName(name))
        }
        for (name in listOf("0.3.100", "0.3.104", "0.3.108")) assertTrue(name, isPilotName(name))
        assertEquals("the formula decides every other name", 30_039_900, expected("0.3.99"))
        assertEquals(30_040_000, expected("0.4.0"))
    }

    @Test
    fun `the build file's comment states the exception to the formula`() {
        assertTrue(build, "The ONE exception to the formula" in build && "30_039_910" in build && "0.3.100 to 0.3.108" in build)
    }

    @Test
    fun `the release takes the fixed code and only the debug build reads the clock`() {
        assertTrue("defaultConfig takes the fixed code", Regex("""versionCode = releaseVersionCode\b""").containsMatchIn(build))
        assertFalse("the old code taken from the clock is gone", "buildVersionCode" in build)
        val clock = build.lines().filter { "currentTimeMillis" in it }
        assertEquals("the clock is read in one place only: $clock", 1, clock.size)
        assertTrue(clock.single(), clock.single().trimStart().startsWith("val debugVersionCode"))
        assertTrue("the debug build has its own code", Regex("""withBuildType\("debug"\)[\s\S]{0,200}versionCode\.set\(debugVersionCode\)""").containsMatchIn(build))
        assertFalse("a release never takes the debug code", Regex("""withBuildType\("release"\)""").containsMatchIn(build))
    }
}
