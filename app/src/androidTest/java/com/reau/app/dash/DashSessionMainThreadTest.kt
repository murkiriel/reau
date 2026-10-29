package com.reau.app.dash

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A [DashSession] talking to [FakeDashPeer], disconnected from the main thread: the one part of `DashSessionFakeDashTest` that needs
 * Android (that test runs on the JVM too). Observed in ride logs: the rider tapped "disconnect" on the screen, the goodbye was sent from
 * the main thread, Android refuses network there (NetworkOnMainThreadException), and the dash never heard that the session was over.
 */
@RunWith(AndroidJUnit4::class)
class DashSessionMainThreadTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var fakeDash: FakeDashPeer? = null

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
        fakeDash?.close()
    }

    @Test
    fun disconnectFromTheMainThreadStillSendsTheTeardownSequence() {
        val fake = FakeDashPeer().also { fakeDash = it }
        val s = DashSession(scope, socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) }).also { session = it }
        s.connect("DashFake")
        runBlocking {
            val deadline = System.currentTimeMillis() + 8_000
            while (s.state.value != DashState.READY && s.state.value != DashState.ERROR && System.currentTimeMillis() < deadline) delay(50)
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync { s.disconnect() }
        runBlocking { delay(500) }

        assertTrue("projectionStop", fake.countOf(0x05, 0x56) >= 1)
        assertTrue("exitSessionBoundary sent twice", fake.countOf(0x05, 0x17) >= 2)
        assertEquals(DashState.IDLE, s.state.value)
    }
}
