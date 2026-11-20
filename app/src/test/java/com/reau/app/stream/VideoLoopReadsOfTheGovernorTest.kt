package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan 27, layer 4: what `VideoLoop` takes of the governor is the list below and nothing else. The video that reaches the dash is made
 * of the actions the governor returns and of these values, so a governor that gives the same actions and the same values for the same
 * polls (GovernadorOuroTest) gives the same video. `VideoLoop` needs a codec and cannot run in a JVM test: like the `*IsWiredTest`
 * tests, this one reads the source.
 */
class VideoLoopReadsOfTheGovernorTest {
    private val source = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()

    // The code alone: no comment says what is read.
    private val code = source.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/**") || it.trim().startsWith("//") }
        .joinToString("\n") { it.substringBefore(" // ") }

    @Test
    fun `the loop reads of the governor only the values the golden traces record, and calls only its four entry points`() {
        val members = Regex("""\bg\.(\w+)""").findAll(code).map { it.groupValues[1] }.toSortedSet()
        assertEquals(
            // The last three (plan 32) only write to the log: the profiles in the line of the start and what each pace cost in bits, every 5 minutes and at the end.
            // They change no action and no value the golden traces record.
            sortedSetOf(
                "fps", "profileFps", "encoderFps", "activeBitrateBps", "keyframeSec", "encoderKeyframeSec", "gopFps", "start", "onPoll", "syncRequested", "rebuildAction",
                "profilesText", "periodicPaceSummary",
            ),
            members,
        )
    }

    @Test
    fun `the governor has no other name in the loop`() {
        // The field is only set, tested for null and copied into `g`: no member of it is read through the field.
        assertEquals(emptyList<String>(), Regex("""\bgovernor\s*(\?\.|!!\.|\.)\s*\w+""").findAll(code).map { it.value }.toList())
        val named = Regex("""\b(\w+)\s*:\s*StreamGovernor\b(?!\.)""").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals("what is declared as a StreamGovernor", setOf("governor", "g"), named)
        assertEquals("the governor is made in one place", 1, Regex("""\bStreamGovernor\(""").findAll(code).count())
        assertTrue("and it is `g`", Regex("""val g = StreamGovernor\(""").containsMatchIn(code))
        assertEquals("every other `g` comes from the field", 2, Regex("""val g = governor \?: return""").findAll(code).count())
    }

    @Test
    fun `the loop handles the six actions, and of a rebuild it uses the four values the traces record`() {
        val handled = Regex("""is StreamGovernor\.Action\.(\w+) ->""").findAll(code).map { it.groupValues[1] }.toSortedSet()
        assertEquals(sortedSetOf("SetPace", "SetBitrate", "RequestSync", "Rebuild", "Log", "Stats"), handled)
        val ofRebuild = Regex("""\bwanted\.(\w+)""").findAll(code).map { it.groupValues[1] }.toSortedSet()
        assertEquals(sortedSetOf("fps", "keyframeSec", "bitrateBps", "encoderKeyframeSec"), ofRebuild)
        val ofPace = Regex("""\baction\.pace\.(\w+)""").findAll(code).map { it.groupValues[1] }.toSortedSet()
        assertEquals("the pace goes whole to the compositor; the log line says its two intervals", sortedSetOf("activeMs", "idleMs"), ofPace)
    }

    /** Plan 27, step G3: the interval is fixed for the whole stream, so the line of the start and the stats text no longer ask the stored setting whether it is "automatic". */
    @Test
    fun `the keyframe interval the loop tells is the one the governor read, and it is always told as fixed`() {
        val told = Regex("""\bstats\?\.stream\(settings\.bitrateMode, (g|wanted)\.keyframeSec, fixed = true\)""").findAll(code).map { it.groupValues[1] }.toList()
        assertEquals("the stream's start and the rebuilt encoder tell it", listOf("g", "wanted"), told)
        assertEquals("the stored setting only goes to the governor's constructor", 1, Regex("""\bsettings\.keyframeSec\b""").findAll(code).count())
        assertTrue("the line of the start says `fixed` after the interval", code.contains("}s fixed\" +"))
        assertFalse("and never `auto`", code.contains("\" auto\""))
    }
}
