package com.reau.app.service

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may put a video loop in the slot of [LiveSides]. The sequence it protects: the panel drops ([LoopSlot.stop], on the controller's queue) at the same
 * instant the pace is changed ([LoopSlot.replace], from the live settings): the new loop must not be built over a session with no panel, and the next
 * [LoopSlot.start] must not find a loop to overwrite without stopping.
 */
class LoopSlotTest {
    private class Fake(val name: String) {
        var stopped = 0
    }

    private val stops = java.util.Collections.synchronizedList(mutableListOf<Fake>())
    private val slot = LoopSlot<Fake> { it.stopped++; stops += it }

    @Test
    fun `a start puts the loop in the slot`() {
        val a = Fake("a")
        slot.start { a }
        assertSame(a, slot.loop)
        assertTrue(stops.isEmpty())
    }

    @Test
    fun `a start stops the loop left over before it builds the next, so there is never a second one running`() {
        val a = Fake("a")
        slot.start { a }
        var stoppedWhenBuilding = -1
        val b = Fake("b")
        slot.start {
            stoppedWhenBuilding = a.stopped
            b
        }
        assertEquals("the old one was already stopped when the new one was built", 1, stoppedWhenBuilding)
        assertEquals(listOf(a), stops)
        assertSame(b, slot.loop)
    }

    @Test
    fun `a start that builds nothing leaves the slot empty`() {
        val a = Fake("a")
        slot.start { a }
        slot.start { null }
        assertNull(slot.loop)
        assertEquals(listOf(a), stops)
    }

    @Test
    fun `a stop empties the slot and stops the loop once`() {
        val a = Fake("a")
        slot.start { a }
        slot.stop()
        slot.stop()
        assertNull(slot.loop)
        assertEquals(1, a.stopped)
    }

    @Test
    fun `a new pace with no loop builds nothing`() {
        var built = 0
        assertFalse(slot.replace { built++; Fake("x") })
        assertEquals(0, built)
        assertNull(slot.loop)
        // The panel dropped: stopped, then the new pace arrives.
        slot.start { Fake("a") }
        slot.stop()
        assertFalse(slot.replace { built++; Fake("x") })
        assertEquals("not rebuilt over a session with no panel", 0, built)
        assertNull(slot.loop)
    }

    @Test
    fun `a new pace replaces the running loop and stops it`() {
        val a = Fake("a")
        val b = Fake("b")
        slot.start { a }
        assertTrue(slot.replace { b })
        assertEquals(listOf(a), stops)
        assertSame(b, slot.loop)
    }

    @Test
    fun `a panel that drops while the new loop is being built gets that loop stopped, not kept`() {
        val a = Fake("a")
        val b = Fake("b")
        slot.start { a }
        val kept = slot.replace {
            slot.stop() // the drop, in the middle of the build
            b
        }
        assertFalse(kept)
        assertNull("no loop over a session with no panel", slot.loop)
        assertEquals("the old one and the one just built, each once", listOf(a, b), stops)
    }

    @Test
    fun `a new session that begins while the new loop is being built keeps its own loop and the late one is stopped`() {
        val a = Fake("a")
        val late = Fake("late")
        val session2 = Fake("session2")
        slot.start { a }
        val kept = slot.replace {
            slot.stop()
            slot.start { session2 } // the panel came back
            late
        }
        assertFalse(kept)
        assertSame(session2, slot.loop)
        assertEquals(listOf(a, late), stops)
        assertEquals("the new session's loop runs", 0, session2.stopped)
    }

    @Test
    fun `a pace change that builds nothing leaves the slot empty`() {
        val a = Fake("a")
        slot.start { a }
        assertFalse(slot.replace { null })
        assertNull(slot.loop)
        assertEquals(listOf(a), stops)
    }

    /** Many drops, starts and pace changes from three threads: every loop ever built is stopped but the one in the slot, none twice. */
    @Test
    fun `under concurrent starts, stops and pace changes no loop is lost or stopped twice`() {
        val built = java.util.Collections.synchronizedList(mutableListOf<Fake>())
        val make = { Fake("n").also { built += it } }
        val pool = Executors.newFixedThreadPool(3)
        val go = CountDownLatch(1)
        val done = CountDownLatch(3)
        pool.execute { go.await(); repeat(2_000) { slot.start(make); slot.stop() }; done.countDown() }
        pool.execute { go.await(); repeat(2_000) { slot.replace(make) }; done.countDown() }
        pool.execute { go.await(); repeat(2_000) { slot.replace(make); slot.stop() }; done.countDown() }
        go.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        slot.stop()
        assertNull(slot.loop)
        assertTrue("every loop built was stopped, once", built.all { it.stopped == 1 })
        assertEquals(built.size, stops.size)
    }

    @Test
    fun `the service keeps the loop only in the slot and takes both paths through it`() {
        val sides = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(sides.contains("private val loops = LoopSlot<VideoLoop> { it.stop() }"))
        assertFalse("no loop kept outside the slot", sides.contains("private var loop: VideoLoop?"))
        assertTrue(sides.contains("loops.start { live.newLoop() }"))
        assertTrue(sides.contains("override fun stopVideo() = loops.stop()"))
        val stream = sides.substringAfter("fun newStream(settings: REauSettings) {").substringBefore("fun followFallback")
        assertTrue(stream, stream.contains("loops.replace { newLoop() }"))
    }
}
