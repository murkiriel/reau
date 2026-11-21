package com.reau.app.aa

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.PowerManager

/**
 * What REau can read of Android Auto's state at the moment a handshake goes unanswered (the server accepts the connection and answers nothing:
 * stuck, frozen by Android, or still closing the previous session), written to the ride log so that a frozen Android Auto can be proved or ruled
 * out after the ride. Nothing here needs a permission; whatever Android hides is said as such, never guessed.
 *
 * Readable: the package (version, time since its update, the stopped state, enabled), whether it is on the battery optimization allowlist
 * (`PowerManager.isIgnoringBatteryOptimizations` takes any package), the app-ops mode of `RUN_ANY_IN_BACKGROUND` (the "Restricted" battery setting;
 * asked for another package, Android may refuse it: then it is said unreadable), the list of running processes (Android answers an app only with its
 * own processes, so the list is checked for what it holds before it is taken as "not running"), the screen, the keyguard, doze and the battery saver.
 * Not readable without a permission, whatever the version: Android Auto's standby bucket (`UsageStatsManager.getAppStandbyBucket` answers for the
 * calling app only; the other needs the usage-access special permission), whether its process is frozen or cached (`ApplicationExitInfo` and
 * `getRunningAppProcesses` are for the caller's own processes), and its importance when the process list hides it.
 *
 * The reading ([read]) is Android's; the line ([line]) is pure, so the JVM tests cover it with the readings given.
 */
@Suppress("TooManyFunctions") // one small function per field of the line, each covered by its own test
internal object AaProcessState {
    /** One running process of the list Android gave: its name and `RunningAppProcessInfo.importance`. */
    data class Proc(val name: String, val importance: Int)

    /** What was read, each part null when it could not be. */
    data class Reading(
        /** The version name; null with [installed] true: not readable. */
        val version: String? = null,
        val installed: Boolean? = null,
        /** How long ago the package was updated, in ms (wall clock). */
        val updatedAgoMs: Long? = null,
        /** The package is in the stopped state (force-stopped, or never started since it was installed): it has no process. */
        val stopped: Boolean? = null,
        val enabled: Boolean? = null,
        /** On the battery optimization allowlist ("Unrestricted"/"Not optimized"). */
        val batteryExempt: Boolean? = null,
        /** The app-ops mode of RUN_ANY_IN_BACKGROUND (see [backgroundWords]). */
        val backgroundMode: Int? = null,
        /** The processes Android lists to REau; null when the list could not be asked. */
        val processes: List<Proc>? = null,
        val interactive: Boolean? = null,
        val locked: Boolean? = null,
        val dozing: Boolean? = null,
        val powerSave: Boolean? = null,
    )

    /**
     * The handshake that went unanswered: [inARow] of them so far, [mutedMs] since the connection was accepted, [sinceGoodEndMs] since the last
     * answered session ended (null: none known), [port] of the head unit server, [ownPackage] REau's, to tell its processes from the others'.
     */
    data class Mute(val inARow: Int, val mutedMs: Long, val sinceGoodEndMs: Long?, val port: Int, val ownPackage: String)

    /** The ride log's line for one unanswered handshake. */
    fun line(mute: Mute, reading: Reading): String =
        "${head(mute)}; the head unit server on port ${mute.port} accepted this dial; ${app(reading)}; " +
            "${processes(reading.processes, mute.ownPackage)}; ${lastGoodSession(mute.sinceGoodEndMs)}; ${phone(reading)}; $UNREADABLE"

    /** Reads, and writes [line]; a reading that throws is said as such: the ride log must not lose the handshake, nor the receiver its thread. */
    @Suppress("TooGenericExceptionCaught") // any reading of Android that throws must still leave the line, never take the receiver's thread
    fun report(mute: Mute, read: () -> Reading, say: (String) -> Unit) {
        val text = try {
            line(mute, read())
        } catch (e: RuntimeException) {
            "${head(mute)}; its state could not be read (${e.javaClass.simpleName})"
        }
        say(text)
    }

    private fun head(mute: Mute) =
        "Android Auto answered nothing to the version request (handshake ${mute.inARow} in a row, " +
            "${seconds(mute.mutedMs)} after its server accepted the connection)"

    /** The package, the battery settings and the process list; the phone's own state is [phone]. Everything asked of Android, nothing remembered. */
    fun read(context: Context, nowWallMs: Long = System.currentTimeMillis()): Reading {
        val info = runCatching { context.packageManager.getPackageInfo(AndroidAuto.PACKAGE, 0) }.getOrNull()
        val app = info?.applicationInfo
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return Reading(
            installed = info != null,
            version = info?.versionName,
            updatedAgoMs = info?.let { (nowWallMs - it.lastUpdateTime).takeIf { ago -> ago >= 0 } },
            stopped = app?.let { (it.flags and ApplicationInfo.FLAG_STOPPED) != 0 },
            enabled = app?.enabled,
            batteryExempt = runCatching { power.isIgnoringBatteryOptimizations(AndroidAuto.PACKAGE) }.getOrNull(),
            backgroundMode = app?.let { backgroundMode(context, it.uid) },
            processes = runCatching {
                context.getSystemService(ActivityManager::class.java).runningAppProcesses?.map { Proc(it.processName, it.importance) }
            }.getOrNull(),
            interactive = runCatching { power.isInteractive }.getOrNull(),
            locked = runCatching { keyguard.isKeyguardLocked }.getOrNull(),
            dozing = runCatching { power.isDeviceIdleMode }.getOrNull(),
            powerSave = runCatching { power.isPowerSaveMode }.getOrNull(),
        )
    }

    // The form that is not deprecated asks for the other app's AttributionSource, which REau cannot build for Android Auto; this one still answers
    // with no permission, and a failure is read as "not readable".
    @Suppress("DEPRECATION")
    private fun backgroundMode(context: Context, uid: Int): Int? = runCatching {
        context.getSystemService(AppOpsManager::class.java).unsafeCheckOpRawNoThrow(OP_RUN_ANY_IN_BACKGROUND, uid, AndroidAuto.PACKAGE)
    }.getOrNull()

    private fun app(r: Reading): String {
        if (r.installed == false) return "Android Auto is not installed"
        val parts = buildList {
            add("Android Auto ${r.version ?: "(version not readable)"}")
            r.updatedAgoMs?.let { add("updated ${agoWords(it)} ago") }
            add("stopped state ${yesNo(r.stopped)}")
            if (r.enabled == false) add("disabled")
            add("battery optimization exempt ${yesNo(r.batteryExempt)}")
            add("background op ${backgroundWords(r.backgroundMode)}")
        }
        return parts.joinToString(", ")
    }

    /**
     * The process list as REau gets it. Android hides other apps' processes from an ordinary app: a list that holds only REau's own says nothing
     * of Android Auto, and is said so, instead of "not running".
     */
    internal fun processes(list: List<Proc>?, ownPackage: String): String {
        if (list == null) return "processes: the list could not be asked"
        val aa = list.filter { it.name == AndroidAuto.PACKAGE || it.name.startsWith(AndroidAuto.PACKAGE + ":") }
        if (aa.isNotEmpty()) {
            return "processes: " + aa.joinToString(", ") { "${shortName(it.name)} ${importanceWords(it.importance)}" }
        }
        val others = list.count { it.name != ownPackage && !it.name.startsWith("$ownPackage:") }
        return if (others == 0) {
            "processes: Android Auto's not listed, and the list holds only REau's own ${list.size} " +
                "(Android hides the other apps': this does not say it is not running)"
        } else {
            "processes: Android Auto has none among the $others other processes listed"
        }
    }

    /** `gearhead:projection` as `projection`; the main process as `main`. */
    private fun shortName(name: String) = name.removePrefix(AndroidAuto.PACKAGE).removePrefix(":").ifEmpty { "main" }

    private fun lastGoodSession(sinceMs: Long?): String =
        if (sinceMs == null) "no answered session known to end before" else "the last answered session ended ${agoWords(sinceMs)} ago"

    private fun phone(r: Reading): String =
        "screen ${onOff(r.interactive)}, ${word(r.locked, "locked", "unlocked")}, doze ${onOff(r.dozing)}, battery saver ${onOff(r.powerSave)}"

    internal fun importanceWords(importance: Int): String = when (importance) {
        IMPORTANCE_FOREGROUND -> "foreground"
        IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
        IMPORTANCE_TOP_SLEEPING -> "top sleeping"
        IMPORTANCE_VISIBLE -> "visible"
        IMPORTANCE_PERCEPTIBLE -> "perceptible"
        IMPORTANCE_CANT_SAVE_STATE -> "can't save state"
        IMPORTANCE_SERVICE -> "service"
        IMPORTANCE_CACHED -> "cached"
        IMPORTANCE_GONE -> "gone"
        else -> "importance $importance"
    }

    /** The app-ops mode of `RUN_ANY_IN_BACKGROUND`: "ignored" is the "Restricted" battery setting. */
    internal fun backgroundWords(mode: Int?): String = when (mode) {
        null -> "not readable"
        MODE_ALLOWED -> "allowed"
        MODE_IGNORED -> "ignored (restricted battery)"
        MODE_ERRORED -> "errored"
        MODE_DEFAULT -> "default"
        MODE_FOREGROUND -> "foreground only"
        else -> "mode $mode"
    }

    /** A time span in the largest whole unit that reads easily: seconds up to 2 min, minutes up to 2 h, hours up to 2 days, then days. */
    internal fun agoWords(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / MS_PER_S
        return when {
            s < 2 * S_PER_MIN -> "$s s"
            s < 2 * S_PER_H -> "${s / S_PER_MIN} min"
            s < 2 * S_PER_DAY -> "${s / S_PER_H} h"
            else -> "${s / S_PER_DAY} days"
        }
    }

    private fun seconds(ms: Long) = String.format(java.util.Locale.US, "%.1f s", ms.coerceAtLeast(0L) / MS_PER_S.toDouble())

    private fun word(value: Boolean?, yes: String, no: String) = when (value) {
        true -> yes
        false -> no
        null -> "?"
    }

    private fun yesNo(value: Boolean?) = word(value, "yes", "no")

    private fun onOff(value: Boolean?) = word(value, "on", "off")

    /** What stays unknown whatever is read, said in every line so that a reader of the log does not look for it. */
    internal const val UNREADABLE =
        "not readable without a permission: Android Auto's standby bucket, and whether Android froze or cached its process"

    // RunningAppProcessInfo.IMPORTANCE_*, written out: the words must not depend on the framework in a JVM test.
    private const val IMPORTANCE_FOREGROUND = 100
    private const val IMPORTANCE_FOREGROUND_SERVICE = 125
    private const val IMPORTANCE_TOP_SLEEPING = 150
    private const val IMPORTANCE_VISIBLE = 200
    private const val IMPORTANCE_PERCEPTIBLE = 230
    private const val IMPORTANCE_CANT_SAVE_STATE = 270
    private const val IMPORTANCE_SERVICE = 300
    private const val IMPORTANCE_CACHED = 400
    private const val IMPORTANCE_GONE = 1000

    // AppOpsManager.MODE_*.
    private const val MODE_ALLOWED = 0
    private const val MODE_IGNORED = 1
    private const val MODE_ERRORED = 2
    private const val MODE_DEFAULT = 3
    private const val MODE_FOREGROUND = 4

    /** The app op behind the "Restricted" battery setting (`AppOpsManager.OPSTR_RUN_ANY_IN_BACKGROUND`, not in the public SDK). */
    private const val OP_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"

    private const val MS_PER_S = 1_000L
    private const val S_PER_MIN = 60L
    private const val S_PER_H = 3_600L
    private const val S_PER_DAY = 86_400L
}
