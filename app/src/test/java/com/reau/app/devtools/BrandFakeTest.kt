package com.reau.app.devtools

import com.reau.app.data.KeepAliveNotice
import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import com.reau.app.data.RideDiagnostics
import com.reau.app.devtools.BrandFake.Choice
import com.reau.app.ui.KeepAliveGuide
import com.reau.app.ui.KeepAliveGuide.Brand
import com.reau.app.ui.KeepAliveOpen
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The debug build's fake brand (26/11/2026): a Motorola can see the "keep REau alive" card and page as a Xiaomi or a realme would. The brand the app reads, the rules of the
 * card with it, the chain of the button on a phone that is not a Xiaomi, and the log line that tells a test from a real log. The release has none of it
 * (BrandFakeAbsenceInReleaseTest).
 */
class BrandFakeTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var log: File

    @Before
    fun setUp() {
        BrandFake.reset()
        log = tmp.newFile("ride-brand.log")
        RideDiagnostics.testOnlySetFile(log)
    }

    @After
    fun tearDown() {
        BrandFake.reset()
        RideDiagnostics.testOnlyReset()
    }

    private fun told(): List<String> {
        RideDiagnostics.logAndWait("ui", "end")
        return log.readLines().filter { "[debug]" in it }
    }

    /** What the app reads on a Motorola (the owner's phone) with the choice made. */
    private fun onMotorola(): Brand = KeepAliveGuide.brandFor("motorola", "motorola", BrandFake.maker())

    @Test
    fun `the real brand is the default, and a Motorola has no notice`() {
        assertEquals(Choice.REAL, BrandFake.choice.value)
        assertNull(BrandFake.maker())
        assertEquals(Brand.OTHER, onMotorola())
        assertFalse(KeepAliveGuide.hasNotice(onMotorola()))
    }

    @Test
    fun `faked as Xiaomi a Motorola reads the Xiaomi family, with its steps, its ticks and its notice`() {
        BrandFake.choose(Choice.XIAOMI)
        assertEquals("xiaomi", BrandFake.maker())
        assertEquals(Brand.XIAOMI, onMotorola())
        assertTrue(KeepAliveGuide.hasNotice(onMotorola()))
        assertEquals(KeepAliveGuide.steps(Brand.XIAOMI), KeepAliveGuide.steps(onMotorola()))
        assertEquals(KeepAliveGuide.items(Brand.XIAOMI), KeepAliveGuide.items(onMotorola()))
    }

    @Test
    fun `faked as realme a Motorola reads the OPPO family, the one of realme`() {
        BrandFake.choose(Choice.REALME)
        assertEquals("realme", BrandFake.maker())
        assertEquals(Brand.OPPO, onMotorola())
        assertTrue(KeepAliveGuide.hasNotice(onMotorola()))
    }

    @Test
    fun `the fake wins over the phone's own brand, and the real choice gives it back`() {
        BrandFake.choose(Choice.REALME)
        assertEquals(Brand.OPPO, KeepAliveGuide.brandFor("Xiaomi", "Redmi", BrandFake.maker()))
        BrandFake.choose(Choice.REAL)
        assertEquals(Brand.XIAOMI, KeepAliveGuide.brandFor("Xiaomi", "Redmi", BrandFake.maker()))
        assertEquals(Brand.OTHER, KeepAliveGuide.brandFor("samsung", "samsung", null))
    }

    @Test
    fun `the card's other rules hold with a fake brand, the pairing and the answer and a closing after it`() {
        BrandFake.choose(Choice.XIAOMI)
        val eligible = KeepAliveGuide.hasNotice(onMotorola())
        assertFalse("not paired with the dash", KeepAliveNotice.due(eligible, paired = false, answeredAtMs = 0L, lastClosedMs = 0L, answeredClosedMs = 0L))
        assertTrue("paired and not answered", KeepAliveNotice.due(eligible, paired = true, answeredAtMs = 0L, lastClosedMs = 0L, answeredClosedMs = 0L))
        assertFalse("answered", KeepAliveNotice.due(eligible, paired = true, answeredAtMs = 1_000L, lastClosedMs = 0L, answeredClosedMs = 0L))
        assertTrue("the system closed REau after the answer", KeepAliveNotice.due(eligible, paired = true, answeredAtMs = 1_000L, lastClosedMs = 5_000L, answeredClosedMs = 0L))
        BrandFake.choose(Choice.REAL)
        assertFalse("the real Motorola again: never due", KeepAliveNotice.due(KeepAliveGuide.hasNotice(onMotorola()), paired = true, answeredAtMs = 0L, lastClosedMs = 0L, answeredClosedMs = 0L))
    }

    @Test
    fun `forgetting the answer makes the card due again, and keeps nothing of it`() {
        val prefs = FakePrefs()
        val book = KeepAliveNotice.Book(prefs)
        book.answer(2_000L, 5_000L)
        assertFalse(KeepAliveNotice.due(true, true, book.answeredAt(), 5_000L, book.answeredClosed()))
        book.forget()
        assertEquals(0L, book.answeredAt())
        assertEquals(0L, book.answeredClosed())
        assertTrue(KeepAliveNotice.due(true, true, book.answeredAt(), 5_000L, book.answeredClosed()))
        assertEquals("nothing else of the book was kept", emptyMap<String, Any?>(), prefs.map)
    }

    @Test
    fun `on a Motorola faked as Xiaomi the button fails on the maker's two screens and falls to the app's details, and the log says each step`() {
        BrandFake.choose(Choice.XIAOMI)
        val lines = mutableListOf<String>()
        val way = KeepAliveOpen.open(onMotorola(), { target -> if (target is KeepAliveOpen.Target.Component) throw IllegalStateException("no such screen") }, { lines += it })
        assertEquals(KeepAliveOpen.Way.APP_DETAILS, way)
        assertEquals(
            listOf(
                "keepalive: the Autostart screen did not open (alternative 1 of 3): IllegalStateException",
                "keepalive: the maker's permissions page of the app did not open (alternative 2 of 3): IllegalStateException",
                "keepalive: opened the app's details page (alternative 3 of 3)",
            ),
            lines,
        )
    }

    @Test
    fun `picking a fake brand writes the line that says it, and the real brand writes the end of it`() {
        BrandFake.choose(Choice.XIAOMI)
        val first = told()
        assertEquals(first.toString(), 1, first.size)
        assertTrue(first[0], "keepalive: brand faked as xiaomi (debug)" in first[0])
        BrandFake.choose(Choice.REALME)
        assertTrue("keepalive: brand faked as realme (debug)" in told().last())
        BrandFake.choose(Choice.REAL)
        assertTrue(BrandFake.ENDED_LINE in told().last())
        assertFalse("the end is not a fake line", BrandFake.ENDED_LINE.contains("faked as"))
    }

    @Test
    fun `the line is written again where the log needs it while a brand is faked, and never with the real one`() {
        BrandFake.logWhenActive()
        assertEquals("the real brand writes nothing", emptyList<String>(), told())
        BrandFake.choose(Choice.XIAOMI)
        BrandFake.logWhenActive()
        BrandFake.logWhenActive()
        assertEquals(3, told().count { "keepalive: brand faked as xiaomi (debug)" in it })
    }

    @Test
    fun `the log line is the one the owner asked for`() {
        assertEquals("keepalive: brand faked as xiaomi (debug)", BrandFake.line("xiaomi"))
        assertEquals("keepalive: brand faked as realme (debug)", BrandFake.line("realme"))
    }

    // -- the row: read from the source, as it needs a Context and a composable ----------------------------------------------------------

    private fun between(text: String, start: String, end: String): String {
        assertTrue("the anchor is gone: $start", text.contains(start))
        return text.substringAfter(start).substringBefore(end)
    }

    @Test
    fun `the row picks the choice and asks the notice to look again, and the button forgets the answer and does the same`() {
        val rows = File("src/debug/java/com/reau/app/devtools/BrandFakeRows.kt").readText()
        val pick = between(rows, "internal fun pick(", "\n    }\n")
        assertTrue(pick, "BrandFake.choose(choice)" in pick && "KeepAliveNotice.refresh(context, eligible())" in pick)
        assertTrue(pick, pick.indexOf("BrandFake.choose(choice)") < pick.indexOf("KeepAliveNotice.refresh"))
        val again = between(rows, "internal fun showCardAgain(", "\n    }\n")
        assertTrue(again, "KeepAliveNotice.Book.open(context).forget()" in again && "KeepAliveNotice.refresh(context, eligible())" in again)
        assertTrue("the eligibility is the one the activity reads", "KeepAliveGuide.hasNotice(KeepAliveGuide.current())" in rows)
        assertTrue("the three options, in the order of the choices", rows.indexOf("debug_brand_real") < rows.indexOf("debug_brand_xiaomi") && rows.indexOf("debug_brand_xiaomi") < rows.indexOf("debug_brand_realme"))
        assertEquals(listOf(Choice.REAL, Choice.XIAOMI, Choice.REALME), Choice.entries)
    }

    @Test
    fun `the page and the start of a projection say it in the log, the page when it opens`() {
        val route = File("src/main/java/com/reau/app/ui/KeepAliveRoute.kt").readText()
        assertTrue(route, "LaunchedEffect(Unit) { com.reau.app.devtools.BrandFake.logWhenActive() }" in route)
        val checklist = File("src/main/java/com/reau/app/data/KeepAliveChecklist.kt").readText()
        val start = between(checklist, "fun logAtStart(", "\n    }\n")
        assertTrue(start, start.indexOf("BrandFake.logWhenActive()") in 0 until start.indexOf("RideDiagnostics.log(\"settings\""))
    }
}
