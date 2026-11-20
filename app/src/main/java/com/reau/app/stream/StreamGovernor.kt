package com.reau.app.stream

import com.reau.app.dash.DashConfig
import com.reau.app.dash.NavProfileMode
import com.reau.app.util.StageReport
import com.reau.app.util.StageStats
import com.reau.app.util.StageTotals
import com.reau.app.video.DashCompositor
import java.util.Locale

/**
 * The decisions of the video loop, with the clock and every measurement passed in, so they run in a plain JVM
 * test. It applies the stream rules ([DashLogic]) to
 * a source that is Android Auto instead of a map:
 *
 * - **Pace.** The still pace until the dash has confirmed the first frame of the stream; then a tick at the
 *   stream's rate. Which ticks carry a frame is the compositor's business: a new picture, or a repeat every idle
 *   interval. The rate is fixed for the whole stream: the full pace it was started with (`fullFps`, 4 in the app), half of
 *   it in the REDUCED tier, the light screens' pace on a light screen. Nothing raises or lowers it by what a window measured
 *   (the ladder that did, `AutoFpsController`, could not be reached with the fixed ceiling of 4 and was cut on 14/11/2026).
 * - **Bitrate.** The full bitrate while pictures keep coming ("the map changed recently" there is "Android Auto
 *   sent a new picture recently" here), half of it [MOTION_HOLD_MS] after the last one. The full bitrate is the
 *   rider's [bitrateCeilingBps], raised as needed so the rate keeps [DashLogic.MIN_BITS_PER_FRAME]. In the REDUCED
 *   tier (profile 2) it is [reducedBitrateBps] at most, whatever the picture does: the pace and the bits come down
 *   together, and go back up together.
 * - **Keyframes.** Every [keyframeSec] seconds, fixed for the whole stream (1 in the app). Nothing a window measured changes it (the
 *   interval that chose 1 or 2 s by itself, `AutoKeyframeController`, was never reached with the fixed 1 s and was cut on 14/11/2026).
 * - **Keyframes while still.** One every [DashLogic.IDLE_SYNC_EVERY_MS], when the encoder's own would be further
 *   apart than that.
 * - **The window.** Every [windowMs] one line with the stream's measurements, the bike's speed and
 *   Wi-Fi among it ([LinkWindow]), ending with what the window showed ([windowVerdict]). A window only writes its line:
 *   it never asks for a new encoder. The one rebuild left is the loop's own, for an encoder that failed ([rebuildAction]).
 * - **The navigation profiles** change the pace and the live bitrate, never the encoder: the encoder is always built
 *   for the full rate ([encoderFps]) and REDUCED just feeds it fewer frames, at the lower bitrate, set live, so a change
 *   of tier costs no rebuild. The last tier is held [NAV_HOLD_MS] when the data it came from goes missing.
 *
 * A window is judged only if the stream ran at its full rate for all of it AND the compositor had a frame for
 * (nearly) every tick: a source slower than the rate says nothing about what the phone or the dash can take.
 */
@Suppress("TooManyFunctions", "LongParameterList") // one small function per case; the parameters are the knobs of one stream
internal class StreamGovernor(
    /** The pace of the full profile, for the whole stream: the rider's ceiling, already under what the phone's heat allowed at the start. */
    fullFps: Int,
    private val clockSlowPercent: Int = 0,
    private val windowMs: Long = WINDOW_MS,
    private val bitrateCeilingBps: Int = DashConfig.MAX_STREAM_BITRATE_BPS,
    /** The keyframe interval, in seconds, for the whole stream. 0 (a stored "automatic", which is no more) or less is read as [DEFAULT_KEYFRAME_SEC]. */
    keyframeSec: Int = DashConfig.KEYFRAME_INTERVAL_AUTO,
    /** The bitrate of profile 2 (REDUCED), set live with its pace; profile 1's is [bitrateCeilingBps]. The table's half of it ([DashConfig.StreamProfile.reducedBps]). */
    private val reducedBitrateBps: Int = DashConfig.PROFILE_REDUCED_BPS,
    /** The pace on a light screen of Android Auto ([LightScreen]); 0: off, the pace is always the profile's. */
    private val lightScreenFps: Int = 0,
    /**
     * The reserve's ceiling is on (D117): the default, and always in a release build. A debug build's switch turns it off to compare the profiles at their own pace in the
     * reserve: the governor then never caps ([reserveOn] stays false) and only says in the log when the reserve begins and ends.
     */
    private val reserveCapOn: Boolean = true,
    /**
     * The three choices of a debug build (pace, ceiling, picture; plan 32) are pure: the stream runs at the chosen pace and ceiling from its first frame, with
     * no profile B (the navigation never lowers the tier), no start at half the pace until the dash has shown a frame, and no halving of the bitrate while the
     * picture is still. What stays is what is not a profile: the light screens, the reserve's ceiling and the heat's. A release build is not manual: it keeps
     * profiles A and B, the start ramp and the still bitrate exactly as they were.
     */
    private val manual: Boolean = false,
) {
    /** What happened since the previous poll ([dashAcks] is the dash's running total). */
    data class Poll(
        val nowMs: Long,
        val compositor: DashCompositor.Stats,
        val framesOut: Int,
        val bytesOut: Long,
        /** The keyframes of this poll: how many, the biggest one's bytes and RTP packets. */
        val keyframes: Int = 0,
        val keyBytesMax: Int = 0,
        val keyPacketsMax: Int = 0,
        val lateFrames: Int,
        val encodeNs: Long,
        val dashAcks: Long,
        val firstFrameConfirmed: Boolean,
        val thermalOk: Boolean = true,
        /** The bike's speed and Wi-Fi at this poll, for the window's line. */
        val link: LinkSample? = null,
        /** The navigation profiles' input: the distance to the next maneuver and the route's speed, read every poll. */
        val navSample: NavSample? = null,
        /** Android Auto's screen in front is a light one (its app menu). */
        val lightScreen: Boolean = false,
        /**
         * The bike is in the reserve (decision D117, plan 32): its low-fuel reminder (`0C05`, [com.reau.app.dash.DashReport.lowFuel]) is ON, as read in THIS
         * dash session ([ReserveCap.lowFuel]; a reading kept from an older session says nothing). While true the pace never goes above
         * [DashConfig.RESERVE_CAP_FPS] and the bitrate of profile A never above [DashConfig.RESERVE_CAP_BPS], light screens included.
         */
        val lowFuel: Boolean = false,
        /** Each frame's time in the encoder in this poll (the same time [encodeNs] sums), for the window's worst and 95th percentile. */
        val encode: StageStats = StageStats.EMPTY,
    )

    /**
     * The navigation profiles' live input, read every poll like [Poll.link]: each data field is trusted only when its
     * own switch ([maneuverProfileEnabled]/[speedProfileEnabled]) is on; both data fields are null at exactly the
     * same time — no active route — since [NavSpeed] only has a reading when `distanceToManeuverMeters` does too.
     */
    data class NavSample(
        val maneuverProfileEnabled: Boolean,
        val speedProfileEnabled: Boolean,
        val distanceToManeuverMeters: Int?,
        val speedKmh: Double?,
        /** Only matters with both switches on and disagreeing — see [NavProfileMode]. */
        val mode: NavProfileMode = NavProfileMode.OTIMISTA,
    )

    sealed interface Action {
        data class SetPace(val pace: DashCompositor.Pace) : Action
        data class SetBitrate(val bps: Int) : Action
        data class RequestSync(val reason: String) : Action

        /** Replace the encoder: its rate hint and keyframe interval are fixed when it is prepared. */
        data class Rebuild(
            val fps: Int,
            val keyframeSec: Int,
            val bitrateBps: Int,
            /** What the encoder is asked for as its own interval, in seconds ([StreamGovernor.encoderKeyframeSec]); the rider's [keyframeSec] when the pace never goes above the encoder's. */
            val encoderKeyframeSec: Int = keyframeSec,
        ) : Action
        data class Log(val line: String) : Action

        /** What the last second measured, for the stats text on the dash's video (every second). */
        data class Stats(val sentFps: Double, val kbps: Double) : Action
    }

    /** One measurement window. */
    private class Window(val startMs: Long, val ackStart: Long) {
        var frames = 0
        var bytes = 0L
        var source = 0
        var drawn = 0
        var repeated = 0
        var composeNs = 0L
        val tickWaits = ArrayList<Double>()
        var encodeNs = 0L
        var late = 0
        var keyframes = 0
        var keyBytesMax = 0
        var keyPacketsMax = 0
        var allFull = true
        var thermalOk = true
        var wakeups = 0
        val compose = StageTotals()
        val encode = StageTotals()
        val look = StageTotals()
        val latch = StageTotals()
        val link = LinkWindow()

        fun add(p: Poll, full: Boolean) {
            frames += p.framesOut
            bytes += p.bytesOut
            source += p.compositor.sourceFrames
            drawn += p.compositor.drawn
            repeated += p.compositor.repeated
            composeNs += p.compositor.composeNs
            tickWaits += p.compositor.tickWaitsMs
            encodeNs += p.encodeNs
            late += p.lateFrames
            keyframes += p.keyframes
            keyBytesMax = maxOf(keyBytesMax, p.keyBytesMax)
            keyPacketsMax = maxOf(keyPacketsMax, p.keyPacketsMax)
            allFull = allFull && full
            thermalOk = thermalOk && p.thermalOk
            wakeups += p.compositor.wakeups
            compose.add(p.compositor.compose)
            look.add(p.compositor.look)
            latch.add(p.compositor.latch)
            encode.add(p.encode)
            link.add(p.link)
        }
    }

    /** The full pace on an even step, like every rate. */
    private val fullFps = DashConfig.snapStreamFps(fullFps)
    private val maneuverProfile = ManeuverProfile()
    private val speedProfile = SpeedProfile()
    private var navTier: QualityTier? = null
    private val lightScreen = LightScreen()
    private var lightOn = false

    // The last verdict a profile gave and when, held while its data is missing (NAV_HOLD_MS).
    private var heldTier: QualityTier? = null
    private var heldAtMs = 0L

    // When the tier last changed from one tier to the other (NAV_DWELL_MS keeps it that long); none yet at the start.
    private var tierChangedAtMs = Long.MIN_VALUE / 2

    /** A profile is switched on: with none, the tier held for missing data is let go at once. */
    private var navProfilesOn = false

    private var dashAckSeen = false

    // What each pace really cost in bits ([PaceBitrateLog]); its line goes to the log every 5 minutes and at the end ([paceSummary]).
    private val paceLog = PaceBitrateLog()

    /** The reserve's ceiling is on ([Poll.lowFuel] as of the last poll); it moves the pace and the live bitrate only, like a tier, never the encoder. */
    private var reserveOn = false

    /** The reserve was on at the last poll with the cap switched off: only to tell it once on the way in and once on the way out. */
    private var reserveSeenUncapped = false

    /**
     * What the stream is at now, for [PaceBitrateLog]: the light screens, profile B, profile A held under the reserve's ceiling, profile A in the reserve
     * with the debug switch having the ceiling off (so a comparison of profiles there is measured on its own line), or profile A.
     */
    private fun paceKind(): PaceBitrateLog.Kind = when {
        lightOn -> PaceBitrateLog.Kind.LIGHT
        navTier == QualityTier.REDUCED -> PaceBitrateLog.Kind.B
        reserveOn && fullFps > DashConfig.RESERVE_CAP_FPS -> PaceBitrateLog.Kind.RESERVE
        reserveSeenUncapped -> PaceBitrateLog.Kind.RESERVE_UNCAPPED
        else -> PaceBitrateLog.Kind.A
    }

    /** The log's line of the end of the stream: the average bitrate of each pace over the whole stream; null while none has any time. */
    fun paceSummary(): String? = paceLog.line()

    /**
     * The same line every 5 minutes of stream: [nowMs] is the loop's clock, the one of [Poll.nowMs]; null until the interval is over. It is not an
     * [Action] on purpose: the golden traces of the governor (GovernadorOuroTest) are of its actions, and a line about the log itself does not belong in them.
     */
    fun periodicPaceSummary(nowMs: Long): String? = paceLog.periodicLine(nowMs)

    /** Both profiles as the stream starts, for the "video loop start" line: pace and target bitrate of A and of B (the exact half of A, [DashConfig.STREAM_PROFILES]). */
    val profilesText: String
        get() = if (manual) {
            "manual $fullFps fps / ${activeBitrateBps / 1000} kbps, no profile B"
        } else {
            "profile A $fullFps fps / ${activeBitrateBps / 1000} kbps, " +
                "B ${(fullFps / 2).coerceAtLeast(NAV_MIN_FPS)} fps / ${minOf(activeBitrateBps, reducedBitrateBps) / 1000} kbps"
        }

    /** The pace of the stream now: the profile's, or the light screens' on a light screen. */
    val fps: Int get() = underReserve(if (lightOn) LightScreen.fps(profileFps, lightScreenFps) else profileFps)

    /**
     * The pace of the profile (the navigation tier, or the full pace with none), without the light-screen pace: what the heat is judged against.
     * In the reserve it is the capped pace ([underReserve]), profile B being the exact half of that.
     */
    val profileFps: Int get() = navTier?.let(::tierFps) ?: underReserve(fullFps)

    /** [pace] under the reserve's ceiling ([DashConfig.RESERVE_CAP_FPS]) while the bike is in the reserve; as it is otherwise. */
    private fun underReserve(pace: Int): Int = if (reserveOn) minOf(pace, DashConfig.RESERVE_CAP_FPS) else pace

    /** Profile A's live bitrate ceiling: [activeBitrateBps], or in the reserve no more than [DashConfig.RESERVE_CAP_BPS]. The encoder is built for the uncapped one. */
    private val liveActiveBitrateBps: Int
        get() = if (reserveOn) minOf(activeBitrateBps, DashConfig.RESERVE_CAP_BPS) else activeBitrateBps

    /** The keyframe interval of the stream, in seconds: the one it was started with, never changed. */
    val keyframeSec: Int = keyframeSec.takeIf { it > 0 } ?: DEFAULT_KEYFRAME_SEC

    /**
     * The rate the encoder is built for (its frame-rate hint, and so the bits each frame gets): always the full pace.
     * REDUCED only feeds it fewer frames ([fps]), so a frame keeps its bits and a change of tier needs no new encoder.
     */
    val encoderFps: Int get() = fullFps

    /**
     * The fastest pace the stream can have on this encoder: the light screens' ([lightScreenFps], 6) when it is above the encoder's rate hint
     * ([encoderFps], 4 with a navigation profile), else the hint itself. The encoder's keyframe interval is counted in frames at the hint,
     * so it is made longer than the rider's seconds at this pace ([encoderKeyframeSec]) and the loop asks for the keyframes by the clock.
     */
    val gopFps: Int get() = if (lightScreenFps > 0) maxOf(encoderFps, lightScreenFps) else encoderFps

    /** The `KEY_I_FRAME_INTERVAL` for the encoder, in seconds: [keyframeSec] made longer for [gopFps] ([DashLogic.encoderKeyframeSec]). */
    val encoderKeyframeSec: Int get() = DashLogic.encoderKeyframeSec(keyframeSec, gopFps, encoderFps)

    /** The bitrate the encoder is built for, and the one profile 1 sets while frames keep coming: the rider's ceiling. */
    val activeBitrateBps: Int
        // A manual stream is held to the ceiling the rider chose: it is not raised for the bits a frame gets (12 fps at 400 kbps would be lifted to 500).
        get() = if (manual) {
            bitrateCeilingBps.coerceIn(0, DashConfig.MAX_STREAM_BITRATE_BPS)
        } else {
            DashLogic.effectiveActiveBitrate(bitrateCeilingBps, encoderFps)
        }

    /** The most the live bitrate goes to now: [activeBitrateBps], or [reducedBitrateBps] in the REDUCED tier. */
    private val tierBitrateBps: Int
        // On the app menu the reduced profile's half bitrate goes too: the menu is nearly still, and 8 fps of it fit in the full one.
        get() = if (navTier == QualityTier.REDUCED && !lightOn) minOf(liveActiveBitrateBps, reducedBitrateLimit) else liveActiveBitrateBps

    /** Profile B's bitrate: the table's half; in the reserve no more than the half of the capped profile A ([DashConfig.RESERVE_CAP_BPS]). */
    private val reducedBitrateLimit: Int
        get() = if (reserveOn) minOf(reducedBitrateBps, DashConfig.RESERVE_CAP_BPS / 2) else reducedBitrateBps

    /**
     * FULL is the full pace — the Settings' own ceiling — never above what the dash has already shown it can hold.
     * REDUCED is half of it, snapped to an even step — allowed down to [NAV_MIN_FPS] (2, REau's idle pace too,
     * `DashEncoder.kt`'s "4 fps active / 2 fps idle"), below [DashConfig.MIN_STREAM_FPS] on purpose: that floor is
     * about the full pace, not this deliberate, bounded reduction. Profile B is always the EXACT half of A, odd or not (4/2, 6/3, 8/4: the table of
     * [DashConfig.STREAM_PROFILES]), from the same floor; in the reserve it is the half of the capped A ([underReserve]).
     */
    private fun tierFps(tier: QualityTier): Int = when (tier) {
        QualityTier.FULL -> underReserve(fullFps)
        QualityTier.REDUCED -> (underReserve(fullFps) / 2).coerceAtLeast(NAV_MIN_FPS)
    }

    private var pace: DashCompositor.Pace? = null
    private var bitrateBps = 0
    private var lastPictureAtMs: Long? = null
    private var lastSyncAtMs = 0L
    private var window: Window? = null

    // The stats text's own second: updated every second, not every 5 s window.
    private var statsStartMs = -1L
    private var statsFrames = 0
    private var statsBytes = 0L
    private val syncReasons = LinkedHashMap<String, Int>()

    /**
     * The encoder to build when the running one failed: the rate it is built for and the bitrate
     * that is applied now. It used to be built for the pace's fps and the full bitrate, so a stream that was still (or in the
     * reduced profile) came back at the full bitrate with the pace unchanged, and nothing set it right (audit of 20/10/2026).
     */
    fun rebuildAction(): Action.Rebuild =
        Action.Rebuild(encoderFps, keyframeSec, bitrateBps.takeIf { it > 0 } ?: activeBitrateBps, encoderKeyframeSec)

    /** A stream to the dash begins: the still pace, the still bitrate, and a keyframe. */
    fun start(nowMs: Long): List<Action> {
        pace = null
        bitrateBps = 0
        reserveOn = false
        reserveSeenUncapped = false
        paceLog.start(nowMs)
        lastPictureAtMs = null
        window = null
        statsStartMs = -1L
        return paceChange(firstFrameConfirmed = false) + bitrateChange(full = false) + sync("stream start", nowMs)
    }

    /** A keyframe was asked for by someone else (a stream restart): it counts in the window's line. */
    fun syncRequested(reason: String, nowMs: Long) {
        sync(reason, nowMs)
    }

    fun onPoll(p: Poll): List<Action> {
        // The frames and bytes of this poll were sent at the pace in force before it changes anything.
        paceLog.add(paceKind(), fps, p.framesOut, p.bytesOut, p.nowMs)
        if (p.compositor.drawn > 0) lastPictureAtMs = p.nowMs
        val reserveChange = updateReserve(p)
        val lightChange = updateLight(p)
        val moving = lastPictureAtMs?.let { p.nowMs - it < MOTION_HOLD_MS } ?: false
        val full = DashLogic.fullRate(moving, p.firstFrameConfirmed)
        val tierChange = updateNavOverride(p.navSample, p.nowMs)
        val actions = mutableListOf<Action>()
        reserveChange?.let { actions += it }
        tierChange?.let { actions += it }
        lightChange?.let { actions += it }
        actions += paceChange(p.firstFrameConfirmed)
        actions += bitrateChange(full)
        // A tier change, a light screen, the reserve and a profile switched on or off move the pace and the live bitrate only: what the
        // encoder is built for ([encoderFps]) never moves, so no poll asks for a new encoder here.
        actions += idleSync(full, p.nowMs)
        actions += account(p, full)
        actions += statsSecond(p)
        return actions
    }

    /**
     * The frames and bytes sent in the last second: one [Action.Stats] when a second has gone by since the previous one.
     * The first poll only starts counting (what it carries came before).
     */
    private fun statsSecond(p: Poll): List<Action> {
        if (statsStartMs < 0) {
            statsStartMs = p.nowMs
            statsFrames = 0
            statsBytes = 0L
            return emptyList()
        }
        statsFrames += p.framesOut
        statsBytes += p.bytesOut
        val elapsedMs = p.nowMs - statsStartMs
        if (elapsedMs < STATS_MS) return emptyList()
        val stats = Action.Stats(DashLogic.computeActualFps(statsFrames, elapsedMs), statsBytes * 8.0 / elapsedMs)
        statsStartMs = p.nowMs
        statsFrames = 0
        statsBytes = 0L
        return listOf(stats)
    }

    /**
     * The navigation profiles, read live every poll, nothing baked in at construction (same pattern as [Poll.link]). A
     * profile with no data this poll (the route's speed or distance missing for a moment) keeps its last verdict for
     * [NAV_HOLD_MS] instead of falling back to the full pace at once: that fall-back, and the way back, caused
     * most of the encoder rebuilds measured in use. A log line when the tier changes (null: no override).
     */
    private fun updateNavOverride(sample: NavSample?, nowMs: Long): Action? {
        // Manual choices have no profile B: the navigation's tiers are not read, and the pace and bitrate are the chosen ones.
        if (manual) return null
        navProfilesOn = sample != null && (sample.maneuverProfileEnabled || sample.speedProfileEnabled)
        val before = navTier
        val verdict = combineNavTier(sample)
        if (verdict != null) {
            heldTier = verdict
            heldAtMs = nowMs
        } else if (!navProfilesOn || nowMs - heldAtMs >= NAV_HOLD_MS) {
            heldTier = null
        }
        navTier = afterDwell(before, verdict ?: heldTier, nowMs)
        if (navTier == before) return null
        val why = if (verdict == null) "no data for ${NAV_HOLD_MS / 1000} s" else "data"
        return Action.Log("nav profile: ${before ?: "none"} -> ${navTier ?: "none"} ($why), pace $fps fps, encoder $encoderFps fps")
    }

    /**
     * A change from one tier to the other waits until the tier has been kept [NAV_DWELL_MS]; the first verdict, a return from
     * no data and the fall to no data are not changes of that kind and are never delayed. A tier taken from no tier starts
     * the count too (without it, a FULL taken from no tier was seen turning REDUCED 6 s later).
     */
    private fun afterDwell(before: QualityTier?, wanted: QualityTier?, nowMs: Long): QualityTier? {
        if (before == null && wanted != null) tierChangedAtMs = nowMs
        return when {
            before == null || wanted == null || wanted == before -> wanted
            nowMs - tierChangedAtMs < NAV_DWELL_MS -> before
            else -> {
                tierChangedAtMs = nowMs
                wanted
            }
        }
    }

    /** No switch enabled (or no active route) is null — no tier, the full pace stands. With
     *  one switch enabled and data, its own verdict stands. With both and disagreeing, [NavSample.mode] decides:
     *  OTIMISTA takes whichever says FULL, PESSIMISTA whichever says REDUCED. */
    private fun combineNavTier(sample: NavSample?): QualityTier? {
        if (sample == null) return null
        val maneuverTier = if (sample.maneuverProfileEnabled) maneuverProfile.onSample(sample.distanceToManeuverMeters) else null
        val speedTier = if (sample.speedProfileEnabled) speedProfile.onSample(sample.speedKmh) else null
        val tiers = listOfNotNull(maneuverTier, speedTier)
        val wins = if (sample.mode == NavProfileMode.OTIMISTA) QualityTier.FULL else QualityTier.REDUCED
        val loses = if (wins == QualityTier.FULL) QualityTier.REDUCED else QualityTier.FULL
        return when {
            tiers.isEmpty() -> null
            tiers.any { it == wins } -> wins
            else -> loses
        }
    }

    /**
     * The reserve's ceiling (D117), read live every poll: one line in the log when it goes on and one when it goes off, none while it stays as
     * it is. The pace and the live bitrate follow in this same poll ([paceChange], [bitrateChange]); the encoder is never asked to be rebuilt.
     */
    private fun updateReserve(p: Poll): Action? {
        if (!reserveCapOn) return reserveWithoutCap(p)
        if (p.lowFuel == reserveOn) return null
        val was = fps
        reserveOn = p.lowFuel
        return Action.Log(
            if (reserveOn) "reserve: pace capped at ${DashConfig.RESERVE_CAP_FPS} fps (was $was)" else "reserve: pace cap lifted (was $was, now $fps fps)",
        )
    }

    /** The debug switch has the ceiling off: nothing is capped, but the reserve is told in the log, so a test made in it is on record. */
    private fun reserveWithoutCap(p: Poll): Action? {
        if (p.lowFuel == reserveSeenUncapped) return null
        reserveSeenUncapped = p.lowFuel
        return Action.Log(
            if (p.lowFuel) {
                "reserve: cap off by the debug switch (pace stays $fps fps)"
            } else {
                "reserve: over (the cap was off by the debug switch, pace stays $fps fps)"
            },
        )
    }

    /** Whether the light-screen pace is on after this poll; a line in the log when it changes. */
    private fun updateLight(p: Poll): Action? {
        if (lightScreenFps <= 0) return null
        val was = lightOn
        // With the first frame confirmed only: before the dash has shown it takes the picture the pace is the profile's. The reduced
        // profile (far from a maneuver, or fast) does not hold it back: it is where the rider's menu was a 2 fps picture (21/10/2026).
        val light = p.lightScreen && p.firstFrameConfirmed
        lightOn = lightScreen.update(light, p.thermalOk, p.nowMs)
        if (lightOn == was) return null
        val said = if (lightOn) "up to $fps fps (the app menu or an app of Android Auto)" else "back to the profile's $fps fps"
        return Action.Log("light screen: pace $said")
    }

    private fun paceChange(firstFrameConfirmed: Boolean): List<Action> {
        val (activeMs, idleMs) = DashLogic.frameIntervalsMsForFps(fps)
        val wanted = DashCompositor.Pace(if (firstFrameConfirmed || manual) activeMs else idleMs, idleMs, clockSlowPercent)
        if (wanted == pace) return emptyList()
        pace = wanted
        return listOf(Action.SetPace(wanted))
    }

    private fun bitrateChange(full: Boolean): List<Action> {
        // Still, profile 1 halves its bitrate; profile 2 already is at the half, and stays there. Manual choices keep the chosen ceiling whatever the picture does.
        val wanted = if (full || manual) tierBitrateBps else minOf(tierBitrateBps, liveActiveBitrateBps / 2)
        if (wanted == bitrateBps) return emptyList()
        bitrateBps = wanted
        return listOf(Action.SetBitrate(wanted))
    }

    /**
     * While the stream runs full the count does not run: the first request comes a whole interval after it stops. The
     * encoder places a keyframe every [encoderFps] × keyframeSec frames, so at a pace below its rate (REDUCED) they
     * would come further apart than the rider's interval: one is asked for at the interval itself.
     */
    private fun idleSync(full: Boolean, nowMs: Long): List<Action> {
        if (full) return paceSync(nowMs)
        val idleMs = DashLogic.frameIntervalsMsForFps(fps).second
        val due = DashLogic.idleSyncNeeded(encoderFps, encoderKeyframeSec, idleMs) && nowMs - lastSyncAtMs >= DashLogic.IDLE_SYNC_EVERY_MS
        return if (due) sync("idle", nowMs) else emptyList()
    }

    /**
     * At the encoder's own rate the encoder's own keyframes do; below it, or with its interval lengthened for a faster pace
     * ([gopFps], see [DashLogic.paceSyncNeeded]), one at the rider's interval, by the clock whatever the pace (6 fps on a light screen
     * came to a keyframe every 0.67 s, 1.49 a second, when only the encoder's count of 4 frames made them).
     */
    private fun paceSync(nowMs: Long): List<Action> {
        if (!DashLogic.paceSyncNeeded(fps, encoderFps, gopFps)) {
            lastSyncAtMs = nowMs
            return emptyList()
        }
        return if (nowMs - lastSyncAtMs >= keyframeSec * 1000L) sync("pace", nowMs) else emptyList()
    }

    private fun sync(reason: String, nowMs: Long): List<Action> {
        lastSyncAtMs = nowMs
        syncReasons[reason] = (syncReasons[reason] ?: 0) + 1
        return listOf(Action.RequestSync(reason))
    }

    /** Adds this poll to the window; the window opens with the first frames that come out, which are not counted. */
    private fun account(p: Poll, full: Boolean): List<Action> {
        val w = window
        if (w == null) {
            if (p.framesOut > 0) window = Window(p.nowMs, p.dashAcks)
            return emptyList()
        }
        w.add(p, full)
        val elapsedMs = p.nowMs - w.startMs
        return if (elapsedMs < windowMs) {
            emptyList()
        } else {
            window = Window(p.nowMs, p.dashAcks)
            closeWindow(w, elapsedMs, p)
        }
    }

    /**
     * `dash=…` for the window's line, once the dash has acknowledged a frame in this stream (on a real dash it
     * almost never did, and a column of `dash=0.0` says nothing); empty until then.
     */
    private fun dashPart(decodedFps: Double, acksInWindow: Long): String {
        if (acksInWindow > 0) dashAckSeen = true
        return if (dashAckSeen) String.format(Locale.US, " dash=%.1f", decodedFps) else ""
    }

    private fun closeWindow(w: Window, elapsedMs: Long, p: Poll): List<Action> {
        val target = fps
        val sentFps = DashLogic.computeActualFps(w.frames, elapsedMs)
        val composedFps = DashLogic.computeActualFps(w.drawn + w.repeated, elapsedMs)
        val decodedFps = DashLogic.computeActualFps((p.dashAcks - w.ackStart).toInt(), elapsedMs)
        val kbps = w.bytes * 8.0 / elapsedMs
        val judged = w.allFull && composedFps >= target * DELIVERY_RATIO
        val perFrame = { ns: Long, frames: Int -> if (frames > 0) ns / 1e6 / frames else 0.0 }
        val line = String.format(
            Locale.US,
            "video fps target=%d sent=%.1f (%dframes/%.1fs) composed=%.1f%s kbps=%.0f set=%dk frame=%dB " +
                "source=%d drawn=%d repeated=%d compose=%.1fms encode=%.1fms late=%d tickwait=%s full=%s thermal=%s %s kf=%ds " +
                "idr=%d(max %dB/%dpkt) sync=%s auto=%dfps [%s]",
            target, sentFps, w.frames, elapsedMs / 1000.0, composedFps, dashPart(decodedFps, p.dashAcks - w.ackStart), kbps, bitrateBps / 1000,
            if (w.frames > 0) w.bytes / w.frames else 0L,
            w.source, w.drawn, w.repeated, perFrame(w.composeNs, w.drawn + w.repeated), perFrame(w.encodeNs, w.frames), w.late,
            com.reau.app.video.TickWait.text(w.tickWaits), judged, if (w.thermalOk) "OK" else "HOT", w.link.text(), keyframeSec,
            w.keyframes, w.keyBytesMax, w.keyPacketsMax, syncSummary(), fullFps, windowVerdict(judged, target, sentFps, kbps, w.thermalOk),
        ) + StageReport.text(w.compose.stats(), w.encode.stats(), w.look.stats(), w.latch.stats(), w.wakeups, elapsedMs)
        // The line is all a window gives: nothing it measured moves the pace or the keyframe interval, so it asks for no new encoder.
        return listOf(Action.Log(line))
    }

    /** "2[stream start:1,idle:1]", or "0"; the counts start again. */
    private fun syncSummary(): String {
        if (syncReasons.isEmpty()) return "0"
        val text = "${syncReasons.values.sum()}[${syncReasons.entries.joinToString(",") { "${it.key}:${it.value}" }}]"
        syncReasons.clear()
        return text
    }

    companion object {
        /** One line, and one judgement, every 5 s. */
        const val WINDOW_MS = 5_000L

        /** The least of the pace a window must have composed to be judged (`full=`), and sent not to be short. */
        const val DELIVERY_RATIO = 0.95

        /**
         * Headroom over [DashConfig.MAX_STREAM_BITRATE_BPS] before a window counts as over budget: the encoder's 5 s
         * average wobbles a few percent around its target.
         */
        const val BITRATE_TOLERANCE = 1.10

        /** The keyframe interval when none is given (0): 1 s, what the stream started at when it chose the interval by itself. */
        const val DEFAULT_KEYFRAME_SEC = 1

        /**
         * The text in brackets that ends the window's line, after `auto=Nfps` (N is the stream's full pace): what this
         * window alone showed, with no memory of the windows before it.
         *
         * - `not judged`: the window is not a full one, exactly when the line says `full=false` ([judged] false): the
         *   picture was still, the dash had not confirmed the first frame, or the compositor made less than
         *   [DELIVERY_RATIO] of [target].
         * - `short: sent 3.1`: a full window where fewer frames went out than [DELIVERY_RATIO] of [target]; with
         *   `kbps N` when the encoder put out more than [BITRATE_TOLERANCE] of the bitrate ceiling and `thermal` when the
         *   phone was hot, each alone or together (`short: sent 3.1, kbps 1200, thermal`).
         * - `ok`: a full window with none of the three.
         *
         * [target] is the `target=` of the same line: the pace when the window closed, not the pace the window ran at.
         * A window the pace changed in is therefore read against the pace it ended at. Going up (2 to 4, 4 to 6) it
         * composed less than the new pace and is `not judged`, never a false `short`; going down (4 to 2, 6 to 4) it
         * sent more than the new pace asks and is `ok`. `short` always means the encoder gave out fewer frames than the
         * compositor made in that same window.
         */
        internal fun windowVerdict(judged: Boolean, target: Int, sentFps: Double, kbps: Double, thermalOk: Boolean): String {
            if (!judged) return "not judged"
            val delivered = sentFps >= target * DELIVERY_RATIO
            val withinBitrate = kbps <= DashConfig.MAX_STREAM_BITRATE_BPS / KILO * BITRATE_TOLERANCE
            val why = buildList {
                if (!delivered) add(String.format(Locale.US, "sent %.1f", sentFps))
                if (!withinBitrate) add(String.format(Locale.US, "kbps %.0f", kbps))
                if (!thermalOk) add("thermal")
            }
            return if (why.isEmpty()) "ok" else "short: ${why.joinToString(", ")}"
        }

        private const val KILO = 1000.0

        /** The stats text on the dash's video is made again every second. */
        const val STATS_MS = 1_000L

        /** How long after the last new picture the stream still counts as moving. */
        const val MOTION_HOLD_MS = 1_500L

        /** The navigation profiles' own floor for the REDUCED tier, a cruise rate the dash shows well, below
         *  [DashConfig.MIN_STREAM_FPS] on purpose: see [tierFps]. */
        const val NAV_MIN_FPS = 2

        /**
         * How long a profile keeps its last verdict once the data behind it is gone (7 s, after the
         * route's speed has been stale for [NavSpeed.STALE_MS], 3 s: 10 s in all).
         */
        const val NAV_HOLD_MS = 7_000L

        /**
         * The least a tier is kept once it was changed to, before the other may be taken. The speed the profile
         * reads is worked out from the distance to the next maneuver and was measured wobbling across its
         * 55-65 km/h band 12 times in 11 min (at worst every 3 s).
         */
        const val NAV_DWELL_MS = 10_000L
    }
}
