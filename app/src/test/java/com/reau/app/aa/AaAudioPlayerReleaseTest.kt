package com.reau.app.aa

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit of 20/10/2026 (bug 17): `release()` let go of the AudioTrack on the caller's thread while the lane's thread could be
 * inside `write`, a native crash on some phones. Now only the lane's thread lets its speaker go, after its last write.
 */
class AaAudioPlayerReleaseTest {
    /** A speaker that notes whether a call came while a write was in progress. */
    private class Probe(private val holdWrite: CountDownLatch? = null) : AaAudioPlayer.PhoneSpeaker {
        val inWrite = AtomicBoolean(false)
        val writeStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val releasedWhileWriting = AtomicBoolean(false)
        val writes = AtomicInteger()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun play() { calls += "play" }
        override fun write(data: ByteArray) {
            inWrite.set(true)
            writes.incrementAndGet()
            writeStarted.countDown()
            holdWrite?.await(5, TimeUnit.SECONDS)
            inWrite.set(false)
        }
        override fun pause() { calls += "pause" }
        override fun flush() { calls += "flush" }
        override fun stop() { calls += "stop" }
        override fun release() {
            if (inWrite.get()) releasedWhileWriting.set(true)
            calls += "release"
            released.countDown()
        }
        override val routedDeviceType: Int? = null
    }

    private val log = mutableListOf<String>()

    private fun pcm() = AaAudioFeed.Pcm(AaAudioFeed.Stream.MEDIA, ByteArray(3_840))

    @Test
    fun `the speaker is let go by the lane's thread, once the write that was going has come back`() {
        val hold = CountDownLatch(1)
        val probe = Probe(hold)
        val player = AaAudioPlayer({ log += it }, { probe })
        player.play(pcm())
        assertTrue("the lane never wrote", probe.writeStarted.await(2, TimeUnit.SECONDS))

        // release() asks the lane to stop; the write is stuck, so the lane (and the speaker) are still there when it gives up waiting.
        val releaser = Thread { player.release() }.apply { start() }
        Thread.sleep(150)
        assertFalse("released under a write that is still going", probe.released.count == 0L)
        hold.countDown() // the write comes back
        releaser.join(3_000)
        assertTrue("the speaker was never let go", probe.released.await(2, TimeUnit.SECONDS))
        assertFalse("the speaker was released while a write was inside it", probe.releasedWhileWriting.get())
        assertTrue("got ${probe.calls}", probe.calls.indexOf("stop") < probe.calls.indexOf("release"))
    }

    @Test
    fun `a lane with nothing being written is let go at once, and not twice`() {
        val probe = Probe()
        val player = AaAudioPlayer({ log += it }, { probe })
        player.play(pcm())
        assertTrue(probe.writeStarted.await(2, TimeUnit.SECONDS))
        player.release()
        assertTrue(probe.released.await(2, TimeUnit.SECONDS))
        player.release() // a second release finds nothing to let go
        assertEquals(1, probe.calls.count { it == "release" })
    }

    @Test
    fun `nothing is queued after the player is released`() {
        val probe = Probe()
        val player = AaAudioPlayer({ log += it }, { probe })
        player.release()
        player.play(pcm())
        Thread.sleep(100)
        assertEquals(0, probe.writes.get())
    }
}
