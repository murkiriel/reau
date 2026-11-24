package com.reau.app.settings

import com.reau.app.ui.AlertRoute
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permissions page's "Liberar" and "Pedir as que faltam" (24/11/2026): after two refusals Android refuses by itself, with no window, so the
 * button did nothing the rider could see. When something asked comes back refused too fast for a window to have been shown, the app's page in
 * Android's settings opens (the same 400 ms as the alert card's).
 */
class OpenAppPageAfterTest {
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val mic = "android.permission.RECORD_AUDIO"
    private val limit = AlertRoute.WINDOW_MIN_MS

    @Test
    fun `the limit is the alert card's 400 ms`() {
        assertEquals(400L, limit)
    }

    @Test
    fun `everything granted opens nothing, however quick`() {
        assertEquals(false, AppPermissions.openAppPageAfter(mapOf(fine to true, mic to true), 0))
        assertEquals(false, AppPermissions.openAppPageAfter(mapOf(mic to true), 10))
    }

    @Test
    fun `a refusal that came back too quick for a window opens the app's page`() {
        assertEquals(true, AppPermissions.openAppPageAfter(mapOf(mic to false), 20))
        assertEquals(true, AppPermissions.openAppPageAfter(mapOf(fine to true, mic to false), 20))
        assertEquals("one refused among several is enough", true, AppPermissions.openAppPageAfter(mapOf(fine to false, mic to true, "a" to true), 0))
    }

    @Test
    fun `a refusal after a window the rider read and answered opens nothing`() {
        assertEquals(false, AppPermissions.openAppPageAfter(mapOf(mic to false), 2_500))
        assertEquals(false, AppPermissions.openAppPageAfter(mapOf(fine to false, mic to false), limit + 1))
    }

    @Test
    fun `no answer at all opens nothing`() {
        assertEquals("the window was cancelled", false, AppPermissions.openAppPageAfter(emptyMap(), 0))
    }

    @Test
    fun `the edge is the 400 ms itself, which already counts as a window`() {
        assertEquals(true, AppPermissions.openAppPageAfter(mapOf(mic to false), limit - 1))
        assertEquals(false, AppPermissions.openAppPageAfter(mapOf(mic to false), limit))
    }

    @Test
    fun `the screen marks the launch, asks the decision after the answer and opens the app's page`() {
        val main = File("src/main/java/com/reau/app/MainActivity.kt").readText()
        assertTrue(main.contains("askedPermissionsAtMs = com.reau.app.util.MonotonicClock.nowMs()\n        askPermissions.launch(it.toTypedArray())"))
        assertTrue(main.contains("        permissionsAnswered(answers)\n"))
        assertTrue(main.contains("if (!com.reau.app.settings.AppPermissions.openAppPageAfter(answers, elapsed)) return"))
        assertTrue(main.contains("        PermissionRows.openAppSettings(this)\n    }"))
    }
}
