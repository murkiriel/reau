package com.reau.app.dash

import com.reau.app.dash.protocol.Tlv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The key exchange (07 xx) before the dash confirmed the key: everything is taken, a rejection too (it starts the attempt over). */
class DashKeyExchangeTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `before the dash confirmed, the key and a rejection are taken as before`() {
        assertEquals(true, session.keyExchangeAccepted(Tlv(0x07, 0x00, ByteArray(128) { 1 })))
        assertEquals(true, session.keyExchangeAccepted(Tlv(0x07, 0x01, byteArrayOf(0))))
        assertEquals(true, session.keyExchangeAccepted(Tlv(0x07, 0x01, byteArrayOf(1))))
    }
}
