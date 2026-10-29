package com.reau.app.util

/**
 * Milliseconds that only go forward, for measuring how long something took or has been silent. The wall clock
 * (`System.currentTimeMillis()`) is set by the network in the middle of a ride (a tunnel's exit, a time sync): a healthy
 * stream then counted as silent for minutes, or a watchdog stopped counting when the clock went back (audit of 20/10/2026).
 * Not a time of day: only the difference between two readings means anything. Always above zero, so that 0 can still mean
 * "never" in the callers' fields. Works on the JVM too (the unit tests), where the Android clocks are stubs.
 */
object MonotonicClock {
    private val origin = System.nanoTime()

    fun nowMs(): Long = (System.nanoTime() - origin) / NANOS_PER_MS + 1

    private const val NANOS_PER_MS = 1_000_000L
}
