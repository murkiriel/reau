package com.reau.app.service

/**
 * Lets go of a list of things one by one: a step that throws is reported and the next still runs. A release that stopped at the
 * first failure left the rest held (the compositor's EGL context and thread, the dash session, the Wi-Fi watch).
 */
internal object ReleaseSteps {
    /** Runs every step in order; [report] gets the name and the failure of each one that threw. Returns how many failed. */
    @Suppress("TooGenericExceptionCaught") // a release step may throw anything; the others must still run
    fun runAll(steps: List<Pair<String, () -> Unit>>, report: (String, Throwable) -> Unit): Int {
        var failed = 0
        for ((name, step) in steps) {
            try {
                step()
            } catch (e: Exception) {
                failed++
                report(name, e)
            }
        }
        return failed
    }
}
