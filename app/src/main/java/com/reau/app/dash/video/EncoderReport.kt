package com.reau.app.dash.video

import android.media.MediaCodec
import android.media.MediaFormat
import com.reau.app.dash.DashConfig
import com.reau.app.data.RideDiagnostics
import com.reau.app.util.CodecReport

/**
 * The lines of the ride log about the dash's encoder that [DashEncoder] tells once for a stream (`codecs: encoder …`, `encoder accepted: …`,
 * `encoder output format: …`; plan 23). Read from the codec once, when it starts and when it announces its output format; nothing here is on the frame path, nothing changes how the
 * encoder is built, and none of it throws.
 */
internal object EncoderReport {
    /** The `codecs:` line: the phone's AVC encoders as the one look at the codec list of the process saw them ([candidates]) and the one in use. */
    fun choice(c: MediaCodec, candidates: String?, frame: DashConfig.FrameSize) {
        runCatching {
            val hardware = c.codecInfo.let { it.isHardwareAccelerated && !it.isSoftwareOnly }
            RideDiagnostics.log(
                "stream",
                CodecReport.line("encoder", MIME, "${frame.width}x${frame.height} baseline", candidates ?: "not listed", c.name, hardware),
            )
        }
    }

    /** The input format the started codec holds (read once, right after `start()`; null when it cannot be read). The c2 encoders echo little in it. */
    fun inputFormat(c: MediaCodec): MediaFormat? = runCatching { c.inputFormat }.getOrNull()

    /**
     * What the codec took of the [asked] format: its [output] format when it announces it (before the first buffer, on the output thread,
     * never the frame loop's) over the [input] one read at the start. A key neither of them has is told as `not echoed`, not as a difference.
     */
    fun accepted(asked: MediaFormat, input: MediaFormat?, output: MediaFormat) {
        runCatching {
            val wanted = CodecReport.KEYS.mapNotNull { key -> numberOf(asked, key)?.let { key to it } }.toMap()
            val text = CodecReport.accepted(wanted, CodecReport.overlay({ numberOf(output, it) }, { key -> input?.let { numberOf(it, key) } }))
            RideDiagnostics.log("stream", "encoder accepted: $text")
        }
    }

    /**
     * The codec announced its [output] format: the lines about it (on the output thread): the format, what it took of the one asked for, and the rate control
     * asked, sent and accepted ([rateLine]; [rateSent] is what the encoder built, `CBR -> VBR (CBR not offered)`).
     */
    fun outputAnnounced(asked: MediaFormat, input: MediaFormat?, output: MediaFormat, rateSent: String) {
        outputFormat(output)
        accepted(asked, input, output)
        runCatching {
            val mode = CodecReport.overlay({ numberOf(output, it) }, { key -> input?.let { numberOf(it, key) } })("bitrate-mode")
            RideDiagnostics.log("stream", rateLine(rateSent, mode?.toInt()))
        }
    }

    /**
     * The line of the rate control of a session, the same for either encoder (26/11/2026): `encoder rate: asked CBR -> sent VBR (CBR not offered), codec says VBR`.
     * [mode] is the `bitrate-mode` the codec echoed (null: it did not).
     */
    fun rateLine(rateSent: String, mode: Int?): String = "encoder rate: $rateSent, codec says ${CodecReport.bitrateModeName(mode)}"

    /**
     * What the encoder sent of the rate control, for [rateLine] and [pickLine]: `CBR -> CBR`, `CBR -> VBR (CBR not offered)`, and `CBR -> none (refused)` when the codec
     * refused the mode it was offered and the last rung of [DashEncoder.startNamed] opened it without one ([sentMode] null while [rate] had a mode: review of 26/11/2026, D4).
     * [asked] is the mode the caller named (null: none).
     */
    fun rateSentText(asked: String?, rate: DashEncoder.RateMode, sentMode: Int?): String =
        "${asked ?: "default"} -> ${if (rate.constant != null && sentMode == null) "none (refused)" else rate.label}"

    /**
     * The line of the choice of the encoder of a session, told once with the `codecs:` line (26/11/2026): what the rider asked ([preference]), what came of it
     * ([note], null when the phone's own was asked) and the codec in use, with its rate control as the encoder sent it ([rateSent]).
     */
    fun pick(preference: VideoEncoder, note: String?, c: MediaCodec, rateSent: String) {
        runCatching {
            val hardware = c.codecInfo.let { it.isHardwareAccelerated && !it.isSoftwareOnly }
            RideDiagnostics.log("stream", pickLine(preference, c.name, hardware, rateSent, note))
        }
    }

    /** The text of [pick]: `encoder in use: asked=SOFTWARE name=c2.android.avc.encoder kind=sw rate=CBR -> CBR`, with `; note` when there is one. */
    fun pickLine(preference: VideoEncoder, name: String, hardware: Boolean, rateSent: String, note: String?): String =
        "encoder in use: asked=${preference.name} name=$name kind=${if (hardware) "hw" else "sw"} rate=$rateSent" + (note?.let { "; $it" } ?: "")

    /** The codec's output format, when it changes (once, before the first buffer). */
    fun outputFormat(f: MediaFormat) {
        runCatching { RideDiagnostics.log("stream", "encoder output format: ${CodecReport.accepted(emptyMap()) { numberOf(f, it) }}") }
    }

    /** The number [key] holds in [format] (an int or a float; null when it has none). */
    private fun numberOf(format: MediaFormat, key: String): Number? = if (format.containsKey(key)) runCatching { format.getNumber(key) }.getOrNull() else null

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
}
