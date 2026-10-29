package com.reau.app.aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The media ack goes for data buffers only (types 0 and 1), never for the setup, start, stop and focus messages of a media channel. */
class AapMessageHandlerTypeTest {
    @Test
    fun `only the media indications are acknowledged`() {
        assertTrue(AapMessageHandlerType.isMediaData(0))
        assertTrue(AapMessageHandlerType.isMediaData(1))
        for (type in listOf(2, 3, 7, 0x8000, 0x8001, 0x8002, 0x8003, 0x8004, 0x8007, 0x8008, -1)) {
            assertFalse("type $type", AapMessageHandlerType.isMediaData(type))
        }
    }
}
