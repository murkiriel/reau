package com.reau.app.dash.video

import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.dash.DashConfig
import com.reau.app.data.RideLogProbe
import com.reau.app.util.CodecMetrics
import com.reau.app.util.CodecReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real MediaCodec encoder fed the way the compositor will feed it: OpenGL frames on the exposed input surface,
 * each stamped with its presentation time. No dash and no Android Auto: this is the encoder alone.
 *
 * The encoder is fed on its surface only (no Canvas `renderFrame`, no `drain` to pull with), so the cases are written
 * here on EGL.
 *
 * The last group reads what the codec itself gives (the `encoder accepted:` line with the input and output formats read once the codec
 * runs, the keyframe interval of 1 s kept by the real encoder, and `getMetrics()` read at the release without holding it beyond its
 * limit): the ride log is read back through [RideLogProbe].
 *
 * To run later (not run when written; the machine had no device): an emulator booted with the app and its test APK installed,
 * `ANDROID_SERIAL=<emulator serial>` for adb and `REAU_EMU_SERIAL=<emulator serial>` for
 * `scripts/emu/run_instrumented.py DashEncoderSurfaceTest`, which runs this class in a process of its own. The emulator needs no Wi-Fi,
 * location or `dumpsys deviceidle`; it needs an AVC encoder and a recordable EGL config (the tests fail on a device without them, as the
 * ones above do), and the ride log switched on (a test that finds it off is skipped). The emulator's only encoder is a software one: the
 * times asked of it are for 4 and 6 frames a second, which it keeps up with.
 */
@RunWith(AndroidJUnit4::class)
class DashEncoderSurfaceTest {

    /** One buffer handed to `onFrame`. */
    private class Out(val nalTypes: List<Int>, val isKey: Boolean, val ptsMs: Long, val thread: String) {
        /** A coded picture (slice NAL), as opposed to the SPS/PPS buffer. */
        val isFrame: Boolean get() = nalTypes.any { it == NAL_SLICE || it == NAL_IDR }
    }

    private val out = CopyOnWriteArrayList<Out>()
    private val probe = RideLogProbe(InstrumentationRegistry.getInstrumentation().targetContext)
    private var encoder: DashEncoder? = null
    private var painter: EglPainter? = null
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.YELLOW)

    @After
    fun tearDown() {
        painter?.release()
        encoder?.release()
    }

    private fun newEncoder(sink: MutableList<Out> = out) = DashEncoder { data, isKey, ptsMs ->
        sink += Out(nalTypes(data), isKey, ptsMs, Thread.currentThread().name)
    }

    /** A prepared encoder with a painter on its surface. */
    private fun start(iFrameIntervalSec: Int = 1): DashEncoder {
        val enc = newEncoder().also { encoder = it }
        assertTrue("prepare failed: ${enc.lastPrepareError}", enc.prepare(16, DashConfig.MAX_STREAM_BITRATE_BPS, iFrameIntervalSec))
        painter = EglPainter(checkNotNull(enc.inputSurface) { "a prepared encoder must expose its input surface" })
        return enc
    }

    private fun frames(sink: List<Out> = out) = sink.filter { it.isFrame }

    /** Paints [count] frames, 62 ms apart, with times [firstPtsMs], +62, +124, … */
    private fun paint(count: Int, firstPtsMs: Long, announceTo: DashEncoder? = null) {
        repeat(count) { i ->
            val ptsMs = firstPtsMs + i * STEP_MS
            announceTo?.frameSubmitted(ptsMs)
            checkNotNull(painter).paint(colors[i % colors.size], ptsMs)
            Thread.sleep(STEP_MS)
        }
    }

    /**
     * Waits for the frames just painted to come out. The default wait ends before the encoder would repeat the
     * last frame by itself ([DashEncoder.REPEAT_AFTER_US] after the last one painted), so a test that counts
     * frames only sees the ones it painted.
     */
    private fun awaitFrames(atLeast: Int, timeoutMs: Long = 700, sink: List<Out> = out) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (frames(sink).size < atLeast && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    // ── what the dash needs from the very first bytes ──────────────────────────────────────────────────────

    @Test
    fun theParameterSetsComeOutBeforeTheFirstFrameAndTheFirstFrameIsAKeyframe() {
        start()
        paint(5, firstPtsMs = STEP_MS)
        awaitFrames(5)

        val firstFrameAt = out.indexOfFirst { it.isFrame }
        assertTrue("no frame came out: ${out.map { it.nalTypes }}", firstFrameAt >= 0)
        val before = out.take(firstFrameAt).flatMap { it.nalTypes } + out[firstFrameAt].nalTypes.takeWhile { it != NAL_IDR }
        assertTrue("SPS must come before the first frame, got ${out.map { it.nalTypes }}", NAL_SPS in before)
        assertTrue("PPS must come before the first frame, got ${out.map { it.nalTypes }}", NAL_PPS in before)

        val first = out[firstFrameAt]
        assertTrue("the first frame must be flagged as a keyframe", first.isKey)
        assertTrue("the first frame must be an IDR, got ${first.nalTypes}", NAL_IDR in first.nalTypes)
    }

    @Test
    fun theStreamIsBaselineProfile() {
        val sps = CopyOnWriteArrayList<ByteArray>()
        val enc = DashEncoder { data, _, _ -> if (NAL_SPS in nalTypes(data)) sps += data }.also { encoder = it }
        assertTrue(enc.prepare(16, DashConfig.MAX_STREAM_BITRATE_BPS))
        painter = EglPainter(checkNotNull(enc.inputSurface))
        paint(3, firstPtsMs = STEP_MS)
        val deadline = System.currentTimeMillis() + 700
        while (sps.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertTrue("no SPS came out", sps.isNotEmpty())
        // profile_idc is the byte after the SPS NAL header. 66 = Baseline: the only one the dash shows.
        assertEquals(66, profileIdc(sps.first()))
    }

    /**
     * The conservative envelope (4 frames a second, 200 kbps) at a constant or a
     * variable bitrate still gives a keyframe first and every frame painted, on whatever encoder the phone has (one
     * that does not offer the mode falls back, DashEncoder.rateMode).
     */
    @Test
    fun eachBitrateModeAtTheConservativeEnvelopeStillCodesEveryFrameFromAKeyframe() {
        for (mode in com.reau.app.dash.BitrateMode.entries) {
            out.clear()
            painter?.release()
            encoder?.release()
            val enc = newEncoder().also { encoder = it }
            assertTrue("$mode: prepare failed: ${enc.lastPrepareError}", enc.prepare(4, 200_000, 1, mode))
            painter = EglPainter(checkNotNull(enc.inputSurface))
            paint(8, firstPtsMs = STEP_MS)
            awaitFrames(8)
            val coded = frames()
            assertTrue("$mode: only ${coded.size}/8 frames came out", coded.size >= 7)
            assertTrue("$mode: the first frame must be a keyframe", coded.first().isKey)
        }
    }

    // ── the time of each frame ─────────────────────────────────────────────────────────────────────────────

    /** Nobody calls frameSubmitted here: the time travels through the codec itself, with the picture. */
    @Test
    fun everyFrameComesBackWithTheTimeItWasStampedWithOnTheSurface() {
        start()
        val submitted = 20
        paint(submitted, firstPtsMs = STEP_MS)
        awaitFrames(submitted)

        val times = frames().map { it.ptsMs }
        assertTrue("only ${times.size}/$submitted frames came out", times.size >= submitted * 9 / 10)
        assertEquals("every frame must carry a different time", times.size, times.toSet().size)
        assertEquals("times must come out in submission order", times.sorted(), times)
        assertTrue("times must be ones that were stamped, got $times", times.all { it % STEP_MS == 0L && it in STEP_MS..submitted * STEP_MS })
        assertTrue(
            "frames must arrive on the encoder's own thread, got ${out.map { it.thread }.toSet()}",
            out.all { it.thread == EncoderOutputPump.THREAD_NAME },
        )
    }

    @Test
    fun theStatsCountTheFramesTheCompositorAnnounced() {
        val enc = start()
        val submitted = 10
        paint(submitted, firstPtsMs = STEP_MS, announceTo = enc)
        awaitFrames(submitted)
        val stats = enc.collectOutput() // also throws if the output thread failed

        assertEquals("the stats must count the frames that came out", frames().size, stats.frames)
        assertTrue("only ${stats.frames}/$submitted frames came out", stats.frames >= submitted * 9 / 10)
        assertTrue("time in the encoder must have been measured, got ${stats.latencySumNs}", stats.latencySumNs > 0)
        assertEquals("the count starts again after it is taken", 0, enc.collectOutput().frames)
    }

    // ── keyframes on request, and the safety net ───────────────────────────────────────────────────────────

    @Test
    fun aSyncFrameRequestIsAnsweredWithAKeyframe() {
        // 30 s between the encoder's own keyframes, so the only ones in this test are the first and the requested.
        val enc = start(iFrameIntervalSec = 30)
        paint(10, firstPtsMs = STEP_MS)
        awaitFrames(10)
        val before = frames().count { it.isKey }
        assertEquals("only the opening keyframe before the request", 1, before)

        enc.requestSyncFrame()
        paint(10, firstPtsMs = 11 * STEP_MS)
        awaitFrames(20)

        val keys = frames().filter { it.isKey }
        assertEquals("the request must produce exactly one more keyframe", 2, keys.size)
        assertTrue("the requested keyframe must be one of the frames painted after the request", keys.last().ptsMs >= 11 * STEP_MS)
    }

    @Test
    fun whenNothingIsPaintedTheEncoderRepeatsTheLastFrameByItself() {
        start()
        paint(3, firstPtsMs = STEP_MS)
        awaitFrames(3)
        val painted = frames().size
        assertEquals(3, painted)

        // Nothing is painted from here on. The codec must encode the last frame again after REPEAT_AFTER_US.
        awaitFrames(painted + 1, timeoutMs = 4 * DashEncoder.REPEAT_AFTER_US / 1000)

        val all = frames()
        assertTrue("no frame came out by itself in ${4 * DashEncoder.REPEAT_AFTER_US / 1000} ms", all.size > painted)
        assertTrue("a repeated frame must carry a later time than the last painted one", all[painted].ptsMs > 3 * STEP_MS)
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theSurfaceExistsOnlyWhileTheEncoderIsPrepared() {
        val enc = newEncoder().also { encoder = it }
        assertNull("no surface before prepare", enc.inputSurface)
        assertTrue(enc.prepare())
        assertNotNull(enc.inputSurface)
        assertTrue(checkNotNull(enc.inputSurface).isValid)
        assertNull(enc.lastPrepareError)
        enc.release()
        assertNull("no surface after release", enc.inputSurface)
    }

    @Test
    fun releaseStopsTheOutputThread() {
        val enc = start()
        paint(5, firstPtsMs = STEP_MS)
        awaitFrames(5)
        assertFalse(out.isEmpty())

        painter?.release()
        painter = null
        enc.release()
        val afterRelease = out.size
        // Longer than the repeat interval: a codec still running would hand over a repeated frame.
        Thread.sleep(DashEncoder.REPEAT_AFTER_US / 1000 + 400)
        assertEquals("nothing may arrive after release()", afterRelease, out.size)
    }

    /**
     * Every rate change builds a new encoder, so it must be rebuildable more times than the device has encoder
     * instances. On a phone's hardware encoder this is what a codec left running by release() would break. On the
     * emulator it does NOT prove that: its software encoder kept accepting new instances with release() mutated to
     * leave the codec running (a mutation where release does not stop the codec). Here it only shows
     * that building and releasing in a row works.
     */
    @Test
    fun theEncoderCanBeRebuiltMoreTimesThanTheDeviceHasInstances() {
        val instances = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .maxOf { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).maxSupportedInstances }
        val cycles = instances + 4
        repeat(cycles) { i ->
            val enc = newEncoder()
            try {
                assertTrue("prepare ${i + 1} of $cycles failed ($instances instances): ${enc.lastPrepareError}", enc.prepare())
            } finally {
                enc.release()
            }
        }
    }

    /** A rate change prepares the new encoder BEFORE releasing the old one, so a failed prepare can keep the
     *  stream alive on the old encoder. */
    @Test
    fun aSecondEncoderCanBePreparedWhileTheFirstRunsAndThenTakesOverAlone() {
        val old = start()
        paint(5, firstPtsMs = STEP_MS)
        awaitFrames(5)

        val fromNew = CopyOnWriteArrayList<Out>()
        val fresh = newEncoder(fromNew)
        try {
            assertTrue("second encoder must prepare while the first is still running", fresh.prepare(8, DashConfig.MAX_STREAM_BITRATE_BPS))
        } finally {
            painter?.release()
            painter = null
            old.release()
            encoder = fresh
        }
        val fromOldAtSwap = out.size
        painter = EglPainter(checkNotNull(fresh.inputSurface))
        paint(6, firstPtsMs = 1_000)
        awaitFrames(6, sink = fromNew)
        fresh.collectOutput()

        assertEquals("nothing from the old encoder after its release()", fromOldAtSwap, out.size)
        assertTrue("the new encoder must send frames, got ${frames(fromNew).size}", frames(fromNew).size >= 5)
        assertTrue("the new encoder must open with a keyframe", frames(fromNew).first().isKey)
    }

    /** The frame loop's "N failures in a row, rebuild the encoder" recovery depends on hearing about these. */
    @Test
    fun aFailureWhileHandingOverAFrameIsReportedOnceAndTheThreadCarriesOn() {
        val failNext = AtomicBoolean(true)
        val delivered = AtomicInteger(0)
        val enc = DashEncoder { _, _, _ ->
            if (failNext.getAndSet(false)) error("send failed") else delivered.incrementAndGet()
        }.also { encoder = it }
        assertTrue(enc.prepare(16, DashConfig.MAX_STREAM_BITRATE_BPS))
        painter = EglPainter(checkNotNull(enc.inputSurface))
        paint(6, firstPtsMs = STEP_MS)
        val deadline = System.currentTimeMillis() + 700
        while (delivered.get() < 5 && System.currentTimeMillis() < deadline) Thread.sleep(20)

        val failure = assertThrows(IllegalStateException::class.java) { enc.collectOutput() }
        assertTrue("got: ${failure.message}", failure.message.orEmpty().contains("send failed"))
        enc.collectOutput() // reported once: the second call does not throw
        assertTrue("the output thread must carry on after the failure, delivered ${delivered.get()}", delivered.get() >= 5)
    }

    @Test
    fun requestsOnAnUnpreparedEncoderAreNoOpsNotCrashes() {
        val enc = newEncoder().also { encoder = it }
        enc.requestSyncFrame()
        enc.requestBitrate(100_000)
        enc.frameSubmitted(1)
        assertEquals(0, enc.collectOutput().frames)
        enc.release()
    }

    @Test
    fun theBitrateCanBeChangedOnARunningEncoder() {
        val enc = start()
        paint(5, firstPtsMs = STEP_MS)
        awaitFrames(5)
        enc.requestBitrate(100_000)
        paint(5, firstPtsMs = 6 * STEP_MS)
        awaitFrames(10)
        enc.collectOutput() // throws if the output thread failed
        assertTrue("frames must keep coming out after requestBitrate(), got ${frames().size}", frames().size >= 9)
    }

    // ── what the ride log says of the codec, read from the running codec ───────────────────────────────────

    /** A prepared encoder, [report] as the video loop sets it (true for a stream's first encoder, false for one it rebuilds), 6 frames through it; the ride log since the test began. */
    private fun encodeSomeFramesAndReadTheLog(report: Boolean): String {
        probe.begin()
        val enc = newEncoder().also { encoder = it }
        enc.reportCodecs = report
        assertTrue("prepare failed: ${enc.lastPrepareError}", enc.prepare(4, 200_000, 1))
        painter = EglPainter(checkNotNull(enc.inputSurface))
        paint(6, firstPtsMs = STEP_MS)
        awaitFrames(6)
        return probe.text()
    }

    private fun onlyLine(text: String, needle: String): String {
        val found = RideLogProbe.lines(text, needle)
        assertEquals("exactly one \"$needle\" line, got $found", 1, found.size)
        return found.single()
    }

    /** The `key=value` pairs of a log line like `prefix a=1 b=2; not echoed: c`: the part before the first `;`, as a map. */
    private fun keysOf(line: String, prefix: String): Map<String, String> =
        line.substringAfter(prefix).substringBefore(";").trim().split(" ").filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun theLogSaysWhatTheCodecAcceptedOnceItsFormatsCanBeRead() {
        encodeSomeFramesAndReadTheLog(report = true)
        val text = probe.await("the line of what the codec accepted") { "[stream] encoder accepted: " in it }

        val codecs = onlyLine(text, "[stream] codecs: encoder ")
        assertTrue(codecs, "video/avc 526x300 baseline [" in codecs && " chosen=" in codecs)
        assertTrue(codecs, codecs.endsWith("(hw)") || codecs.endsWith("(sw)"))
        val outputLine = onlyLine(text, "[stream] encoder output format: ")
        val acceptedLine = onlyLine(text, "[stream] encoder accepted: ")
        val output = keysOf(outputLine, "encoder output format: ")
        val accepted = keysOf(acceptedLine, "encoder accepted: ")

        assertTrue("the codec must have echoed some key of the format it was asked for, got: $acceptedLine", accepted.isNotEmpty())
        output.forEach { (key, value) ->
            assertEquals("the output format's $key must be the one in the accepted line", value, accepted[key])
        }
        // A value the codec changed is told as such: what it echoes of the 200 kbps and 4 a second asked is the same, or the line says "differs".
        accepted["bitrate"]?.let { assertTrue(acceptedLine, it == "200000" || "bitrate asked 200000" in acceptedLine) }
        accepted["frame-rate"]?.let { assertTrue(acceptedLine, it == "4" || "frame-rate asked 4" in acceptedLine) }
    }

    /**
     * The codec's input format is read once it runs (before, a surface encoder has none to give): so the accepted line holds every
     * key that its input format or its output format holds. The input format is read here from a codec of the same name, configured the same way.
     */
    @Test
    fun theAcceptedLineHoldsWhatTheInputFormatReadAfterStartHasBesideTheOutputFormat() {
        encodeSomeFramesAndReadTheLog(report = true)
        val text = probe.await("the line of what the codec accepted") { "[stream] encoder accepted: " in it }
        val codecs = onlyLine(text, "[stream] codecs: encoder ")
        val started = RideLogProbe.lines(text, "[stream] encoder: ").last()
        val accepted = keysOf(onlyLine(text, "[stream] encoder accepted: "), "encoder accepted: ")
        val output = keysOf(onlyLine(text, "[stream] encoder output format: "), "encoder output format: ")
        painter?.release()
        painter = null
        encoder?.release()
        encoder = null
        assumeTrue("the encoder took the full format on its first try, the one this test builds again: $started", "lowLatency=on" in started)
        val name = checkNotNull(Regex("""chosen=(\S+)""").find(codecs)?.groupValues?.get(1)) { "no codec named in: $codecs" }

        val format = DashEncoder.buildFormat(DashEncoder.Companion.Ask(4, 200_000, 1), lowLatency = true)
        val bare = MediaCodec.createByCodecName(name)
        var surface: android.view.Surface? = null
        val inputKeys = try {
            bare.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = bare.createInputSurface()
            bare.start()
            val input = bare.inputFormat
            CodecReport.KEYS.filter { key -> input.containsKey(key) && runCatching { input.getNumber(key) }.getOrNull() != null }
        } finally {
            runCatching { bare.stop() }
            bare.release()
            surface?.release()
        }

        val left = (inputKeys + output.keys).filter { it !in accepted }
        assertTrue("the accepted line leaves out $left, which the codec's input format ($inputKeys) or output format (${output.keys}) holds; it has ${accepted.keys}", left.isEmpty())
    }

    @Test
    fun anEncoderTheLoopRebuildsTellsNothingOfItsCodecButStillLogsItsStart() {
        val text = encodeSomeFramesAndReadTheLog(report = false)

        assertTrue("the start of the encoder is logged all the same, got: $text", "[stream] encoder: " in text)
        for (once in listOf("[stream] codecs: encoder ", "[stream] encoder accepted: ", "[stream] encoder output format: ")) {
            assertFalse("a rebuilt encoder must not tell \"$once\" again, got: $text", once in text)
        }
    }

    // ── the keyframe interval of the real encoder ──────────────────────────────────────────────────────────

    /** [seconds] of frames painted at [fps], the encoder asked for a keyframe every [intervalSec] s at that rate hint; the times of the keyframes that came out. */
    private fun keyframeTimesMs(fps: Int, intervalSec: Int, seconds: Int): List<Long> {
        val enc = newEncoder().also { encoder = it }
        assertTrue("prepare failed: ${enc.lastPrepareError}", enc.prepare(fps, 200_000, intervalSec))
        painter = EglPainter(checkNotNull(enc.inputSurface))
        val total = fps * seconds
        repeat(total) { i ->
            checkNotNull(painter).paint(colors[i % colors.size], (i + 1) * 1000L / fps)
            Thread.sleep(1000L / fps)
        }
        awaitFrames(total - 1)
        return frames().filter { it.isKey }.map { it.ptsMs }
    }

    /** One keyframe per window of [intervalSec] s: about [seconds] / [intervalSec] of them, each about that long after the one before. */
    private fun assertAKeyframeEvery(intervalSec: Int, seconds: Int, keys: List<Long>) {
        val windowMs = intervalSec * 1000L
        val expected = seconds / intervalSec
        assertTrue("keyframes at $keys ms: expected about $expected, one every $intervalSec s", keys.size in expected - 1..expected + 1)
        val gaps = keys.zipWithNext { a, b -> b - a }
        assertTrue("gaps of $gaps ms between the keyframes: each must be about $windowMs ms", gaps.all { it in windowMs * 3 / 4..windowMs * 5 / 4 })
    }

    @Test
    fun theEncoderKeepsAKeyframeEverySecondAtFourFramesASecond() {
        probe.begin()
        val keys = keyframeTimesMs(fps = 4, intervalSec = 1, seconds = 6)
        assertAKeyframeEvery(1, 6, keys)
        val started = probe.text()
        assertTrue("the log says how many frames a second is: $started", "iFrameIntervalSec=1 (every 4 frames at the hint)" in started)
    }

    @Test
    fun theEncoderKeepsAKeyframeEverySecondAtSixFramesASecond() {
        assertAKeyframeEvery(1, 6, keyframeTimesMs(fps = 6, intervalSec = 1, seconds = 6))
    }

    // ── getMetrics(), read without holding anyone beyond its limit ─────────────────────────────────────────

    @Test
    fun releasingTheEncoderLogsTheCodecsMetricsAndTheReleaseIsNotHeldByThem() {
        probe.begin()
        val enc = start()
        paint(5, firstPtsMs = STEP_MS)
        awaitFrames(5)
        painter?.release()
        painter = null

        val begun = System.nanoTime()
        enc.release()
        val tookMs = (System.nanoTime() - begun) / NANOS_PER_MS

        assertTrue(
            "release() took $tookMs ms: the read of the metrics may hold it for ${CodecMetrics.JOIN_MS} ms only, and the output thread's wait and the codec's stop come on top",
            tookMs <= RELEASE_BUDGET_MS,
        )
        val text = probe.await("the metrics of the encoder") { "[stream] codec metrics (encoder " in it }
        val line = onlyLine(text, "[stream] codec metrics (encoder ")
        assertFalse("the metrics must have been read before the codec went: $line", "not available" in line || "no answer" in line || "(late:" in line)
        assertTrue(line, "mime=video/avc" in line)
        assertTrue(line, "encoder=1" in line)
    }

    @Test
    fun theMetricsOfARunningCodecAreReadWithinTheLimitAndAReadThatHangsIsGivenUpAtIt() {
        val format = DashEncoder.buildFormat(DashEncoder.Companion.Ask(4, 200_000, 1), lowLatency = false)
        val bare = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: android.view.Surface? = null
        try {
            bare.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = bare.createInputSurface()
            bare.start()

            val told = CopyOnWriteArrayList<String>()
            val begun = System.nanoTime()
            CodecMetrics.logBounded("encoder", bare.name, bare) { told += it }
            val tookMs = (System.nanoTime() - begun) / NANOS_PER_MS
            assertTrue("the read of a running codec's metrics took $tookMs ms, the limit is ${CodecMetrics.JOIN_MS} ms", tookMs <= CodecMetrics.JOIN_MS + SLACK_MS)
            assertEquals("one line for the read: $told", 1, told.size)
            assertTrue(told.single(), told.single().startsWith("codec metrics (encoder ${bare.name}): "))
            assertFalse("a started codec gives its metrics: ${told.single()}", "not available" in told.single())
            val values = checkNotNull(CodecMetrics.read(bare)) { "a started codec gives its metrics" }
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, values[MediaCodec.MetricsConstants.MIME_TYPE])

            // A driver that does not answer: the caller is let go at the limit, not when the read ends, and the line says the read was given up.
            val hung = CopyOnWriteArrayList<String>()
            val hungBegun = System.nanoTime()
            CodecMetrics.logBounded("encoder", bare.name, { Thread.sleep(HANG_MS); null }, CodecMetrics.JOIN_MS) { hung += it }
            val hungMs = (System.nanoTime() - hungBegun) / NANOS_PER_MS
            assertTrue("a read that hangs for $HANG_MS ms held the caller for $hungMs ms, the limit is ${CodecMetrics.JOIN_MS} ms", hungMs <= CodecMetrics.JOIN_MS + SLACK_MS)
            assertTrue("the caller waited less than the limit ($hungMs ms) though the read had not ended", hungMs >= CodecMetrics.JOIN_MS - SLACK_MS)
            assertEquals("the line of the read given up, and none other yet: $hung", 1, hung.size)
            assertTrue(hung.single(), "no answer in ${CodecMetrics.JOIN_MS} ms, given up" in hung.single())
        } finally {
            runCatching { bare.stop() }
            bare.release()
            surface?.release()
        }
    }

    private companion object {
        const val STEP_MS = 62L
        const val NANOS_PER_MS = 1_000_000L

        /** What release() may take: the metrics' wait ([CodecMetrics.JOIN_MS]), the output thread's wait (200 ms) and the codec's own stop, with room for a busy emulator. */
        const val RELEASE_BUDGET_MS = 2_000L

        /** What a wait with a limit may overshoot it by on a busy emulator (starting the thread, the scheduler). */
        const val SLACK_MS = 250L

        /** A read of the metrics that does not come back for this long. */
        const val HANG_MS = 2_000L
        const val NAL_SLICE = 1
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8

        /** NAL unit types of an Annex-B buffer, in order (after each 00 00 01 start code). */
        fun nalTypes(data: ByteArray): List<Int> = startCodes(data).map { data[it].toInt() and 0x1F }

        /** profile_idc of the first SPS in [data]. */
        fun profileIdc(data: ByteArray): Int {
            val at = startCodes(data).first { data[it].toInt() and 0x1F == NAL_SPS }
            return data[at + 1].toInt() and 0xFF
        }

        /** Index of the NAL header byte after each start code. */
        private fun startCodes(data: ByteArray): List<Int> = (0..data.size - 4).filter {
            data[it].toInt() == 0 && data[it + 1].toInt() == 0 && data[it + 2].toInt() == 1
        }.map { it + 3 }
    }
}
