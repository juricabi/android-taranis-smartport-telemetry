package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRCMAVLink
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MAVLink HIGH_LATENCY2 (235): the one message a high-latency link carries.
 * Frames are built the way ArduPilot sends them — MAVLink 2, little-endian,
 * crc_extra 179 — and the v1 framing the library also defines.
 */
class HighLatency2Test {

    private class Captor : DataDecoder.Companion.DefaultDecodeListener() {
        var lat = 0.0; var lon = 0.0
        var gpsAltitude = Float.NaN
        var altitude = Float.NaN
        var heading = Float.NaN
        var gspeed = Float.NaN
        var aspeed = Float.NaN
        var throttle = -1
        var fuel = -1
        var satellites = -1
        var fix = false
        var armed = false
        var mode: DataDecoder.Companion.FlyMode? = null
        var modeReported = false

        override fun onGPSData(latitude: Double, longitude: Double) {
            lat = latitude; lon = longitude
        }
        override fun onGPSAltitudeData(altitude: Float) { gpsAltitude = altitude }
        override fun onAltitudeData(altitude: Float) { this.altitude = altitude }
        override fun onHeadingData(heading: Float) { this.heading = heading }
        override fun onGSpeedData(speed: Float) { gspeed = speed }
        override fun onAirSpeedData(speed: Float) { aspeed = speed }
        override fun onThrottleData(throttle: Int) { this.throttle = throttle }
        override fun onFuelData(fuel: Int) { this.fuel = fuel }
        override fun onGPSState(satellites: Int, gpsFix: Boolean) {
            this.satellites = satellites; fix = gpsFix
        }
        override fun onFlyModeData(
            armed: Boolean, heading: Boolean,
            firstFlyMode: DataDecoder.Companion.FlyMode?,
            secondFlyMode: DataDecoder.Companion.FlyMode?
        ) {
            this.armed = armed; mode = firstFlyMode; modeReported = true
        }
    }

    /** The 42 bytes, in wire order, with the values the assertions expect. */
    private fun payload(
        lat: Int = 451234567, lon: Int = 168765432,
        customMode: Int = 6, altitude: Int = 178,
        failureFlags: Int = 0, type: Int = 2,
        heading: Int = 135, throttle: Int = 55,
        airspeed: Int = 62, groundspeed: Int = 100,
        battery: Int = 77, custom0: Int = 0x81
    ): ByteArray {
        val b = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(123456)          // timestamp
        b.putInt(lat)
        b.putInt(lon)
        b.putShort(customMode.toShort())
        b.putShort(altitude.toShort())
        b.putShort(180.toShort()) // target altitude
        b.putShort(0)             // target distance
        b.putShort(3)             // wp_num
        b.putShort(failureFlags.toShort())
        b.put(type.toByte())
        b.put(3)                  // ArduPilotMega
        b.put(heading.toByte())
        b.put(0)                  // target heading
        b.put(throttle.toByte())
        b.put(airspeed.toByte())
        b.put(0)                  // airspeed setpoint
        b.put(groundspeed.toByte())
        b.put(0); b.put(0)        // wind
        b.put(0); b.put(0)        // eph, epv
        b.put(-128)               // no thermometer
        b.put(0)                  // climb rate, always zero from ArduPilot
        b.put(battery.toByte())
        b.put(custom0.toByte())   // base_mode: armed is the sign bit
        b.put(0); b.put(0)
        return b.array()
    }

    private fun mav2Frame(payload: ByteArray, crcExtra: Int = 179): ByteArray {
        val head = byteArrayOf(
            payload.size.toByte(), 0, 0, 7, 1, 1,
            235.toByte(), 0, 0
        )
        val crc = CRCMAVLink()
        crc.start_checksum()
        head.forEach { crc.update_checksum(it.toInt() and 0xFF) }
        payload.forEach { crc.update_checksum(it.toInt() and 0xFF) }
        crc.update_checksum(crcExtra)
        return byteArrayOf(0xFD.toByte()) + head + payload +
            byteArrayOf(crc.lsb.toByte(), crc.msb.toByte())
    }

    private fun mav1Frame(payload: ByteArray): ByteArray {
        val head = byteArrayOf(payload.size.toByte(), 7, 1, 1, 235.toByte())
        val crc = CRCMAVLink()
        crc.start_checksum()
        head.forEach { crc.update_checksum(it.toInt() and 0xFF) }
        payload.forEach { crc.update_checksum(it.toInt() and 0xFF) }
        crc.update_checksum(179)
        return byteArrayOf(0xFE.toByte()) + head + payload +
            byteArrayOf(crc.lsb.toByte(), crc.msb.toByte())
    }

    private fun feed(protocol: Protocol, bytes: ByteArray) {
        bytes.forEach { protocol.process(it.toInt() and 0xFF) }
    }

    @Test
    fun mavlink2FrameDecodes() {
        val captor = Captor()
        feed(MAVLink2Protocol(captor), mav2Frame(payload()))
        assertEquals(45.1234567, captor.lat, 1e-7)
        assertEquals(16.8765432, captor.lon, 1e-7)
        assertEquals(178f, captor.gpsAltitude, 0f)
        assertEquals(178f, captor.altitude, 0f)
        assertEquals(270f, captor.heading, 0f)          // deg/2 on the wire
        assertEquals(100 / 5f * 3.6f, captor.gspeed, 0.01f)  // m/s x5 -> km/h
        assertEquals(62 / 5f * 3.6f, captor.aspeed, 0.01f)
        assertEquals(55, captor.throttle)
        assertEquals(77, captor.fuel)
        assertTrue(captor.fix)
        assertTrue("armed is the sign bit of custom0", captor.armed)
        assertEquals(DataDecoder.Companion.FlyMode.RTH, captor.mode) // copter 6
    }

    @Test
    fun mavlink1FrameDecodes() {
        val captor = Captor()
        feed(MAVLinkProtocol(captor), mav1Frame(payload()))
        assertEquals(45.1234567, captor.lat, 1e-7)
        assertEquals(270f, captor.heading, 0f)
        assertTrue(captor.armed)
    }

    @Test
    fun truncatedMavlink2PayloadIsZeroExtended() {
        // MAVLink 2 trims trailing zeros: a disarmed, empty-battery message
        // loses its tail on the wire and must decode as though it were there.
        val full = payload(battery = 0, custom0 = 0)
        var cut = full.size
        while (cut > 1 && full[cut - 1] == 0.toByte()) cut--
        val captor = Captor()
        feed(MAVLink2Protocol(captor), mav2Frame(full.copyOf(cut)))
        assertEquals(45.1234567, captor.lat, 1e-7)
        assertFalse(captor.armed)
    }

    @Test
    fun theOldCrcTableCellRejectedEveryFrame() {
        // The table shipped with 0 at index 235; the real crc_extra is 179.
        val captor = Captor()
        try {
            feed(MAVLink2Protocol(captor), mav2Frame(payload(), crcExtra = 0))
        } catch (e: RuntimeException) {
            // the bad-CRC path logs, and android.util.Log is not on the JVM;
            // reaching it is itself the rejection this test wants to see
        }
        assertEquals("nothing decodes with the zeroed extra", 0.0, captor.lat, 0.0)
    }

    @Test
    fun missingBatteryIsNotReported() {
        val captor = Captor()
        feed(MAVLink2Protocol(captor), mav2Frame(payload(battery = -1)))
        assertEquals("minus one means no monitor, not one percent", -1, captor.fuel)
    }

    @Test
    fun disarmedWhenSignBitClear() {
        val captor = Captor()
        feed(MAVLink2Protocol(captor), mav2Frame(payload(custom0 = 0x01)))
        assertFalse(captor.armed)
        assertTrue(captor.modeReported)
    }

    @Test
    fun gpsFailureFlagClearsTheFix() {
        val captor = Captor()
        feed(MAVLink2Protocol(captor), mav2Frame(payload(failureFlags = 1)))
        assertFalse(captor.fix)
    }

    @Test
    fun planeModeComesFromThePlaneTable() {
        val captor = Captor()
        feed(MAVLink2Protocol(captor), mav2Frame(payload(customMode = 11, type = 1)))
        assertEquals(DataDecoder.Companion.FlyMode.RTH, captor.mode)   // plane RTL
    }

    @Test
    fun unknownModeIsReportedAsNothing() {
        val captor = Captor()
        feed(MAVLink2Protocol(captor), mav2Frame(payload(customMode = 999)))
        assertTrue(captor.modeReported)
        assertNull(captor.mode)
    }
}
