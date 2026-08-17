package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.Protocol.Companion.TelemetryData
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LTMProtocolTest {

    @Test
    fun testLTMProtocol() {

        val expectedTelemetry = arrayListOf(
            TelemetryData(Protocol.GPS, 0, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
            TelemetryData(Protocol.GPS, 0, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)))
        val decodedTelemetry = ArrayList<TelemetryData>()
        val ltmProtocol = LTMProtocol(object : DataDecoder(Companion.DefaultDecodeListener()) {
            override fun decodeData(data: TelemetryData) {
                decodedTelemetry.add(data)
            }
        })

        val bytes = requireNotNull(
            this.javaClass.classLoader?.getResourceAsStream("ltm.log")
        ).use { it.readBytes() }
        require(bytes.size % 18 == 0)
        for (offset in bytes.indices step 18) {
            var checksum: Byte = 0
            for (i in 3..16) {
                checksum = (checksum.toInt() xor bytes[offset + i].toInt()).toByte()
            }
            bytes[offset + 17] = checksum
        }
        bytes.forEach { ltmProtocol.process(it.toUByte().toInt()) }

        assertEquals(expectedTelemetry.size, decodedTelemetry.size)
        assertArrayEquals(expectedTelemetry.toArray(), decodedTelemetry.toArray())
    }

    @Test
    fun theOriginFrameSaysWhereHomeStandsAndHowHigh() {
        // the one protocol that tells the zero outright: home's own
        // altitude is what turns LTM's relative heights into absolute ones
        var homeLat = Double.NaN
        var homeLon = Double.NaN
        var homeAlt = Float.NaN
        val protocol = LTMProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onHomeData(latitude: Double, longitude: Double, altitudeMsl: Float) {
                homeLat = latitude
                homeLon = longitude
                homeAlt = altitudeMsl
            }
        })
        val payload = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(448_000_000)
            .putInt(150_000_000)
            .putInt(159_400) // home's altitude, centimetres: 1594 m
            .put(1)          // OSD on
            .put(1)          // fix
            .array()

        ltmFrame('O', payload).forEach { protocol.process(it.toInt() and 0xFF) }

        assertEquals(44.8, homeLat, 1e-9)
        assertEquals(15.0, homeLon, 1e-9)
        assertEquals(1594f, homeAlt, 1e-3f)
    }

    @Test
    fun gpsSpeedAndSatelliteCountAreUnsigned() {
        var reportedSpeed = Float.NaN
        var reportedSatellites = -1
        var hasFix = false
        val protocol = LTMProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onGSpeedData(speed: Float) {
                reportedSpeed = speed
            }

            override fun onGPSState(satellites: Int, gpsFix: Boolean) {
                reportedSatellites = satellites
                hasFix = gpsFix
            }
        })
        val payload = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(450_000_000)
            .putInt(160_000_000)
            .put(200.toByte())
            .putInt(12_345)
            .put(((40 shl 2) or 1).toByte())
            .array()

        ltmFrame('G', payload).forEach { protocol.process(it.toInt() and 0xFF) }

        assertEquals(720f, reportedSpeed, 0f)
        assertEquals(40, reportedSatellites)
        assertTrue(hasFix)
    }

    private fun ltmFrame(type: Char, payload: ByteArray): ByteArray {
        var checksum = 0
        payload.forEach { checksum = checksum xor (it.toInt() and 0xFF) }
        return byteArrayOf('$'.code.toByte(), 'T'.code.toByte(), type.code.toByte()) +
            payload + checksum.toByte()
    }
}
