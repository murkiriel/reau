package com.reau.app.service

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone reader makes of what Android says: the battery's percentage, a Starlink's Wi-Fi, the Wi-Fi's bars and the mobile generation. */
class PhoneReaderTest {
    @Test
    fun `the battery is a percentage of its scale, null when Android does not say`() {
        assertEquals(87, PhoneReader.percentOf(87, 100))
        assertEquals(50, PhoneReader.percentOf(127, 254))
        assertEquals(100, PhoneReader.percentOf(300, 100))
        assertEquals(0, PhoneReader.percentOf(0, 100))
        assertNull(PhoneReader.percentOf(-1, 100))
        assertNull(PhoneReader.percentOf(50, 0))
    }

    @Test
    fun `only a Wi-Fi with Starlink in its name is a Starlink`() {
        assertTrue(PhoneReader.isStarlink("STARLINK"))
        assertTrue(PhoneReader.isStarlink("\"My Starlink 2\""))
        assertFalse(PhoneReader.isStarlink("RE_Tripper_04"))
        assertFalse(PhoneReader.isStarlink("<unknown ssid>"))
        assertFalse(PhoneReader.isStarlink(null))
    }

    @Test
    fun `the Wi-Fi's bars follow its dBm from 4 at -55 to 0 under -90`() {
        assertEquals(4, PhoneReader.wifiBars(-30))
        assertEquals(4, PhoneReader.wifiBars(-55))
        assertEquals(3, PhoneReader.wifiBars(-56))
        assertEquals(3, PhoneReader.wifiBars(-65))
        assertEquals(2, PhoneReader.wifiBars(-75))
        assertEquals(1, PhoneReader.wifiBars(-90))
        assertEquals(0, PhoneReader.wifiBars(-91))
    }

    @Test
    fun `the mobile network's generation by Android's data type`() {
        assertEquals("5G", PhoneReader.generationOf(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals("4G", PhoneReader.generationOf(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("3G", PhoneReader.generationOf(TelephonyManager.NETWORK_TYPE_HSPAP))
        assertEquals("3G", PhoneReader.generationOf(TelephonyManager.NETWORK_TYPE_UMTS))
        assertEquals("2G", PhoneReader.generationOf(TelephonyManager.NETWORK_TYPE_EDGE))
        assertNull(PhoneReader.generationOf(TelephonyManager.NETWORK_TYPE_UNKNOWN))
    }
}
