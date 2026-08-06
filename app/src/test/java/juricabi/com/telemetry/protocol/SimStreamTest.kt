package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simulator's own byte streams, replayed through the real parsers.
 *
 * The .log resources are written by tools/simflight.py with --dump on a fixed
 * clock, so the stream a phone receives from the simulator is byte-identical
 * to the one proved here. Regenerate them with the commands in each test if
 * the simulator's flight model changes.
 */
class SimStreamTest {

    private class Captor : DataDecoder.Companion.DefaultDecodeListener() {
        var lat = 0.0; var lon = 0.0
        var positions = 0
        var satellites = -1
        var fix = false
        var armed = false
        var mode: DataDecoder.Companion.FlyMode? = null
        var throttle = -1
        var fuel = -1
        var distance = -1
        var heading = Float.NaN
        var status = ""
        var decodes = 0

        override fun onGPSData(latitude: Double, longitude: Double) {
            lat = latitude; lon = longitude; positions++
        }
        override fun onGPSState(satellites: Int, gpsFix: Boolean) {
            this.satellites = satellites; fix = gpsFix
        }
        override fun onFlyModeData(
            armed: Boolean, heading: Boolean,
            firstFlyMode: DataDecoder.Companion.FlyMode?,
            secondFlyMode: DataDecoder.Companion.FlyMode?
        ) {
            this.armed = armed; mode = firstFlyMode
        }
        override fun onThrottleData(throttle: Int) { this.throttle = throttle }
        override fun onFuelData(fuel: Int) { this.fuel = fuel }
        override fun onDistanceData(distance: Int) { this.distance = distance }
        override fun onHeadingData(heading: Float) { this.heading = heading }
        override fun onStatusText(message: String) { status = message }
        override fun onSuccessDecode() { decodes++ }
    }

    private fun stream(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    private fun feed(protocol: Protocol, bytes: ByteArray) {
        bytes.forEach { protocol.process(it.toInt() and 0xFF) }
    }

    // tools/simflight.py --lat 45.8971 --lon 16.8421 --style eight
    //   --passthrough --dump crsf-passthrough.log --seconds 12

    @Test
    fun crsfPassthroughStreamDecodesEndToEnd() {
        val captor = Captor()
        feed(CrsfProtocol(captor), stream("crsf-passthrough.log"))
        // flown around the seeded field
        assertTrue(captor.positions > 50)
        assertEquals(45.897, captor.lat, 0.01)
        assertEquals(16.842, captor.lon, 0.01)
        // the passthrough words made it through the CRSF wrapping
        assertEquals(36, captor.throttle)                     // 38% through 63rds
        assertTrue("armed by the end of the stream", captor.armed)
        assertEquals(DataDecoder.Companion.FlyMode.ACRO, captor.mode) // plane 5
        assertTrue("real fix from 0x5002", captor.fix)
        assertEquals(14, captor.satellites)
        assertTrue("home distance from 0x5004", captor.distance >= 0)
        assertEquals("SimFlight passthrough alive", captor.status)
    }

    @Test
    fun crsfPassthroughStreamStillDetectsAsCrsf() {
        val detector = ProtocolDetector(object : ProtocolDetector.Callback {
            override fun onProtocolDetected(detectedProtocol: Protocol?) {
                detected = detectedProtocol
            }
        })
        stream("crsf-passthrough.log").forEach {
            detector.feedData(it.toInt() and 0xFF)
        }
        assertTrue(detected is CrsfProtocol)
    }

    // tools/simflight.py --lat 45.8971 --lon 16.8421 --protocol mavlink-hl
    //   --hl-period 2 --dump mavlink-hl2.log --seconds 12

    @Test
    fun mavlink2HighLatencyStreamDecodesEndToEnd() {
        val captor = Captor()
        feed(MAVLink2Protocol(captor), stream("mavlink-hl2.log"))
        assertTrue(captor.positions >= 5)
        assertEquals(45.897, captor.lat, 0.01)
        assertEquals(16.842, captor.lon, 0.01)
        assertTrue("fix reported from the failure flags", captor.fix)
        assertTrue("armed by the end of the stream", captor.armed)
        assertEquals(DataDecoder.Companion.FlyMode.LOITER, captor.mode) // copter 5
        assertEquals(38, captor.throttle)
        assertTrue("battery percentage as fuel", captor.fuel in 1..100)
    }

    @Test
    fun mavlink1HighLatencyStreamDecodesEndToEnd() {
        // tools/simflight.py ... --mavlink-version 1 --dump mavlink1-hl2.log
        val captor = Captor()
        feed(MAVLinkProtocol(captor), stream("mavlink1-hl2.log"))
        assertTrue(captor.positions >= 5)
        assertEquals(45.897, captor.lat, 0.01)
        assertTrue(captor.armed)
    }

    @Test
    fun mavlink2HighLatencyStreamDetects() {
        val detector = ProtocolDetector(object : ProtocolDetector.Callback {
            override fun onProtocolDetected(detectedProtocol: Protocol?) {
                detected = detectedProtocol
            }
        })
        stream("mavlink-hl2.log").forEach { detector.feedData(it.toInt() and 0xFF) }
        assertTrue("an HL2-only link is still recognisably MAVLink 2",
            detected is MAVLink2Protocol)
    }

    private var detected: Protocol? = null
}
