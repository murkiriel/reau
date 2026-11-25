package com.reau.app.update

/**
 * The daily notice of a newer version (P32A): the decisions of it, with no Android in them. Once a day, with the internet on, [UpdateCheckJob] asks GitHub the
 * very question of the "Check now" button ([ReleaseCheck.fetch]: one request, no account, nothing about the rider) and, if the published version is newer than this
 * build, posts one notification, once per version. Nothing is downloaded or installed; the tap opens the release page in the browser.
 *
 * [decide] says what to do with an answer, [plan] what to do with the job when the switch or the app start asks, [logLine] what the ride log says. The JobScheduler
 * and the notification are [UpdateNoticeAndroid]'s.
 */
internal object UpdateNotice {
    /** The job's number in the app: one daily job. */
    const val JOB_ID = 3201

    /** The job repeats about once a day. */
    const val PERIOD_MS = 24L * 60 * 60 * 1000

    /** The notification channel of these notices, apart from the alert channel of the motorcycle ([com.reau.app.service.ProjectionNotification.ALERT_CHANNEL_ID]). */
    const val CHANNEL_ID = "reau_updates"

    /** The notification's number. */
    const val NOTIFICATION_ID = 3202

    /** What to do with an answer. Only [NOTIFY] posts a notification. */
    enum class Decision {
        /** The switch is off: nothing is asked or posted. */
        OFF,

        /** A newer version is published and has not been told yet. */
        NOTIFY,

        /** A newer version is published, and it was told before (this tag is the last one told). */
        ALREADY_TOLD,

        /** This build is the published version or a later one. */
        UP_TO_DATE,

        /** A development build ("0.3.0-dev+...") of the published version: it cannot be told apart from a newer one, so nothing is said. */
        DEV_BUILD,

        /** The two version names cannot be compared. */
        UNKNOWN,

        /** GitHub published nothing, or could not be asked, or did not answer as expected. */
        NO_ANSWER,
    }

    /**
     * What to do with [result] for the build named [current], with the switch [enabled] and [lastTold], the tag told last (null when none was). Follows
     * [ReleaseCheck.verdict]: only [ReleaseCheck.Verdict.NEWER] can notify, a development build and a name that cannot be compared never do.
     */
    fun decide(enabled: Boolean, current: String, result: ReleaseCheck.Result, lastTold: String?): Decision {
        if (!enabled) return Decision.OFF
        val latest = result as? ReleaseCheck.Result.Latest ?: return Decision.NO_ANSWER
        return when (ReleaseCheck.verdict(current, latest.tag)) {
            ReleaseCheck.Verdict.NEWER -> if (latest.tag == lastTold) Decision.ALREADY_TOLD else Decision.NOTIFY
            ReleaseCheck.Verdict.UP_TO_DATE -> Decision.UP_TO_DATE
            ReleaseCheck.Verdict.DEV_BUILD -> Decision.DEV_BUILD
            ReleaseCheck.Verdict.UNKNOWN -> Decision.UNKNOWN
        }
    }

    /** What to do with the daily job. */
    enum class Plan { SCHEDULE, KEEP, CANCEL, NOTHING }

    /** The job for the switch [enabled], given whether Android already [pending] has it: scheduled once, kept, or cancelled by the switch. */
    fun plan(enabled: Boolean, pending: Boolean): Plan = when {
        enabled && !pending -> Plan.SCHEDULE
        enabled -> Plan.KEEP
        pending -> Plan.CANCEL
        else -> Plan.NOTHING
    }

    /** The version as the title says it: the tag without its `v` (`v0.3.1` is "0.3.1"). */
    fun versionLabel(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /**
     * The line of the ride log for a run: `update check (daily): up to date`, `newer v0.3.1`, `failed (offline)`... the tag of a published release is public; there is no
     * name of the rider, of the phone or of an app in it.
     */
    fun logLine(decision: Decision, result: ReleaseCheck.Result): String {
        val tag = (result as? ReleaseCheck.Result.Latest)?.tag.orEmpty()
        val what = when (decision) {
            Decision.OFF -> "switched off, nothing asked"
            Decision.NOTIFY -> "newer $tag, told"
            Decision.ALREADY_TOLD -> "newer $tag, already told"
            Decision.UP_TO_DATE -> "up to date"
            Decision.DEV_BUILD -> "development build of the published version, nothing told"
            Decision.UNKNOWN -> "versions not comparable ($tag), nothing told"
            Decision.NO_ANSWER -> when (result) {
                ReleaseCheck.Result.NoRelease -> "no release published"
                is ReleaseCheck.Result.Failed -> "failed (${result.why.name.lowercase()})"
                is ReleaseCheck.Result.Latest -> "no answer"
            }
        }
        return "update check (daily): $what"
    }
}
