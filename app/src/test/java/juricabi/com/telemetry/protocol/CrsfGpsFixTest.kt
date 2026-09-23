package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The plain CRSF GPS frame carries no fix, so the fix was a guess from the
 * satellite count. Betaflight's GPS extended frame (0x06) carries the
 * receiver's own fix type, and while it arrives it answers instead.
 */
class CrsfGpsFixTest {

    private class Captor : DataDecoder.Companion.DefaultDecodeListener() {
        val fixes = ArrayList<Boolean>()
        override fun onGPSState(satellites: Int, gpsFix: Boolean) { fixes.add(gpsFix) }
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(type.toByte()) + payload
        val crc = CRC8()
        crc.reset()
        body.forEach { crc.update(it) }
        return byteArrayOf(0xC8.toByte(), (body.size + 1).toByte()) + body +
            byteArrayOf(crc.value.toByte())
    }

    private fun gps(satellites: Int): ByteArray {
        val b = ByteBuffer.allocate(15)
        b.putInt(450000000)          // latitude, 1e-7 degrees
        b.putInt(150000000)          // longitude
        b.putShort(0)                // ground speed
        b.putShort(0)                // heading
        b.putShort((1000 + 120).toShort())  // altitude, metres + 1000
        b.put(satellites.toByte())
        return frame(0x02, b.array())
    }

    private fun extended(fixType: Int): ByteArray {
        val b = ByteBuffer.allocate(20)
        b.put(fixType.toByte())      // then velocities and accuracies, all zero here
        return frame(0x06, b.array())
    }

    private fun decode(vararg frames: ByteArray): List<Boolean> {
        val captor = Captor()
        val protocol = CrsfProtocol(captor)
        frames.forEach { f -> f.forEach { protocol.process(it.toInt() and 0xFF) } }
        return captor.fixes
    }

    @Test
    fun withoutTheExtendedFrameTheCountStillGuesses() {
        assertEquals(listOf(false, true), decode(gps(5), gps(9)))
    }

    @Test
    fun aRealFixWithFewSatellitesIsAFix() {
        assertEquals(listOf(true), decode(extended(3), gps(5)))
    }

    @Test
    fun manySatellitesWithoutAFixAreNoFix() {
        // a receiver still hunting forwards where it last was
        assertEquals(listOf(false), decode(extended(1), gps(10)))
    }

    @Test
    fun aTwoDimensionalFixPlacesTheModel() {
        assertEquals(listOf(true), decode(extended(2), gps(4)))
    }
}
