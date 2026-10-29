package com.reau.app.service

import com.reau.app.dash.WifiConnStatus
import com.reau.app.dash.WifiState
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit of 20/10/2026 (bug 11): the observer of the Wi-Fi's outcome was launched on a dispatcher and read the state with
 * `drop(1)`. A Wi-Fi manager that reached its final state before the observer first ran (the connection was already up, or
 * failed at once) had that state dropped, and the link stayed "connecting" for ever. The dispatcher here holds every task
 * until the test runs it, which is the order that went wrong.
 */
class WifiDashLinkUpFastWifiTest {
    /** Runs nothing until [runAll]: a coroutine that is dispatched starts only then. */
    private class Held : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runAll() {
            while (true) (queue.poll() ?: return).run()
        }
    }

    private val held = Held()
    private val scope = CoroutineScope(SupervisorJob() + held)
    private val wifi = MutableStateFlow(WifiState())
    private val ready = mutableListOf<String>()
    private val failed = mutableListOf<String>()

    private class Known(override var ssid: String = "RE_AB12_345678") : WifiDashLinkUp.Pairing {
        override val ssidPrefix = "RE_"
        override val password = "secret"
        override fun remember(ssid: String) { this.ssid = ssid }
        override fun discover(prefix: String): String? = null
    }

    @After
    fun tearDown() = scope.cancel()

    private fun linkUp(connect: () -> Unit) =
        WifiDashLinkUp(scope, wifi, { null }, { _, _, _ -> connect() }, { }, Known())

    @Test
    fun `a Wi-Fi that is connected by the time the observer first runs still reports it`() {
        val link = linkUp { wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_AB12_345678") }
        link.start({ ssid, _ -> ready += ssid }, { failed += it })
        held.runAll()
        assertEquals(listOf("RE_AB12_345678"), ready)
        assertTrue("got $failed", failed.isEmpty())
    }

    @Test
    fun `a Wi-Fi that fails at once still reports the failure`() {
        val link = linkUp { wifi.value = WifiState(WifiConnStatus.ERROR, ssid = "RE_AB12_345678", error = "no luck") }
        link.start({ ssid, _ -> ready += ssid }, { failed += it })
        held.runAll()
        assertEquals(listOf("no luck"), failed)
        assertTrue("got $ready", ready.isEmpty())
    }

    @Test
    fun `a state that was already final before the start is not taken for this attempt's outcome`() {
        // The previous attempt's ERROR is still the state when the next one begins; only what comes after counts.
        wifi.value = WifiState(WifiConnStatus.ERROR, ssid = "RE_AB12_345678", error = "the old failure")
        val link = linkUp { wifi.value = WifiState(WifiConnStatus.REQUESTING, ssid = "RE_AB12_345678") }
        link.start({ ssid, _ -> ready += ssid }, { failed += it })
        held.runAll()
        assertTrue("the old failure was reported: $failed", failed.isEmpty())
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_AB12_345678")
        held.runAll()
        assertEquals(listOf("RE_AB12_345678"), ready)
    }
}
