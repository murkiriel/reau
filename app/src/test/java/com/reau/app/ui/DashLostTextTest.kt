package com.reau.app.ui

import com.reau.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ride logs of 20/10/2026: the dash's Wi-Fi went away for seconds to minutes; the screen says how long, and what to do after a minute. */
class DashLostTextTest {
    @Test
    fun `the seconds since the drop come whole`() {
        assertEquals(12L, DashLostText.seconds(goneAtMs = 100_000, nowMs = 112_900))
        assertEquals(0L, DashLostText.seconds(goneAtMs = 100_000, nowMs = 100_000))
        assertEquals(125L, DashLostText.seconds(goneAtMs = 0, nowMs = 125_000))
    }

    @Test
    fun `an unknown drop or one after now says no seconds, never a negative number`() {
        assertNull(DashLostText.seconds(null, 100_000))
        assertNull(DashLostText.seconds(goneAtMs = 105_000, nowMs = 100_000)) // -5 s
        val (res, secs) = DashLostText.text(goneAtMs = 105_000, nowMs = 100_000)
        assertEquals(R.string.sub_dash_lost, res)
        assertNull(secs)
    }

    @Test
    fun `with the seconds known the text that carries them is the one used`() {
        val (res, secs) = DashLostText.text(goneAtMs = 100_000, nowMs = 112_000)
        assertEquals(R.string.sub_dash_lost_for, res)
        assertEquals(12L, secs)
    }

    private fun strings(dir: String) = File("src/main/res/$dir/strings.xml").readText()

    @Test
    fun `both languages carry the seconds and say what to do after a minute`() {
        val en = strings("values")
        val pt = strings("values-pt")
        assertTrue(en, Regex("""name="sub_dash_lost_for">[^<]*%1\${'$'}d s ago[^<]*over a minute[^<]*key""").containsMatchIn(en))
        assertTrue(pt, Regex("""name="sub_dash_lost_for">[^<]*há %1\${'$'}d s[^<]*1 minuto[^<]*chave""").containsMatchIn(pt))
        // the notification has no counter that ticks: the same advice, no seconds
        assertTrue(Regex("""name="notif_text_dash_lost">[^<]*over a minute[^<]*key""").containsMatchIn(en))
        assertTrue(Regex("""name="notif_text_dash_lost">[^<]*1 minuto[^<]*chave""").containsMatchIn(pt))
    }
}
