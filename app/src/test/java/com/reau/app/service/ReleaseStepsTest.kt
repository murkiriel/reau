package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `LiveSides.release()` stopped at the first step that threw and left the compositor, the dash session and the Wi-Fi watch held. */
class ReleaseStepsTest {
    @Test
    fun `every step runs in order when none fails`() {
        val ran = mutableListOf<String>()
        val failed = ReleaseSteps.runAll(listOf("a" to { ran += "a" }, "b" to { ran += "b" })) { _, _ -> error("nothing failed") }
        assertEquals(0, failed)
        assertEquals(listOf("a", "b"), ran)
    }

    @Test
    fun `a step that throws is reported and the next ones still run`() {
        val ran = mutableListOf<String>()
        val reported = mutableListOf<String>()
        val failed = ReleaseSteps.runAll(
            listOf(
                "web" to { throw IllegalStateException("boom") },
                "receiver" to { ran += "receiver" },
                "compositor" to { ran += "compositor" },
            ),
        ) { name, e -> reported += "$name:${e.javaClass.simpleName}" }
        assertEquals(1, failed)
        assertEquals(listOf("receiver", "compositor"), ran)
        assertEquals(listOf("web:IllegalStateException"), reported)
    }

    @Test
    fun `all failures are counted`() {
        val failed = ReleaseSteps.runAll(listOf("a" to { error("x") }, "b" to { error("y") }, "c" to { })) { _, _ -> }
        assertEquals(2, failed)
        assertTrue(failed > 0)
    }
}
