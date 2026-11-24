package com.reau.app.settings

import com.reau.app.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permissions page: a button listing every permission and why REau uses it.
 * Every permission the manifest asks for has its own explanation; one the build adds that REau does not know (a
 * library's) still shows, with the library text, after the known ones; the special access (the battery exemption)
 * comes first, since it is the one the rider switches by hand.
 */
class AppPermissionsTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val declared = Regex("""<uses-permission\s+android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()

    @Test
    fun `every permission of the manifest has its own explanation`() {
        assertTrue("the manifest was read: $declared", declared.size >= 15)
        val unexplained = declared.filter { AppPermissions.explain(it).kind == AppPermissions.Kind.LIBRARY }
        assertEquals("no permission falls to the library text", emptyList<String>(), unexplained)
    }

    @Test
    fun `nearby devices is gone, since it never opened the Wi-Fi list`() {
        val nearby = "android.permission.NEARBY_WIFI_DEVICES"
        assertFalse("the manifest no longer asks for it", nearby in declared)
        assertEquals("the page no longer explains it", AppPermissions.Kind.LIBRARY, AppPermissions.explain(nearby).kind)
    }

    @Test
    fun `the internal receiver permission AndroidX adds is told as the library's`() {
        assertEquals(AppPermissions.Kind.LIBRARY, AppPermissions.explain("com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION").kind)
        assertEquals(AppPermissions.Kind.LIBRARY, AppPermissions.explain("android.permission.SOMETHING_NEW").kind)
    }

    @Test
    fun `the list puts the special access first, then the known ones in the catalog's order, then the rest`() {
        val requested = listOf(
            "com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "android.permission.RECORD_AUDIO",
            "android.permission.INTERNET",
        )
        val rows = AppPermissions.rows(
            requested = requested,
            granted = { it == "android.permission.INTERNET" },
            batteryExempt = true,
        )
        assertEquals(
            listOf(
                AppPermissions.BATTERY,
                // the ones that depend on the rider before the ones Android grants at install
                "android.permission.RECORD_AUDIO", "android.permission.INTERNET",
                "com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            rows.map { it.explained.permission },
        )
        assertTrue("battery exempt", rows[0].granted)
        assertFalse("microphone not granted", rows[1].granted)
        assertTrue("internet granted", rows[2].granted)
    }

    @Test
    fun `each permission is told once, even when two names share an explanation`() {
        val rows = AppPermissions.rows(
            requested = listOf(
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.INTERNET", "android.permission.INTERNET",
            ),
            granted = { false },
            batteryExempt = false,
        )
        assertEquals("the special access, the location (one row) and the internet", 3, rows.size)
        assertEquals(rows.size, rows.map { it.explained.permission }.toSet().size)
    }

    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"

    private fun locationRows(requested: List<String>, granted: Set<String>) =
        AppPermissions.rows(requested, { it in granted }, batteryExempt = false).filter { it.explained.permission in setOf(fine, coarse) }

    @Test
    fun `the two location permissions are one row, the precise one, with the location text`() {
        val rows = locationRows(listOf(coarse, fine), granted = emptySet())
        assertEquals(listOf(fine), rows.map { it.explained.permission })
        assertEquals(R.string.perm_location_title, rows.single().explained.title)
        assertEquals(R.string.perm_location_why, rows.single().explained.why)
        assertEquals("the order they were asked in does not matter", rows, locationRows(listOf(fine, coarse), granted = emptySet()))
    }

    @Test
    fun `the merged location row is granted when the precise one is, whatever the approximate one is`() {
        assertEquals(true, locationRows(listOf(fine, coarse), granted = setOf(fine)).single().granted)
        assertEquals(true, locationRows(listOf(fine, coarse), granted = setOf(fine, coarse)).single().granted)
        assertEquals("the approximate one alone does not open the Wi-Fi list", false, locationRows(listOf(fine, coarse), granted = setOf(coarse)).single().granted)
        assertEquals(false, locationRows(listOf(fine, coarse), granted = emptySet()).single().granted)
    }

    @Test
    fun `a location permission asked alone keeps its own row, as it is`() {
        assertEquals(listOf(fine to true), locationRows(listOf(fine), granted = setOf(fine)).map { it.explained.permission to it.granted })
        assertEquals(listOf(coarse to false), locationRows(listOf(coarse), granted = setOf(fine)).map { it.explained.permission to it.granted })
        assertEquals(listOf(coarse to true), locationRows(listOf(coarse), granted = setOf(coarse)).map { it.explained.permission to it.granted })
    }

    @Test
    fun `the merged row still asks for both location permissions and counts once`() {
        val rows = AppPermissions.rows(listOf(fine, coarse), { false }, batteryExempt = true)
        val row = rows.single { it.explained.permission == fine }
        assertEquals(AppPermissions.Ask.Runtime(listOf(fine, coarse)), AppPermissions.ask(row, 34, locationAllowed = true))
        assertEquals(1, AppPermissions.missing(rows, 34, locationAllowed = true))
        assertEquals(listOf(fine, coarse), AppPermissions.askAll(rows, 34, locationAllowed = true))
        assertEquals(0, AppPermissions.missing(rows, 34, locationAllowed = false))
    }

    @Test
    fun `a permission the catalog does not know is a library's, in any state`() {
        val rows = AppPermissions.rows(declared, { true }, batteryExempt = false)
        assertEquals(emptyList<String>(), rows.map { it.explained.permission }.filter { it.startsWith("special:") && it != AppPermissions.BATTERY })
        assertEquals(AppPermissions.Kind.LIBRARY, AppPermissions.explain("special:unknown").kind)
    }

    @Test
    fun `the ones Android grants at install are told apart from the ones the rider grants`() {
        assertEquals(AppPermissions.Kind.INSTALL, AppPermissions.explain("android.permission.INTERNET").kind)
        assertEquals(AppPermissions.Kind.ASKED, AppPermissions.explain("android.permission.RECORD_AUDIO").kind)
        assertEquals(AppPermissions.Kind.ASKED, AppPermissions.explain("android.permission.POST_NOTIFICATIONS").kind)
        assertEquals(AppPermissions.Kind.SPECIAL, AppPermissions.explain(AppPermissions.BATTERY).kind)
    }
}
