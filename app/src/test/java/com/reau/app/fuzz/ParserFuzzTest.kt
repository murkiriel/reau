package com.reau.app.fuzz

import com.reau.app.aa.VideoFragmentAssembler
import com.reau.app.dash.protocol.K1GPacket
import com.reau.app.dash.protocol.Tlv
import com.reau.app.web.HttpRequestParser
import com.reau.app.web.WebSocketCodec
import com.reau.app.web.WebSocketViolation
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The parsers that read bytes from outside (the dash over UDP, a browser on the network, Android Auto's video) are given thousands of
 * odd inputs from a fixed seed ([Seeded]): none may throw what it does not declare, none may read past its input, and what is built
 * must read back the same. This is the cheap part of a fuzz test, in the normal run; a coverage-guided fuzzer (Jazzer) would go deeper.
 */
class ParserFuzzTest {
    // ── K1G packets from the dash ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `any bytes from the dash parse without throwing and without reading past the packet`() {
        val rnd = Seeded.random(1)
        repeat(Seeded.ROUNDS) { round ->
            val data = Seeded.bytes(rnd, 300)
            val parsed = try {
                K1GPacket.parse(data)
            } catch (e: Exception) {
                fail("round $round, ${data.size} bytes: ${e.javaClass.simpleName}")
                return
            }
            val total = parsed.tlvs.sumOf { 4 + it.value.size }
            assertTrue("round $round: the TLVs say $total bytes of ${data.size}", total <= maxOf(0, data.size - 8))
            parsed.tlvs.forEach { assertTrue("round $round", it.type in 0..255 && it.sub in 0..255) }
        }
    }

    private fun randomTlvs(rnd: java.util.Random) = List(rnd.nextInt(6)) { Tlv(rnd.nextInt(256), rnd.nextInt(256), Seeded.bytes(rnd, 40)) }

    @Test
    fun `what is built reads back the same, after the packet's own first segment`() {
        val rnd = Seeded.random(2)
        repeat(Seeded.ROUNDS) { round ->
            val tlvs = randomTlvs(rnd)
            val parsed = K1GPacket.parse(K1GPacket.build(*tlvs.toTypedArray()))
            assertEquals("round $round", false, parsed.cutShort)
            val read = parsed.tlvs.drop(1) // the first segment of a packet is its own "K1G " header
            assertEquals("round $round", tlvs.size, read.size)
            tlvs.zip(read).forEach { (a, b) ->
                assertEquals(a.type, b.type)
                assertEquals(a.sub, b.sub)
                assertArrayEquals("round $round", a.value, b.value)
            }
        }
    }

    @Test
    fun `a packet cut anywhere gives a prefix of what the whole one gives, and says it was cut`() {
        val rnd = Seeded.random(3)
        repeat(Seeded.ROUNDS / 3) { round ->
            val whole = K1GPacket.build(*randomTlvs(rnd).toTypedArray())
            val full = K1GPacket.parse(whole).tlvs
            val cut = whole.copyOf(rnd.nextInt(whole.size + 1))
            val part = K1GPacket.parse(cut)
            assertTrue("round $round: a cut packet has no more than the whole", part.tlvs.size <= full.size)
            part.tlvs.zip(full).forEach { (a, b) ->
                assertEquals("round $round", a.type, b.type)
                assertArrayEquals("round $round", a.value, b.value)
            }
            if (cut.size < whole.size && part.tlvs.size == full.size) fail("round $round: all the TLVs of a packet cut at ${cut.size} of ${whole.size}")
        }
    }

    // ── WebSocket frames from a browser ──────────────────────────────────────────────────────────────────────

    private fun maskedFrame(opcode: Int, payload: ByteArray, rnd: java.util.Random): ByteArray {
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        val head = when {
            payload.size < 126 -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or payload.size).toByte())
            else -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or 126).toByte(), (payload.size shr 8).toByte(), payload.size.toByte())
        }
        return head + mask + ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
    }

    @Test
    fun `any bytes from a browser are a frame, the end of the stream or a violation, nothing else`() {
        val rnd = Seeded.random(4)
        repeat(Seeded.ROUNDS) { round ->
            val data = Seeded.bytes(rnd, 200)
            try {
                val frame = WebSocketCodec.read(ByteArrayInputStream(data), maxPayload = 64)
                if (frame != null) assertTrue("round $round", frame.payload.size <= 64)
            } catch (e: WebSocketViolation) {
                // declared
            } catch (e: Exception) {
                fail("round $round: ${e.javaClass.simpleName} on ${data.size} bytes")
            }
        }
    }

    @Test
    fun `a masked frame reads back its payload, and a damaged one is a violation or another valid frame`() {
        val rnd = Seeded.random(5)
        repeat(Seeded.ROUNDS) { round ->
            val payload = Seeded.bytes(rnd, 300)
            val opcode = listOf(1, 2).random(kotlin.random.Random(round))
            val frame = maskedFrame(opcode, payload, rnd)
            val read = WebSocketCodec.read(ByteArrayInputStream(frame), maxPayload = 1_000)
            assertNotNull("round $round", read)
            assertEquals(opcode, read!!.opcode)
            assertArrayEquals("round $round", payload, read.payload)
            try {
                WebSocketCodec.read(ByteArrayInputStream(Seeded.damaged(rnd, frame)), maxPayload = 1_000)
            } catch (e: WebSocketViolation) {
                // declared
            } catch (e: Exception) {
                fail("round $round: ${e.javaClass.simpleName} on a damaged frame")
            }
        }
    }

    @Test
    fun `an empty stream ends between frames without a violation`() {
        assertNull(WebSocketCodec.read(ByteArrayInputStream(ByteArray(0)), maxPayload = 10))
    }

    // ── the HTTP head from a browser ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `any head is a request or a refusal with a status, and only an IOException may come out`() {
        val rnd = Seeded.random(6)
        val pieces = listOf("GET", " ", "/", "/ws", "?k=1234", "&", "=", "%zz", "%41", "HTTP/1.1", "\r\n", "\r\n\r\n", "Host: x", ":", "é", "\u0000")
        repeat(Seeded.ROUNDS) { round ->
            val head = buildString { repeat(rnd.nextInt(14)) { append(pieces[rnd.nextInt(pieces.size)]) } } +
                (if (rnd.nextBoolean()) "\r\n\r\n" else "")
            val bytes = if (rnd.nextInt(4) == 0) Seeded.bytes(rnd, 120) else head.toByteArray(Charsets.ISO_8859_1)
            try {
                when (val result = HttpRequestParser.parse(ByteArrayInputStream(bytes))) {
                    is HttpRequestParser.Result.Ok -> assertTrue("round $round: ${result.request.path}", result.request.path.startsWith("/"))
                    is HttpRequestParser.Result.Bad -> assertTrue("round $round: ${result.status}", result.status in listOf(400, 405, 431))
                }
            } catch (e: IOException) {
                // a head that came too slowly: declared
            } catch (e: Exception) {
                fail("round $round: ${e.javaClass.simpleName} on ${bytes.size} bytes")
            }
        }
    }

    @Test
    fun `a head larger than the limit is refused, not read to the end`() {
        val huge = ByteArray(200_000) { 'a'.code.toByte() }
        val result = HttpRequestParser.parse(ByteArrayInputStream(huge))
        assertEquals(431, (result as HttpRequestParser.Result.Bad).status)
    }

    // ── the order of Android Auto's video fragments ──────────────────────────────────────────────────────────

    @Test
    fun `whatever order the fragments come in, a piece is appended only inside an assembly that began, and never twice decoded`() {
        val rnd = Seeded.random(7)
        val flags = listOf(VideoFragmentAssembler.FLAG_SINGLE, VideoFragmentAssembler.FLAG_FIRST, VideoFragmentAssembler.FLAG_MIDDLE, VideoFragmentAssembler.FLAG_LAST, 0, 3, 12, 255)
        repeat(Seeded.ROUNDS / 6) { round ->
            val assembler = VideoFragmentAssembler()
            var assembling = false
            repeat(rnd.nextInt(40)) { step ->
                val flag = flags[rnd.nextInt(flags.size)]
                val decision = assembler.onMessage(flag, rnd.nextBoolean(), rnd.nextBoolean())
                val where = "round $round step $step flag $flag -> ${decision.action}"
                when (decision.action) {
                    is VideoFragmentAssembler.Action.DecodeWhole -> assertEquals(where, VideoFragmentAssembler.FLAG_SINGLE, flag)
                    is VideoFragmentAssembler.Action.BeginAssembly -> {
                        assertEquals(where, VideoFragmentAssembler.FLAG_FIRST, flag)
                        assembling = true
                    }
                    VideoFragmentAssembler.Action.Append -> assertTrue(where, assembling && flag == VideoFragmentAssembler.FLAG_MIDDLE)
                    VideoFragmentAssembler.Action.AppendAndDecode -> {
                        assertTrue(where, assembling && flag == VideoFragmentAssembler.FLAG_LAST)
                        assembling = false
                    }
                    is VideoFragmentAssembler.Action.Discard -> if (flag == VideoFragmentAssembler.FLAG_LAST) assembling = false
                }
                if (flag == VideoFragmentAssembler.FLAG_SINGLE) assembling = false
            }
        }
    }
}
