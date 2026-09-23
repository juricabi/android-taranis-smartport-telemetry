package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.crc.CRCMAVLink
import juricabi.com.telemetry.protocol.decoder.ArduPassthroughDecoder
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import juricabi.com.telemetry.protocol.decoder.FrskyDataDecoder
import juricabi.com.telemetry.protocol.decoder.MAVLinkDataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * GPS precision, as each link sends it: metres where the receiver measured
 * them, HDOP where only the geometry is known, S.Port's one digit where that
 * is all there is room for — and nothing where a firmware's "unknown" or
 * padding would otherwise be shown as a reading.
 */
class GpsPrecisionTest {

    private class Captor : DataDecoder.Companion.DefaultDecodeListener() {
        val heard = ArrayList<GpsPrecision>()
        override fun onGPSPrecisionData(precision: GpsPrecision) { heard.add(precision) }
    }

    // --- CRSF: Betaflight's GPS extended frame ---

    private fun crsfExtended(hAccCm: Int, hdopTenths: Int): ByteArray {
        val b = ByteBuffer.allocate(20)
        b.put(3)                          // a 3D fix
        b.position(13)
        b.putShort(hAccCm.toShort())      // h_acc, cm
        b.putShort(0)                     // v_acc
        b.put(0)                          // reserved
        b.put(hdopTenths.toByte())        // hDOP, tenths
        val body = byteArrayOf(0x06) + b.array()
        val crc = CRC8()
        crc.reset()
        body.forEach { crc.update(it) }
        return byteArrayOf(0xC8.toByte(), (body.size + 1).toByte()) + body + byteArrayOf(crc.value.toByte())
    }

    private fun crsf(vararg frames: ByteArray): List<GpsPrecision> {
        val captor = Captor()
        val protocol = CrsfProtocol(captor)
        frames.forEach { f -> f.forEach { protocol.process(it.toInt() and 0xFF) } }
        return captor.heard
    }

    @Test
    fun crsfMetresWinOverTheDop() {
        assertEquals(listOf(GpsPrecision.Metres(1.5f)), crsf(crsfExtended(150, 9)))
    }

    @Test
    fun crsfWithoutMetresShowsTheDop() {
        // an NMEA receiver leaves h_acc at 0
        assertEquals(listOf(GpsPrecision.Hdop(1.2f)), crsf(crsfExtended(0, 12)))
    }

    @Test
    fun crsfWithNeitherSaysNothing() {
        // and past 327 m the int16 has wrapped negative
        assertEquals(emptyList<GpsPrecision>(), crsf(crsfExtended(0, 0), crsfExtended(-200, 0)))
    }

    // --- MAVLink GPS_RAW_INT ---

    private fun heartbeat(autopilot: Int): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).put(1).put(autopilot.toByte()).put(0).put(4).put(3).array()

    private fun rawGps(eph: Int, hAccMm: Int? = null): ByteArray {
        val b = ByteBuffer.allocate(if (hAccMm == null) 30 else 38).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(1_000_000).putInt(450_000_000).putInt(160_000_000).putInt(123_000)
            .putShort(eph.toShort()).putShort(-1).putShort(0).putShort(9000)
            .put(3).put(12)
        if (hAccMm != null) b.putInt(0).putInt(hAccMm)
        return b.array()
    }

    private fun mav1Frame(messageId: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(payload.size.toByte(), 0, 1, 1, messageId.toByte())
        return byteArrayOf(0xFE.toByte()) + header + payload + checksum(header, payload, messageId)
    }

    private fun mav2Frame(messageId: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            payload.size.toByte(), 0, 0, 0, 1, 1,
            messageId.toByte(), (messageId shr 8).toByte(), (messageId shr 16).toByte()
        )
        return byteArrayOf(0xFD.toByte()) + header + payload + checksum(header, payload, messageId)
    }

    private fun checksum(header: ByteArray, payload: ByteArray, messageId: Int): ByteArray {
        val crc = CRCMAVLink()
        header.forEach { crc.update_checksum(it.toUByte().toInt()) }
        payload.forEach { crc.update_checksum(it.toUByte().toInt()) }
        crc.finish_checksum(messageId)
        return byteArrayOf(crc.lsb.toByte(), crc.msb.toByte())
    }

    private fun mav(protocol: (DataDecoder.Listener) -> Protocol, vararg frames: ByteArray): List<GpsPrecision> {
        val captor = Captor()
        val p = protocol(captor)
        frames.forEach { f -> f.forEach { p.process(it.toUByte().toInt()) } }
        return captor.heard
    }

    @Test
    fun mavlinkMillimetresMeanTheSameFromEveryone() {
        // iNav calls itself generic, and its h_acc is right where its eph is not
        assertEquals(
            listOf(GpsPrecision.Metres(1.8f)),
            mav(::MAVLink2Protocol, mav2Frame(HEARTBEAT, heartbeat(GENERIC)), mav2Frame(GPS_RAW, rawGps(180, 1800)))
        )
    }

    @Test
    fun arduPilotBehindNmeaFallsBackToHdop() {
        assertEquals(
            listOf(GpsPrecision.Hdop(0.9f)),
            mav(::MAVLink2Protocol, mav2Frame(HEARTBEAT, heartbeat(ARDUPILOT)), mav2Frame(GPS_RAW, rawGps(90, 0)))
        )
    }

    @Test
    fun aGroundStationsHeartbeatDoesNotUnsayTheAutopilot() {
        // MAV_AUTOPILOT_INVALID, from a GCS or a gimbal on the same link
        assertEquals(
            listOf(GpsPrecision.Hdop(0.9f)),
            mav(::MAVLink2Protocol,
                mav2Frame(HEARTBEAT, heartbeat(ARDUPILOT)),
                mav2Frame(HEARTBEAT, heartbeat(8)),
                mav2Frame(GPS_RAW, rawGps(90, 0)))
        )
    }

    @Test
    fun mavlink1HdopFromPx4() {
        assertEquals(
            listOf(GpsPrecision.Hdop(1.1f)),
            mav(::MAVLinkProtocol, mav1Frame(HEARTBEAT, heartbeat(PX4)), mav1Frame(GPS_RAW, rawGps(110)))
        )
    }

    @Test
    fun anEphFromINavIsNotTakenForHdop() {
        // centimetres of accuracy, in the HDOP field
        assertEquals(
            emptyList<GpsPrecision>(),
            mav(::MAVLinkProtocol, mav1Frame(HEARTBEAT, heartbeat(GENERIC)), mav1Frame(GPS_RAW, rawGps(180)))
        )
    }

    @Test
    fun unknownIsNotAReading() {
        assertEquals(
            emptyList<GpsPrecision>(),
            mav(::MAVLink2Protocol,
                mav2Frame(HEARTBEAT, heartbeat(ARDUPILOT)),
                mav2Frame(GPS_RAW, rawGps(9999, 99_990)),
                mav2Frame(GPS_RAW, rawGps(0xFFFF, 0)))
        )
    }

    // --- MAVLink HIGH_LATENCY2 ---

    private fun highLatency(epHDecimetres: Int): List<GpsPrecision> {
        val raw = ByteArray(42)
        raw[34] = epHDecimetres.toByte()
        val captor = Captor()
        MAVLinkDataDecoder(captor).decodeData(Protocol.Companion.TelemetryData(Protocol.HIGH_LATENCY, 0, raw))
        return captor.heard
    }

    @Test
    fun px4HighLatencyErrorIsMetres() {
        assertEquals(listOf(GpsPrecision.Metres(2.5f)), highLatency(25))
    }

    @Test
    fun arduPilotHighLatencyPaddingIsNothing() {
        assertEquals(emptyList<GpsPrecision>(), highLatency(0) + highLatency(255))
    }

    // --- ArduPilot passthrough 0x5002 ---

    private fun passthrough(hdopBits: Int): List<GpsPrecision> {
        val captor = Captor()
        ArduPassthroughDecoder(captor).gpsStatus((hdopBits shl 6) or (3 shl 4) or 10, withAltitude = false)
        return captor.heard
    }

    @Test
    fun passthroughHdopInTenths() {
        assertEquals(listOf(GpsPrecision.Hdop(0.8f)), passthrough(8 shl 1))
    }

    @Test
    fun passthroughHdopWithItsPowerOfTen() {
        assertEquals(listOf(GpsPrecision.Hdop(15f)), passthrough((15 shl 1) or 1))
    }

    @Test
    fun passthroughUnknownIsNothing() {
        assertEquals(emptyList<GpsPrecision>(), passthrough(0xFF))
    }

    // --- S.Port: iNav's and Betaflight's digit ---

    private fun sport(word: Int): List<GpsPrecision> {
        val captor = Captor()
        FrskyDataDecoder(captor).decodeData(Protocol.Companion.TelemetryData(Protocol.GPS_STATE, word))
        return captor.heard
    }

    @Test
    fun sportDigitIsTheHundreds() {
        // a fix, digit 2, seven satellites
        assertEquals(listOf(GpsPrecision.HdopDigit(2)), sport(1207))
    }

    @Test
    fun sportWithoutAGpsIsNothing() {
        assertEquals(emptyList<GpsPrecision>(), sport(0))
    }

    // --- LTM: iNav's X frame ---

    private fun ltm(hdopHundredths: Int): List<GpsPrecision> {
        val payload = byteArrayOf(hdopHundredths.toByte(), (hdopHundredths shr 8).toByte(), 0, 0, 0, 0)
        var checksum = 0
        payload.forEach { checksum = checksum xor (it.toInt() and 0xFF) }
        val frame = byteArrayOf('$'.code.toByte(), 'T'.code.toByte(), 'X'.code.toByte()) + payload + checksum.toByte()
        val captor = Captor()
        val protocol = LTMProtocol(captor)
        frame.forEach { protocol.process(it.toInt() and 0xFF) }
        return captor.heard
    }

    @Test
    fun ltmHdopInHundredths() {
        assertEquals(listOf(GpsPrecision.Hdop(1.35f)), ltm(135))
    }

    @Test
    fun ltmUnknownIsNothing() {
        assertEquals(emptyList<GpsPrecision>(), ltm(9999))
    }

    // --- what the tile says ---

    @Test
    fun eachFormSaysWhatItIs() {
        val was = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("±2.5 m", GpsPrecision.Metres(2.5f).text())
            assertEquals("HDOP 1.2", GpsPrecision.Hdop(1.2f).text())
            // the bound true of both firmwares' steps
            assertEquals("HDOP ≤1.5", GpsPrecision.HdopDigit(9).text())
            assertEquals("HDOP ≤5.5", GpsPrecision.HdopDigit(1).text())
            assertEquals("HDOP >5", GpsPrecision.HdopDigit(0).text())
        } finally {
            Locale.setDefault(was)
        }
    }

    private companion object {
        const val HEARTBEAT = 0
        const val GPS_RAW = 24
        const val GENERIC = 0
        const val ARDUPILOT = 3
        const val PX4 = 12
    }
}
