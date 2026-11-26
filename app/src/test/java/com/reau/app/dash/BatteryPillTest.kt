package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan 35, step E (26/11/2026): the pill of the low battery on the dash's video ([BatteryPill]): when it shows, for how long, and what gives way to what. The clock and the wait
 * are moved by hand; what the compositor is given is recorded.
 */
class BatteryPillTest {
    private var now = 1_000L
    private val waits = mutableListOf<Pair<Long, () -> Unit>>()
    private val given = mutableListOf<CallNotice?>()
    private val pill = BatteryPill(
        show = { given.add(it) },
        nowMs = { now },
        schedule = { delayMs, action ->
            val wait = (now + delayMs) to action
            waits.add(wait)
            ({ waits.remove(wait) })
        },
    )

    private val battery = CallCards.batteryNotice("Battery 10 %", "Plug in the charger")
    private val ringing = CallCards.notice(CallCard(CallPhase.RINGING, "Ana"), words())

    private fun words() = CallWords("Answer", "Decline", { "On call $it" }, "End", "Calling", { "Ended $it" }, "Missed call")

    /** The time moves, and the waits that are due run, as the scheduler would. */
    private fun advance(ms: Long) {
        now += ms
        waits.filter { it.first <= now }.also { waits.removeAll(it) }.forEach { it.second() }
    }

    private val onGlass get() = given.lastOrNull()

    @Test
    fun `the pill is drawn as the call card is, with the level for the name and the hint in red`() {
        assertTrue(battery.battery)
        assertEquals("Battery 10 %", battery.card.name)
        assertEquals(listOf(CallSpan("Plug in the charger", CallRole.MISSED)), battery.spans)
        assertEquals("the red hint has no shape", "Plug in the charger", CallCards.plain(battery.spans))
        assertFalse("a call's card is not the pill", ringing.battery)
    }

    @Test
    fun `it shows at once with nothing else up, for 5 seconds, and then goes`() {
        assertTrue(pill.request(battery))
        assertEquals(battery, onGlass)
        advance(4_900)
        assertEquals(battery, onGlass)
        advance(100)
        assertNull(onGlass)
        assertTrue("no wait left over", waits.isEmpty())
    }

    @Test
    fun `it is for 5 seconds`() {
        assertEquals(5_000L, BatteryPill.SHOW_MS)
    }

    @Test
    fun `it comes once per projection, a second request is refused and shows nothing`() {
        assertTrue(pill.request(battery))
        assertFalse(pill.request(battery))
        advance(5_000)
        assertNull(onGlass)
        assertFalse("not even after it went", pill.request(battery))
        assertNull(onGlass)
        val count = given.size
        advance(10_000)
        assertEquals("nothing more is drawn", count, given.size)
    }

    @Test
    fun `a call card on the glass wins, and the pill shows when the call is over and its grey card gone`() {
        pill.callChanged(ringing)
        assertTrue(pill.request(battery))
        assertEquals("the call stays", ringing, onGlass)
        advance(60_000)
        assertEquals("the call is still the one on the glass a minute later", ringing, onGlass)
        val grey = CallCards.notice(CallCard(CallPhase.ENDED, "Ana", 60), words())
        pill.callChanged(grey)
        assertEquals("the grey card of the call over is a call card too", grey, onGlass)
        pill.callChanged(null)
        assertEquals(battery, onGlass)
        advance(4_999)
        assertEquals(battery, onGlass)
        advance(1)
        assertNull(onGlass)
    }

    @Test
    fun `a call that comes while the pill shows takes the glass, and the pill has its 5 seconds again after it`() {
        pill.request(battery)
        advance(3_000)
        pill.callChanged(ringing)
        assertEquals(ringing, onGlass)
        advance(20_000)
        pill.callChanged(null)
        assertEquals(battery, onGlass)
        advance(4_999)
        assertEquals(battery, onGlass)
        advance(1)
        assertNull(onGlass)
    }

    @Test
    fun `the call card ticking each second while a call goes on does not push the pill in`() {
        pill.callChanged(ringing)
        pill.request(battery)
        repeat(30) {
            advance(1_000)
            pill.callChanged(ringing)
            assertEquals(ringing, onGlass)
        }
    }

    @Test
    fun `the volume card up holds the pill back, and the pill shows when it goes`() {
        pill.volumeChanged(true)
        assertTrue(pill.request(battery))
        assertNull("nothing for the compositor but the call card slot's own, which is empty", onGlass)
        advance(30_000)
        assertNull(onGlass)
        pill.volumeChanged(false)
        assertEquals(battery, onGlass)
        advance(5_000)
        assertNull(onGlass)
    }

    @Test
    fun `a volume card that comes while the pill shows holds it, and it has its 5 seconds again`() {
        pill.request(battery)
        advance(2_000)
        pill.volumeChanged(true)
        assertNull(onGlass)
        advance(10_000)
        pill.volumeChanged(false)
        assertEquals(battery, onGlass)
        advance(4_999)
        assertEquals(battery, onGlass)
        advance(1)
        assertNull(onGlass)
    }

    @Test
    fun `a call over a volume card keeps the call as the feed gave it, and the pill comes when both are gone`() {
        pill.request(battery)
        pill.volumeChanged(true)
        pill.callChanged(ringing)
        assertEquals(ringing, onGlass)
        pill.callChanged(null)
        assertNull("the volume card is still up", onGlass)
        pill.volumeChanged(false)
        assertEquals(battery, onGlass)
    }

    @Test
    fun `a wait that wakes early waits again for what is left`() {
        pill.request(battery)
        now += 4_000
        val early = waits.single().second
        waits.clear()
        early()
        assertEquals(battery, onGlass)
        assertEquals(1, waits.size)
        advance(1_000)
        assertNull(onGlass)
    }

    @Test
    fun `dropping it cancels the wait and tells nobody`() {
        pill.request(battery)
        val count = given.size
        pill.drop()
        assertTrue(waits.isEmpty())
        assertEquals(count, given.size)
    }

    // ── the words ─────────────────────────────────────────────────────────────────────────────────────────

    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `the pill's words are in the four languages, the level with the real number`() {
        val folders = listOf("values", "values-pt", "values-es", "values-hi")
        for (folder in folders) {
            assertTrue(folder, "%1\$d" in string(folder, "battery_pill_level"))
            assertTrue(folder, string(folder, "battery_pill_hint").isNotBlank())
        }
        assertEquals("Battery %1\$d %%", string("values", "battery_pill_level"))
        assertEquals("Bateria %1\$d %%", string("values-pt", "battery_pill_level"))
        assertEquals("Ligue o carregador", string("values-pt", "battery_pill_hint"))
        assertEquals("Plug in the charger", string("values", "battery_pill_hint"))
    }
}
