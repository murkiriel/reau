package com.reau.app.service

import android.net.wifi.WifiManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The phone's Wi-Fi radio as Android's broadcast tells it, on its way to the projection that runs. */
class WifiRadioTest {
    private val heard = mutableListOf<Boolean>()

    @After
    fun tearDown() {
        WifiRadio.listener = null
    }

    @Test
    fun `enabled is on, disabled and disabling are off, and coming up or unknown say nothing yet`() {
        assertEquals(true, WifiRadio.of(WifiManager.WIFI_STATE_ENABLED))
        assertEquals(false, WifiRadio.of(WifiManager.WIFI_STATE_DISABLED))
        assertEquals("off from the moment it starts going down", false, WifiRadio.of(WifiManager.WIFI_STATE_DISABLING))
        assertNull("the enabled that follows says it", WifiRadio.of(WifiManager.WIFI_STATE_ENABLING))
        assertNull(WifiRadio.of(WifiManager.WIFI_STATE_UNKNOWN))
        assertNull(WifiRadio.of(-1))
    }

    @Test
    fun `what is heard reaches the listener, and a state that says nothing does not`() {
        WifiRadio.listener = { heard += it }
        WifiRadio.heard(false)
        WifiRadio.heard(null)
        WifiRadio.heard(true)
        assertEquals(listOf(false, true), heard)
    }

    @Test
    fun `with no projection listening, nothing happens`() {
        WifiRadio.heard(true)
        assertEquals(emptyList<Boolean>(), heard)
    }

    @Test
    fun `a projection going away late gives back only its own listener`() {
        val old: (Boolean) -> Unit = { heard += it }
        val next: (Boolean) -> Unit = { heard += !it }
        WifiRadio.listener = old
        WifiRadio.listener = next
        WifiRadio.release(old)
        assertSame(next, WifiRadio.listener)
        WifiRadio.heard(true)
        assertEquals(listOf(false), heard)
        WifiRadio.release(next)
        assertNull(WifiRadio.listener)
    }
}
