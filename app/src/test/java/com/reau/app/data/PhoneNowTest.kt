package com.reau.app.data

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** What the line that says a link ended knows of the phone. */
class PhoneNowTest {
    @Before
    fun before() = PhoneNow.testOnlyReset()

    @After
    fun after() = PhoneNow.testOnlyReset()

    @Test
    fun `nothing heard yet is a question mark for each part`() {
        assertEquals("charger ?, screen ?, doze ?", PhoneNow.text())
    }

    @Test
    fun `the phone's broadcasts say the screen, the charger and doze`() {
        PhoneNow.heard(Intent.ACTION_POWER_CONNECTED, dozing = false)
        PhoneNow.heard(Intent.ACTION_SCREEN_ON, dozing = false, nowMs = 1_000)
        assertEquals("charger in, screen on, doze off, screen event 0.0 s before", PhoneNow.text(nowMs = 1_000))
        PhoneNow.heard(Intent.ACTION_POWER_DISCONNECTED, dozing = false)
        PhoneNow.heard(Intent.ACTION_SCREEN_OFF, dozing = true, nowMs = 2_000)
        assertEquals("charger out, screen off, doze on, screen event 0.5 s before", PhoneNow.text(nowMs = 2_500))
    }

    @Test
    fun `the unlock is a screen event too, and a charger or doze broadcast is not`() {
        PhoneNow.heard(Intent.ACTION_USER_PRESENT, dozing = null, nowMs = 5_000)
        assertEquals("charger ?, screen ?, doze ?, screen event 2.3 s before", PhoneNow.text(nowMs = 7_300))
        PhoneNow.testOnlyReset()
        PhoneNow.heard(Intent.ACTION_POWER_CONNECTED, dozing = false, nowMs = 5_000)
        assertEquals("charger in, screen ?, doze off", PhoneNow.text(nowMs = 7_300))
    }

    @Test
    fun `a broadcast about something else keeps what was known, and a part not read keeps its last word`() {
        PhoneNow.update(charging = true, screenOn = true, dozing = false)
        PhoneNow.heard(Intent.ACTION_BATTERY_LOW, dozing = null)
        assertEquals("charger in, screen on, doze off", PhoneNow.text())
        PhoneNow.update(charging = null, screenOn = false, dozing = null)
        assertEquals("charger in, screen off, doze off", PhoneNow.text())
    }
}
