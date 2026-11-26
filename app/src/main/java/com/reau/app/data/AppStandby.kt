package com.reau.app.data

/**
 * REau's standing with Android's battery management, as one line of the ride log (26/11/2026). The two Android 9+ answers the runs of 25/11/2026 lacked while the
 * system ended the process at 5 min of screen off: the standby bucket the system put the app in, and whether the rider restricted its background use. Both are
 * read for the app's own package with no permission; what could not be read is `unknown` / `?`. No place or network name here (privacy).
 *
 * The line also says whether USB debugging (`adb=`) and the developer options (`devopts=`) are on (`Settings.Global.ADB_ENABLED` and `DEVELOPMENT_SETTINGS_ENABLED`, public, no
 * permission): USB debugging may change how a maker's system treats the app when the screen locks, and the owner's phone has it on while the pilots' may not; the log
 * never showed it. It is written only when it changes, like the rest of the line.
 */
object AppStandby {
    /**
     * The bucket as `UsageStatsManager.getAppStandbyBucket` answers it (null when it failed), the background restriction (null when it failed), USB debugging ([adb]) and the developer
     * options ([devOptions]) switches (null when the setting could not be read).
     */
    data class Reading(val bucket: Int?, val backgroundRestricted: Boolean?, val adb: Boolean? = null, val devOptions: Boolean? = null)

    // `UsageStatsManager.STANDBY_BUCKET_*`: written as numbers because the restricted bucket is API 30 and the exempted one is not public.
    private const val EXEMPTED = 5
    private const val ACTIVE = 10
    private const val WORKING_SET = 20
    private const val FREQUENT = 30
    private const val RARE = 40
    private const val RESTRICTED = 45
    private const val NEVER = 50

    /** The bucket's name in the log; a number this list does not know is `bucket<N>`, a bucket that could not be read is `unknown`. */
    fun bucketName(bucket: Int?): String = when (bucket) {
        null -> "unknown"
        EXEMPTED -> "exempted"
        ACTIVE -> "active"
        WORKING_SET -> "working_set"
        FREQUENT -> "frequent"
        RARE -> "rare"
        RESTRICTED -> "restricted"
        NEVER -> "never"
        else -> "bucket$bucket"
    }

    /** The log line. It does not start with `device ` (the reader takes those for the looks at the phone). */
    fun line(reading: Reading): String = "app standby: bucket=${bucketName(reading.bucket)} background-restricted=" +
        when (reading.backgroundRestricted) { true -> "yes"; false -> "no"; null -> "?" } +
        " adb=${onOff(reading.adb)} devopts=${onOff(reading.devOptions)}"

    private fun onOff(value: Boolean?) = when (value) { true -> "on"; false -> "off"; null -> "?" }
}

/** Writes [AppStandby.line] when a projection first looks at the phone and again only when the bucket or the restriction changed. One per projection. */
class AppStandbyLog(private val write: (String) -> Unit) {
    private var last: AppStandby.Reading? = null

    fun offer(reading: AppStandby.Reading) {
        if (reading == last) return
        last = reading
        write(AppStandby.line(reading))
    }
}
