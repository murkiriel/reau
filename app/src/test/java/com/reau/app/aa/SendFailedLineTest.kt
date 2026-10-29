package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/** `E: send failed :: Broken pipe` with a stack at each try Android Auto refused (20/10/2026): one warning line now. */
class SendFailedLineTest {
    @Test
    fun `a broken pipe is one line with the reason`() {
        val line = sendFailedLine(IOException("Broken pipe"))
        assertEquals("send failed: IOException: Broken pipe", line)
        assertFalse(line, "\n" in line || " at " in line)
    }

    @Test
    fun `a failure with no message still says what it was`() {
        assertEquals("send failed: IOException: null", sendFailedLine(IOException()))
    }
}
