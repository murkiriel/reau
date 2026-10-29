package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android Auto's battery message (type 23) was an `E: Unsupported Control message type: 23` at 15 % and at 5 % (20/10/2026). */
class AaBatteryNoticeTest {
    @Test
    fun `the line says the level and what else the message carried, and nothing when it carried nothing`() {
        assertEquals("battery status from Android Auto: 15 %", AaBatteryNotice.line(15, null, null))
        assertEquals("battery status from Android Auto: 5 %, 600 s left, critical", AaBatteryNotice.line(5, 600, true))
        assertEquals("battery status from Android Auto: 40 %", AaBatteryNotice.line(40, null, false))
        assertEquals("battery status from Android Auto", AaBatteryNotice.line(null, null, null))
    }

    @Test
    fun `the control channel reads the message instead of calling it unsupported, and the line is not an error`() {
        val source = File("src/main/java/com/reau/app/aa/AapControl.kt").readText()
        val branch = source.substringAfter("MESSAGE_BATTERY_STATUS_NOTIFICATION_VALUE ->").substringBefore("else ->")
        assertTrue("the branch exists", branch.isNotBlank() && branch.length < source.length)
        assertTrue("it logs the line as news (AaLog.i)", branch.contains("AaLog.i(") && branch.contains("AaBatteryNotice.line("))
        assertFalse("and never as an error", branch.contains("AaLog.e(") || branch.contains("AaLog.w("))
    }
}
