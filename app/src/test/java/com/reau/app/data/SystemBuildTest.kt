package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The maker's system version in the ride log's header line (26/11/2026): `Build.DISPLAY` and `Build.VERSION.INCREMENTAL`, short, on one line, and never the name
 * of whoever built an engineering image. The values below are made up in the shape of the real ones.
 */
class SystemBuildTest {
    @Test
    fun `the line gives the display and the incremental`() {
        assertEquals("display=BP2A.250605.031.A3 incremental=OS3.0.12.0.WOCCNXM", SystemBuild.line("BP2A.250605.031.A3", "OS3.0.12.0.WOCCNXM"))
    }

    @Test
    fun `a label that is missing or blank is a question mark`() {
        assertEquals("display=? incremental=?", SystemBuild.line(null, "  "))
        assertEquals("display=? incremental=V12", SystemBuild.line("", "V12"))
    }

    @Test
    fun `a space or a character outside printable ASCII becomes an underscore, so the label stays one word on one line`() {
        assertEquals("RMX3941_16.0.0.1_(EX01)", SystemBuild.label("RMX3941 16.0.0.1 (EX01)"))
        assertEquals("a_b_c", SystemBuild.label("a\tb\nc"))
        assertEquals("a_b", SystemBuild.label("aéb"))
    }

    @Test
    fun `a label is cut at 40 characters`() {
        val long = "X".repeat(100)
        assertEquals(40, SystemBuild.label(long).length)
        assertEquals("Y".repeat(40), SystemBuild.label("Y".repeat(40)))
        assertEquals(40, SystemBuild.label("Z".repeat(41)).length)
    }

    @Test
    fun `an engineering build's label is not written, it carries the name of who built it`() {
        assertEquals("eng-build", SystemBuild.label("eng.someone.20250101.120000"))
        assertEquals("eng-build", SystemBuild.label("ENG.someone.20250101.120000"))
        assertEquals("engine.1", SystemBuild.label("engine.1"))
    }

    @Test
    fun `the engineering mark is found anywhere in the label, and a user at host too`() {
        assertEquals("eng-build", SystemBuild.label("AP1A.240405.002_eng.alice.20240610.12121"))
        assertEquals("eng-build", SystemBuild.label("AP1A.240405.002 eng.alice.20240610 test-keys"))
        assertEquals("eng-build", SystemBuild.label("AP1A.240405.002.alice@buildhost"))
        assertEquals("a label that only has the letters is kept", "AP1A.240405.002_engine.1", SystemBuild.label("AP1A.240405.002 engine.1"))
    }
}
