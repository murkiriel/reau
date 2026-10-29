package com.reau.app.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import com.reau.app.dash.protocol.K1GPacket
import com.reau.app.web.HttpRequestParser
import com.reau.app.web.WebSocketCodec
import com.reau.app.web.WebSocketViolation
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Coverage-guided fuzzing (Jazzer) of the three parsers that read bytes from outside, with the same promises as [ParserFuzzTest]: no input may
 * make them throw what they do not declare, nor read past what they were given. In a normal unit-test run each target only replays its seeds,
 * the files under `src/test/resources/com/reau/app/fuzz/ParserFuzzTargetsInputs/<target>/` (a regression test, a few milliseconds); with the
 * environment variable `JAZZER_FUZZ=1` it fuzzes for [FUZZ_FOR] each, steered by the code it reaches, and a failing input is saved under
 * `.cifuzz-corpus` or in the working folder as `crash-<hash>` (copy it into the target's seed folder once the bug is fixed, so it stays fixed).
 * See CONTRIBUTING, "Fuzzing".
 */
class ParserFuzzTargets {
    /** A packet from the dash over UDP: any bytes parse, the TLVs add up to no more than the packet holds. */
    @FuzzTest(maxDuration = FUZZ_FOR)
    fun dashPacket(data: ByteArray) {
        val parsed = K1GPacket.parse(data)
        val total = parsed.tlvs.sumOf { 4 + it.value.size }
        check(total <= maxOf(0, data.size - 8)) { "the TLVs say $total bytes of ${data.size}" }
        parsed.tlvs.forEach { check(it.type in 0..255 && it.sub in 0..255) { "type ${it.type} sub ${it.sub}" } }
    }

    /** A frame from a browser: a frame within the limit, the end of the stream, or a declared violation. */
    @FuzzTest(maxDuration = FUZZ_FOR)
    fun webSocketFrame(data: ByteArray) {
        try {
            val frame = WebSocketCodec.read(ByteArrayInputStream(data), maxPayload = 64)
            if (frame != null) check(frame.payload.size <= 64) { "a payload of ${frame.payload.size} bytes over the limit of 64" }
        } catch (_: WebSocketViolation) {
            // declared
        }
    }

    /** The head of an HTTP request from a browser: a request for a path, or a refusal with one of the three statuses; only an IOException may come out. */
    @FuzzTest(maxDuration = FUZZ_FOR)
    fun httpHead(data: ByteArray) {
        try {
            when (val result = HttpRequestParser.parse(ByteArrayInputStream(data))) {
                is HttpRequestParser.Result.Ok -> check(result.request.path.startsWith("/")) { "path ${result.request.path}" }
                is HttpRequestParser.Result.Bad -> check(result.status in listOf(400, 405, 431)) { "status ${result.status}" }
            }
        } catch (_: IOException) {
            // a head that came too slowly: declared
        }
    }

    private companion object {
        const val FUZZ_FOR = "20s"
    }
}
