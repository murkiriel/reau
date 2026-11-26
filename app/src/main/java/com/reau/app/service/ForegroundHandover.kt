package com.reau.app.service

/**
 * Switching off and on within a second, while the stop was still stopping Android Auto's receiver, once let the
 * stop's release take away the foreground the new start had just taken, and the projection went on as a plain
 * service. In the background, with the battery saver on, Android blocked its network (EPERM) and killed it as
 * "service (300)". Every projection seen failing with EPERM had begun that way.
 */
object ForegroundHandover {
    /**
     * Whether the release of the stop brought by [stopCommand] may let the foreground go: not when a start came after
     * it ([lastStart], the start id of the last start that took the foreground), which already holds it.
     */
    fun dropsForeground(stopCommand: Int, lastStart: Int): Boolean = lastStart <= stopCommand

    /**
     * The start id the release of the stop brought by [stopCommand] ends the service with. A stop that lets the foreground go ends the service with the id of the
     * latest command of any kind ([latestCommand]): a MARK or a REFIT that came after the Ligar or the Desligar has a newer id, and `stopSelf` with an older one does
     * nothing, so the service stayed "started" and idle, and being sticky the system could bring that idle process back (25/11/2026). A stop that does not let it
     * go (a start came after it) ends with its own id, as before: the newer start is the one that decides.
     */
    fun stopId(stopCommand: Int, lastStart: Int, latestCommand: Int): Int =
        if (dropsForeground(stopCommand, lastStart)) maxOf(stopCommand, latestCommand) else stopCommand

    /**
     * The id a command leaves as [stopId]'s latest command: its own [startId] when it does not take the foreground (Desligar, a mark, a refit), and the
     * [latest] it found when it does (a Ligar, a restart with no intent). A Ligar's id there would let a Desligar released while that Ligar is still on its way
     * end the new projection: `stopSelf` with the newest id stops the service, with an older one it does nothing (25/11/2026).
     */
    fun latestForStop(latest: Int, startId: Int, takesForeground: Boolean): Int = if (takesForeground) latest else startId

    // ActivityManager.RunningAppProcessInfo's importances, as numbers so the words stay testable off the device.
    private val NAMES = mapOf(
        100 to "foreground",
        125 to "foreground service",
        200 to "visible",
        230 to "perceptible",
        300 to "service",
        400 to "cached",
    )

    /** The process's importance with its name, for the ride log. */
    fun importance(importance: Int): String = NAMES[importance]?.let { "$importance ($it)" } ?: "$importance"

    /** How the process stands, for the `app background` line: the projection's network depends on its foreground. */
    fun standing(importance: Int, projectionForeground: Boolean, projectionOn: Boolean): String {
        val projection = when {
            !projectionOn -> "projection off"
            projectionForeground -> "projection in the foreground"
            else -> "projection NOT in the foreground: its network may be blocked"
        }
        return "process importance ${importance(importance)}, $projection"
    }
}
