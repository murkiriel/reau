package com.reau.app.dash

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the framework-calling half of DashWifiManager.findAlreadyConnectedDashNetwork
 * (the pure decision inside it, candidateMatchesActive, has full unit coverage in
 * DashWifiManagerHelpersTest — this only exercises the real ConnectivityManager/WifiManager
 * enumeration around it, which needs a real Context and can't be unit-tested). Best-effort: the
 * test environment is never actually joined to a "RE_*" dash network, so this can only assert
 * "doesn't crash", same pattern as DashWifiSuggestionsTest.
 *
 * The emulator's own Wi-Fi stands for the dash network in the cases that follow the first one, which watch what the manager does when that
 * network goes: a request let go stays let go, and a new network takes the place of the one that died (plan 21's case of the
 * lost network that comes back as another one).
 *
 * To run later (not run when written; the machine had no device):
 *  - an emulator with Wi-Fi on (the default image), booted, and the app installed with its test APK; `ANDROID_SERIAL=<emulator serial>`
 *    for adb and `REAU_EMU_SERIAL=<emulator serial>` for `scripts/emu/run_instrumented.py DashWifiManagerTest`, which runs one process
 *    per class and never touches a phone;
 *  - the app is put on screen by the tests themselves, and they give it the location permission and switch the system's location on
 *    (Android hands the Wi-Fi name only to an app on screen with that permission); `svc wifi disable` / `svc wifi enable` take the
 *    network away and give it back, so the emulator must be free of other runs meanwhile;
 *  - a test that cannot read the Wi-Fi name is skipped (assumption), never passed. No `dumpsys deviceidle` is needed here.
 */
@RunWith(AndroidJUnit4::class)
class DashWifiManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var manager: DashWifiManager? = null

    @After
    fun tearDown() {
        manager?.disconnect()
        scope.cancel()
    }

    private fun shell(command: String) {
        val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(what, condition())
    }

    /**
     * A dash network the phone had joined by itself is reused without a
     * request of REau's own, and nothing told REau when it went: every try for 15 minutes bound to the
     * same dead network (EPERM). The emulator's own Wi-Fi stands for that dash network here.
     */
    @Test
    fun aDashNetworkJoinedBeforeIsWatchedAndItsLossIsNoticed() {
        // The Wi-Fi name is read with the location permission, as REau does on the phone.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_FINE_LOCATION)
        shell("cmd location set-location-enabled true")
        Thread.sleep(1_000)
        // Android hands the Wi-Fi name only to an app on screen, as REau is when the rider opens it near the bike.
        androidx.test.core.app.ActivityScenario.launch(com.reau.app.MainActivity::class.java).use {
            val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
            @Suppress("DEPRECATION")
            val ssid = wifi.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            assumeTrue("the emulator's Wi-Fi name is readable", ssid != null)
            val dash = DashWifiManager(context, scope).also { manager = it }
            dash.connect(ssid!!)
            await("joined through the network already there", 10_000) {
                dash.state.value.status == WifiConnStatus.CONNECTED && dash.network != null
            }
            try {
                shell("svc wifi disable")
                await("the loss noticed", 20_000) { dash.network == null }
            } finally {
                shell("svc wifi enable")
            }
        }
    }

    @Test
    fun connectAgainstANonExistentSsidExercisesTheAlreadyConnectedScanWithoutCrashing() {
        val wifiManager = DashWifiManager(context, scope).also { manager = it }
        // No real dash nearby in a test environment — this SSID is guaranteed not to be the one
        // the test device is actually joined to, so findAlreadyConnectedDashNetwork() falls
        // through to a real requestNetwork() call. The point of this test is that the whole
        // connect() path (including the "already connected?" scan) runs clean end to end.
        wifiManager.connect("RE_NONEXISTENT_TEST_SSID", "12345678")
    }

    // ── the network of the request, when it goes ───────────────────────────────────────────────────────────

    @Suppress("DEPRECATION") // WifiManager.connectionInfo: the same read as the first test, the only one that gives the name of the joined Wi-Fi without a callback
    private fun joinedSsid(): String? {
        val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
        return wifi.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    /**
     * Runs [block] with the app on screen, the location permission given and the emulator's Wi-Fi name read (the name of the dash network
     * of these tests). Skipped, not passed, where the name cannot be read.
     */
    private fun withTheEmulatorWifiAsTheDash(block: (String) -> Unit) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_FINE_LOCATION)
        shell("cmd location set-location-enabled true")
        Thread.sleep(1_000)
        androidx.test.core.app.ActivityScenario.launch(com.reau.app.MainActivity::class.java).use {
            val ssid = joinedSsid()
            assumeTrue("the emulator's Wi-Fi name is readable", ssid != null)
            block(ssid!!)
        }
    }

    /** The emulator's Wi-Fi switched on again, and waited for until it has rejoined [ssid], so the next test finds it as it was. */
    private fun wifiBack(ssid: String) {
        shell("svc wifi enable")
        await("the emulator's Wi-Fi rejoined the network it had", 40_000) { joinedSsid() == ssid }
    }

    /** A manager that has the emulator's Wi-Fi as the dash network, joined (through the network already there, as the first test). */
    private fun joinedManager(ssid: String): DashWifiManager {
        val dash = DashWifiManager(context, scope).also { manager = it }
        dash.connect(ssid)
        await("joined through the network already there", 10_000) {
            dash.state.value.status == WifiConnStatus.CONNECTED && dash.network != null
        }
        return dash
    }

    /** The loss of the joined network noticed: no network, and the manager says it is getting it back. */
    private fun awaitTheLossNoticed(dash: DashWifiManager) {
        await("the loss noticed", 20_000) { dash.network == null && dash.state.value.status == WifiConnStatus.REQUESTING }
    }

    private fun assertLetGo(dash: DashWifiManager, moment: String) {
        assertNull("no network $moment", dash.network)
        assertEquals("the state stays idle $moment", WifiConnStatus.IDLE, dash.state.value.status)
        assertFalse("nothing is being got back $moment", dash.reconnectUnderWay())
    }

    /**
     * A request let go takes its network and its state with it, and what the network does afterwards is no loss: the Wi-Fi going
     * away, and coming back, after disconnect() must not start a reconnection, ask for the network, or take it. (On the phone the
     * system drops the network of a request when the request is released; here the Wi-Fi is taken away by hand after the release.)
     */
    @Test
    fun aRequestLetGoStaysLetGoWhateverTheNetworkDoesAfterIt() {
        withTheEmulatorWifiAsTheDash { ssid ->
            val dash = joinedManager(ssid)

            dash.disconnect()
            assertLetGo(dash, "straight after the release")

            try {
                shell("svc wifi disable")
                // Longer than the pause before a new request (RECONNECT_DELAY) and than a look or two of the watch over a loss.
                Thread.sleep(RECONNECT_DELAY + SETTLE_MS)
                assertLetGo(dash, "while the Wi-Fi is away")
            } finally {
                wifiBack(ssid)
            }
            Thread.sleep(RECONNECT_DELAY + SETTLE_MS)
            assertLetGo(dash, "after the Wi-Fi came back")
        }
    }

    /**
     * The network lost is replaced by a new one. While it lasts the manager knows it is getting the network back (a fresh connect()
     * is not what the projection does then: that would put the first connection's long wait and its giving up back), and when the Wi-Fi
     * returns the network it holds is another one, not the dead one, with the state connected again.
     */
    @Test
    fun aNewNetworkTakesThePlaceOfTheDeadOneAndTheReconnectionIsKnownWhileItLasts() {
        withTheEmulatorWifiAsTheDash { ssid ->
            val dash = joinedManager(ssid)
            val dead = checkNotNull(dash.network)

            try {
                shell("svc wifi disable")
                awaitTheLossNoticed(dash)
                await("the reconnection known to be under way", 5_000) { dash.reconnectUnderWay() }
            } finally {
                // At once: the Wi-Fi is back before the first new request (RECONNECT_DELAY), and if it is not, the watch over the loss or the next request finds it.
                wifiBack(ssid)
            }

            await("a network again", 60_000) { dash.state.value.status == WifiConnStatus.CONNECTED && dash.network != null }
            val fresh = checkNotNull(dash.network)
            assertNotEquals("the dead network is not the one held again", dead, fresh)
            assertTrue("the state names the network again", dash.state.value.ssid.isNotBlank())
            assertFalse("a reconnection that ended is no longer under way", dash.reconnectUnderWay())
        }
    }

    /**
     * The request let go in the middle of a loss: the pause before the next request, and the watch over the loss that asks again when the
     * dash shows up, both end with disconnect(), and the network that comes back afterwards is not taken.
     */
    @Test
    fun aRequestLetGoDuringALossIsNotBroughtBackByTheNetworkThatReturns() {
        withTheEmulatorWifiAsTheDash { ssid ->
            val dash = joinedManager(ssid)

            try {
                shell("svc wifi disable")
                awaitTheLossNoticed(dash)
                dash.disconnect()
                assertLetGo(dash, "straight after the release in the loss")
            } finally {
                wifiBack(ssid)
            }

            // The pause of the reconnection and two looks of the watch over the loss would both have fired by now.
            Thread.sleep(RECONNECT_DELAY + SETTLE_MS)
            assertLetGo(dash, "after the network returned")
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    /**
     * 19/11/2026, 01:26: Android let the first request go ("unavailable") 1.8 s after it went out, while the phone had joined the dash's Wi-Fi by
     * itself 0.5 s before, and REau declared a stuck dash. Now the network that is up is adopted: no refusal, the state is connected, and no
     * request is left open. The emulator's Wi-Fi stands for the dash network the phone joined by itself; the request of a name that is not there
     * stands for the request Android let go, and its callback is told "unavailable" by hand. Then a socket refused on that network (EPERM) takes the
     * way it always took: the network is dropped and asked for again, never adopted twice.
     */
    @Test
    fun aRequestLetGoWithTheDashNetworkAlreadyUpAdoptsItAndALaterEpermAsksAgain() {
        withTheEmulatorWifiAsTheDash { ssid ->
            val dash = DashWifiManager(context, scope).also { manager = it }
            dash.connect("RE_NONEXISTENT_TEST_SSID", "12345678")
            await("a request out", 5_000) { dash.state.value.status == WifiConnStatus.REQUESTING }
            // From here the name asked for is the emulator's, as the dash's is once it is learned.
            field(dash, "pendingSsid").set(dash, ssid)
            val ended = field(dash, "networkCallback").get(dash) as android.net.ConnectivityManager.NetworkCallback
            ended.onUnavailable()

            assertEquals(WifiConnStatus.CONNECTED, dash.state.value.status)
            assertNull("no refusal", dash.state.value.error)
            val adopted = checkNotNull(dash.network)
            assertNotEquals("the request that ended is not the callback in hand any more", ended, field(dash, "networkCallback").get(dash))

            dash.bindResult(adopted, refused = true)
            assertEquals("asked for again after the refusal", WifiConnStatus.REQUESTING, dash.state.value.status)
            assertNotEquals("the refused network is not taken again", adopted, dash.network)

            dash.disconnect()
            assertLetGo(dash, "after the release")
        }
    }

    private companion object {
        /** Added to the pause before a new request when a test waits to see that nothing happens: a few looks of the watch over a loss (every 2 s). */
        const val SETTLE_MS = 4_000L
    }
}
