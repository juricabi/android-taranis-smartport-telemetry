package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ArduPilot passthrough over CRSF: frame types 0x80 and 0x7F, subtypes F0
 * (one appid+word), F2 (several) and F1 (status text). The words are
 * little-endian packed structs inside an otherwise big-endian protocol.
 */
class CrsfPassthroughTest {

    private class Captor : DataDecoder.Companion.DefaultDecodeListener() {
        var vbat = Float.NaN
        var current = Float.NaN
        var fuel = -1
        var satellites = -1
        var fix = false
        var gpsAltitude = Float.NaN
        var distance = -1
        var throttle = -1
        var armed = false
        var mode: DataDecoder.Companion.FlyMode? = null
        var roll = Float.NaN
        var pitch = Float.NaN
        var heading = Float.NaN
        var vspeed = Float.NaN
        var status = ""
        val vbatValues = ArrayList<Float>()

        override fun onVBATData(voltage: Float) { vbat = voltage; vbatValues.add(voltage) }
        override fun onCurrentData(current: Float) { this.current = current }
        override fun onFuelData(fuel: Int) { this.fuel = fuel }
        override fun onGPSState(satellites: Int, gpsFix: Boolean) {
            this.satellites = satellites; fix = gpsFix
        }
        override fun onGPSAltitudeData(altitude: Float) { gpsAltitude = altitude }
        override fun onDistanceData(distance: Int) { this.distance = distance }
        override fun onThrottleData(throttle: Int) { this.throttle = throttle }
        override fun onFlyModeData(
            armed: Boolean, heading: Boolean,
            firstFlyMode: DataDecoder.Companion.FlyMode?,
            secondFlyMode: DataDecoder.Companion.FlyMode?
        ) {
            this.armed = armed; mode = firstFlyMode
        }
        override fun onRollData(rollAngle: Float) { roll = rollAngle }
        override fun onPitchData(pitchAngle: Float) { pitch = pitchAngle }
        override fun onHeadingData(heading: Float) { this.heading = heading }
        override fun onVSpeedData(vspeed: Float) { this.vspeed = vspeed }
        override fun onStatusText(message: String) { status = message }
        val namedAs = ArrayList<String>()
        override fun onProtocolDetected(protocolName: String) { namedAs.add(protocolName) }
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(type.toByte()) + payload
        val crc = CRC8()
        crc.reset()
        body.forEach { crc.update(it) }
        return byteArrayOf(0xC8.toByte(), (body.size + 1).toByte()) + body +
            byteArrayOf(crc.value.toByte())
    }

    private fun feed(protocol: Protocol, bytes: ByteArray) {
        bytes.forEach { protocol.process(it.toInt() and 0xFF) }
    }

    private fun single(appId: Int, word: Long): ByteArray {
        val b = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0xF0.toByte())
        b.putShort(appId.toShort())
        b.putInt(word.toInt())
        return b.array()
    }

    /** 0x5003: 25.2 V, 12.3 A, 987 mAh. Voltage 252 = asymmetric LE proof. */
    private val batteryWord = (252L) or (123L shl 10) or (987L shl 17)

    @Test
    fun aPassthroughWordNamesTheLinkThatCarriesIt() {
        // The link is CRSF either way, and detection says so — but which of
        // the two it is decides what half the readings mean, so the first
        // passthrough word says the longer name. The readout is the only
        // place a person sees it, and nobody here can fly an ArduPilot.
        val captor = Captor()
        feed(CrsfProtocol(captor), frame(0x80, single(0x5003, batteryWord)))
        assertEquals(listOf(ProtocolFactory.CRSF_WITH_PASSTHROUGH), captor.namedAs)
    }

    @Test
    fun theNameIsSaidOnceHoweverManyWordsArrive() {
        val captor = Captor()
        val protocol = CrsfProtocol(captor)
        repeat(5) { feed(protocol, frame(0x80, single(0x5003, batteryWord))) }
        assertEquals(1, captor.namedAs.size)
    }

    @Test
    fun plainCrsfNeverClaimsPassthrough() {
        // a native battery frame, no passthrough word anywhere in it
        val captor = Captor()
        val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putShort(252).putShort(123).put(byteArrayOf(0, 3, 219.toByte())).put(50.toByte())
        feed(CrsfProtocol(captor), frame(0x08, payload.array()))
        assertTrue("plain CRSF named itself " + captor.namedAs, captor.namedAs.isEmpty())
    }

    @Test
    fun singlePacketAt0x80Decodes() {
        val captor = Captor()
        feed(CrsfProtocol(captor), frame(0x80, single(0x5003, batteryWord)))
        assertEquals(25.2f, captor.vbat, 0.01f)
        assertEquals(12.3f, captor.current, 0.01f)
        assertEquals(987, captor.fuel)
    }

    @Test
    fun legacy0x7FIsTheSameFrame() {
        val captor = Captor()
        feed(CrsfProtocol(captor), frame(0x7F, single(0x5003, batteryWord)))
        assertEquals(25.2f, captor.vbat, 0.01f)
    }

    @Test
    fun multiPacketCarriesSeveralWords() {
        // 0x5001 ap status: mode word 12 (plane RTL plus one), armed, 38%
        val status = (12L) or (1L shl 8) or ((38L * 63 / 100) shl 19)
        // 0x5006 attitude: roll +20, ArduPilot pitch -10 (nose-up-positive) — the
        // app flips pitch to its nose-down-positive convention, so +10 comes out
        val attitude = ((20L * 5 + 900)) or ((-10L * 5 + 450) shl 11)
        val b = ByteBuffer.allocate(2 + 6 * 3).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0xF2.toByte()).put(3)
        b.putShort(0x5001).putInt(status.toInt())
        b.putShort(0x5003).putInt(batteryWord.toInt())
        b.putShort(0x5006).putInt(attitude.toInt())
        val captor = Captor()
        feed(CrsfProtocol(captor), frame(0x80, b.array()))
        assertTrue(captor.armed)
        assertEquals(DataDecoder.Companion.FlyMode.RTH, captor.mode)
        assertEquals(38 * 63 / 100 * 100 / 63, captor.throttle)
        assertEquals(25.2f, captor.vbat, 0.01f)
        assertEquals(20f, captor.roll, 0.01f)
        assertEquals(10f, captor.pitch, 0.01f)
    }

    @Test
    fun statusTextArrivesWhole() {
        val text = "Battery 1 low".toByteArray()
        val payload = byteArrayOf(0xF1.toByte(), 4) + text + byteArrayOf(0)
        val captor = Captor()
        feed(CrsfProtocol(captor), frame(0x80, payload))
        assertEquals("Battery 1 low", captor.status)
    }

    @Test
    fun trailingBytesAreIgnoredNotRejected() {
        // the spec allows frames longer than the subtype needs
        val padded = single(0x5003, batteryWord) + byteArrayOf(0, 0, 0)
        val captor = Captor()
        feed(CrsfProtocol(captor), frame(0x80, padded))
        assertEquals(25.2f, captor.vbat, 0.01f)
    }

    @Test
    fun gpsStatusBeatsTheSatelliteGuess() {
        // The native frame guesses a fix from the satellite count; the
        // passthrough word carries the receiver's own answer. Two satellites
        // with a 3D fix is a thing a cold morning produces, and the guess
        // would call it no fix.
        // altitude 15 m = 150 dm: mantissa 15, exponent 1
        val word = (2L) or (3L shl 4) or (1L shl 22) or (15L shl 24)
        val captor = Captor()
        val protocol = CrsfProtocol(captor)
        feed(protocol, frame(0x80, single(0x5002, word)))
        assertEquals(2, captor.satellites)
        assertTrue(captor.fix)
        assertEquals(15f, captor.gpsAltitude, 0.01f)
    }

    @Test
    fun nativeBatterySuppressesThePassthroughCopy() {
        val captor = Captor()
        val protocol = CrsfProtocol(captor)
        // native battery frame: 16.8 V — the CRSF 0x08 frame layout
        val native = ByteBuffer.allocate(8)
        native.putShort(168)
        native.putShort(50)
        native.put(0); native.put(0); native.put(10)
        native.put(90)
        feed(protocol, frame(0x08, native.array()))
        // the passthrough copy of the same value must now hold its tongue
        feed(protocol, frame(0x80, single(0x5003, batteryWord)))
        assertTrue("only the native frame reported voltage",
            captor.vbatValues.isEmpty())   // native goes through onVBATOrCellData
        assertEquals("fuel came from the native frame, not the passthrough copy",
            10, captor.fuel)
    }

    @Test
    fun passthroughAloneReportsEverything() {
        // With no native competition — the S.Port-over-CRSF case the whole
        // feature exists for — every half of every word is published.
        val captor = Captor()
        val protocol = CrsfProtocol(captor)
        // 0x5005: vspeed -2.5 m/s (25 dm/s), hspeed 15 m/s (150 dm/s: mantissa
        // 15 exponent 1), yaw 234 deg in 0.2 deg steps
        val velYaw = (25L shl 1) or (1L shl 8) or (1L shl 9) or (15L shl 10) or
            ((234L * 5) shl 17)
        feed(protocol, frame(0x80, single(0x5005, velYaw)))
        assertEquals(-2.5f, captor.vspeed, 0.01f)
        assertEquals(234f, captor.heading, 0.1f)
        // 0x5004: home 1250 m away (mantissa 125 exponent 1), 85 m above it
        val home = (1L) or (125L shl 2) or (850L shl 14)
        feed(protocol, frame(0x80, single(0x5004, home)))
        assertEquals(1250, captor.distance)
    }
}
