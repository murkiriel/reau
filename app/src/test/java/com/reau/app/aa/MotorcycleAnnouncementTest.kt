package com.reau.app.aa

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The experiment "Android Auto in motorcycle mode" ([AaConfig.motorcycle]), on the wire. The bytes of the service discovery response are read here with a
 * minimal reader of the protobuf wire format, NOT with the generated classes: the head unit info of this app numbers its eight strings in another order than
 * Android Auto's own copy of the message (which is the only reader), and has no vehicle type, so only the numbers on the wire say what Android Auto reads.
 *
 * Android Auto's numbering of the head unit info: 1 make, 2 model, 3 year, 4 vehicle_id, 5 head_unit_make, 6 head_unit_model, 7 head_unit_software_build,
 * 8 head_unit_software_version, 9 vehicle_type (0 not said, 1 car, 2 truck, 3 motorcycle).
 *
 * Off, the response is byte for byte the one of before the experiment: ServiceDiscoveryNavigationTest holds that response against the golden bytes of the
 * original; here the head unit info is read again field by field, and the whole message is compared with the one of the default configuration.
 */
class MotorcycleAnnouncementTest {
    private class Field(val number: Int, val varint: Long?, val bytes: ByteArray?) {
        val text: String get() = String(bytes!!, Charsets.UTF_8)
    }

    /** The fields of one message in the order they are on the wire; only varints and length-delimited fields are expected here. */
    private fun fields(bytes: ByteArray): List<Field> {
        val input = CodedInputStream.newInstance(bytes)
        val out = mutableListOf<Field>()
        while (!input.isAtEnd) {
            val tag = input.readTag()
            val number = WireFormat.getTagFieldNumber(tag)
            when (WireFormat.getTagWireType(tag)) {
                WireFormat.WIRETYPE_VARINT -> out += Field(number, input.readInt64(), null)
                WireFormat.WIRETYPE_LENGTH_DELIMITED -> out += Field(number, null, input.readByteArray())
                else -> fail("field $number: a wire type this test does not expect")
            }
        }
        return out
    }

    private fun payload(config: AaConfig): ByteArray = ServiceDiscoveryResponse(config).data.let { it.copyOfRange(HEADER, it.size) }

    private fun top(config: AaConfig): List<Field> = fields(payload(config))

    private fun info(config: AaConfig): ByteArray = top(config).single { it.number == HEADUNIT_INFO }.bytes!!

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

    /** One string field as it goes on the wire (short strings only: one byte of length). */
    private fun text(number: Int, value: String): String {
        val raw = value.toByteArray(Charsets.UTF_8)
        require(raw.size < 128)
        return "%02X%02X".format((number shl 3) or 2, raw.size) + hex(raw)
    }

    private val moto = AaConfig(motorcycle = true)
    private val version = com.reau.app.BuildConfig.VERSION_NAME

    @Test
    fun `it is off by default`() {
        assertFalse(AaConfig().motorcycle)
        assertFalse(AaConfig(navigation = true, audio = true, media = true, phone = true).motorcycle)
    }

    @Test
    fun `off, the whole response is the one of the default configuration and the head unit info is the eight texts it always was, with no field 9`() {
        assertArrayEquals(ServiceDiscoveryResponse(AaConfig()).data, ServiceDiscoveryResponse(AaConfig(motorcycle = false)).data)
        // this app's numbering, as the golden bytes of ServiceDiscoveryNavigationTest have it
        val expected = text(1, "REau") + text(2, "REau") + text(3, "REau") + text(4, "REau") + text(5, "2026") + text(6, "1") + text(7, "reau") + text(8, version)
        assertEquals(expected, hex(info(AaConfig())))
        assertEquals(expected, hex(info(AaConfig(motorcycle = false, navigation = true))))
        assertTrue("no vehicle type", fields(info(AaConfig())).none { it.number == 9 })
        assertEquals("reau", top(AaConfig()).single { it.number == TOP_VEHICLE_ID }.text)
    }

    @Test
    fun `on, the head unit info goes out in Android Auto's numbering, 1 to 8 and then 9 = 3`() {
        val read = fields(info(moto))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), read.map { it.number })
        assertEquals("1 make", "REau", read[0].text)
        assertEquals("2 model", "REau", read[1].text)
        assertEquals("3 year", "2026", read[2].text)
        assertEquals("4 vehicle_id", "reau-moto-1", read[3].text)
        assertEquals("5 head_unit_make", "REau", read[4].text)
        assertEquals("6 head_unit_model", "REau", read[5].text)
        assertEquals("7 head_unit_software_build", "1", read[6].text)
        assertEquals("8 head_unit_software_version", version, read[7].text)
        assertEquals("9 vehicle_type: motorcycle", 3L, read[8].varint)
        val expected = text(1, "REau") + text(2, "REau") + text(3, "2026") + text(4, "reau-moto-1") + text(5, "REau") + text(6, "REau") +
            text(7, "1") + text(8, version) + "4803"
        assertEquals(expected, hex(info(moto)))
    }

    @Test
    fun `on, the top level carries the same new vehicle id, in field 5, and its other texts are the ones they were`() {
        val off = top(AaConfig()).associateBy { it.number }
        val on = top(moto).associateBy { it.number }
        assertEquals("reau-moto-1", on.getValue(TOP_VEHICLE_ID).text)
        assertEquals("the same id in the head unit info", fields(info(moto))[3].text, on.getValue(TOP_VEHICLE_ID).text)
        assertEquals("REau", on.getValue(2).text)
        assertEquals("REau", on.getValue(3).text)
        assertEquals("2026", on.getValue(4).text)
        for (n in 7..10) assertEquals("top-level field $n", off.getValue(n).text, on.getValue(n).text)
    }

    @Test
    fun `on, nothing else changes, the services, the driver position and every other top-level field are the same bytes`() {
        fun rest(config: AaConfig) = top(config).filter { it.number != TOP_VEHICLE_ID && it.number != HEADUNIT_INFO }
            .map { "${it.number}:${it.varint}:${it.bytes?.let { b -> hex(b) }}" }
        assertEquals(rest(AaConfig()), rest(moto))
        // and the same with the optional services on
        val all = AaConfig(navigation = true, audio = true, media = true, phone = true)
        assertEquals(rest(all), rest(all.copy(motorcycle = true)))
        // the service ids (microphone included) are the five of always
        val services = top(moto).filter { it.number == 1 }.map { fields(it.bytes!!).first { f -> f.number == 1 }.varint!!.toInt() }
        assertEquals(listOf(Channel.ID_SEN, Channel.ID_VID, Channel.ID_INP, Channel.ID_AU2, Channel.ID_MIC), services)
    }

    @Test
    fun `the vehicle id of the experiment is new and fixed, not the one of the car announcement`() {
        assertEquals("reau-moto-1", ServiceDiscoveryResponse.VEHICLE_ID_MOTORCYCLE)
        assertEquals(3L, ServiceDiscoveryResponse.VEHICLE_TYPE_MOTORCYCLE)
        assertEquals("reau", ServiceDiscoveryResponse.vehicleIdOf(AaConfig()))
        assertEquals("reau-moto-1", ServiceDiscoveryResponse.vehicleIdOf(moto))
        assertNotEquals(ServiceDiscoveryResponse.vehicleIdOf(AaConfig()), ServiceDiscoveryResponse.vehicleIdOf(moto))
        // two responses of the experiment are the same bytes: the identity does not change from one session to the next
        assertArrayEquals(ServiceDiscoveryResponse(moto).data, ServiceDiscoveryResponse(moto).data)
    }

    @Test
    fun `the vehicle type is an unknown field of the generated message, which was not edited for it`() {
        // the field 9 is an unknown field of this app's message: it is read back as one, so nothing in the generated code was edited for it
        val parsed = ServiceDiscoveryResponse(moto).parse(com.reau.app.aa.proto.Control.ServiceDiscoveryResponse.newBuilder()).build().headunitInfo
        assertEquals(setOf(9), parsed.unknownFields.asMap().keys)
        assertEquals(listOf(3L), parsed.unknownFields.getField(9).varintList)
    }

    @Test
    fun `the log says it, only for the experiment, with the vehicle id`() {
        assertNull(DiscoveryLog.announcedLine(AaConfig()))
        val line = DiscoveryLog.announcedLine(moto)!!
        assertTrue(line, "MOTORCYCLE" in line && "vehicle_type 3" in line && "vehicle_id reau-moto-1" in line)
        assertTrue(line, line.startsWith("Service Discovery Response"))
    }

    @Test
    fun `the control writes that line before the response it sends`() {
        val control = java.io.File("src/main/java/com/reau/app/aa/AapControl.kt").readText()
        val request = control.substringAfter("private fun serviceDiscoveryRequest(").substringBefore("private fun pingRequest")
        val said = request.indexOf("DiscoveryLog.announcedLine(config)?.let { AaLog.i(it) }")
        assertTrue(request, said >= 0 && said < request.indexOf("aapTransport.send(ServiceDiscoveryResponse(config))"))
    }

    private companion object {
        /** The message's channel, flags, length and type (AapMessage.HEADER_SIZE + MsgType.SIZE). */
        const val HEADER = 6
        const val TOP_VEHICLE_ID = 5
        const val HEADUNIT_INFO = 17
    }
}
