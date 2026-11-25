package com.reau.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The side of the road (20/11/2026): what decides it is [com.reau.app.dash.nav.DrivingSideSource] (DrivingSideSourceTest) and what it does to the arrows
 * is [com.reau.app.dash.nav.AaManeuvers] (AaManeuversTest). Where it is read from and handed to needs the live sides (a Context, the telephony service, the
 * dash session), so those lines are read from the source, as the other "...IsWired" tests do.
 */
class DrivingSideIsWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val live = File(main, "service/LiveSides.kt").readText()
    private val source = live.substringAfter("private val drivingSide = DrivingSideSource(").substringBefore("// The latest guidance snapshot")

    @Test
    fun `the country comes from the telephony service, the network first and the SIM second, and nothing is asked of the user`() {
        assertTrue(source, "networkCountry = { appContext.getSystemService(android.telephony.TelephonyManager::class.java)?.networkCountryIso }" in source)
        assertTrue(source, "simCountry = { appContext.getSystemService(android.telephony.TelephonyManager::class.java)?.simCountryIso }" in source)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("no permission was added for it", manifest.contains("READ_PHONE_NUMBERS") || manifest.contains("READ_PRIVILEGED_PHONE_STATE"))
    }

    @Test
    fun `the rider's choice is read from the live settings at each ask, so a change counts at once`() {
        assertTrue(source, "mode = { tuned?.drivingSideMode ?: com.reau.app.settings.DrivingSideMode.AUTO }" in source)
        assertTrue(source, "log = { RideDiagnostics.log(\"dash\", it) }" in source)
    }

    @Test
    fun `it is read when the projection starts, and a new choice is said at once`() {
        assertTrue(live, "            drivingSide.refresh()\n            // Behind the switch: Android Auto's guidance goes to the dash's own nav bubble." in live)
        assertTrue(live, "if (before.drivingSideMode != settings.drivingSideMode) drivingSide.refresh()" in live)
    }

    @Test
    fun `every guidance update carries the side to the dash's figures`() {
        assertTrue(live, "session.showGuidance(it, noNetwork = noNetwork.without(), imperial = navImperial(), drivingSide = drivingSide.side())" in live)
        assertTrue(live, "AaManeuvers.toDash(guidance, noNetwork, imperial = imperial, drivingSide = drivingSide)" in live)
    }
}
