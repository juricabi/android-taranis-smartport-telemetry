package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class CrsfProtocolTest {

    @Test
    fun completeGpsFrameIsDecodedWithoutFollowingBytes() {
        var position: Pair<Double, Double>? = null
        val protocol = CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onGPSData(latitude: Double, longitude: Double) {
                position = latitude to longitude
            }
        })
        val payload = ByteBuffer.allocate(15)
            .putInt(451234567)
            .putInt(161234567)
            .putShort(100)
            .putShort(9000)
            .putShort(1100)
            .put(12)
            .array()

        feed(protocol, frame(0x02, payload))

        assertEquals(45.1234567, position?.first ?: Double.NaN, 0.0000001)
        assertEquals(16.1234567, position?.second ?: Double.NaN, 0.0000001)
    }

    @Test
    fun shortVarioFrameIsAccepted() {
        var verticalSpeed = Float.NaN
        val protocol = CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onVSpeedData(vspeed: Float) {
                verticalSpeed = vspeed
            }
        })

        feed(protocol, frame(0x07, byteArrayOf(0x00, 0xc8.toByte())))

        assertEquals(2f, verticalSpeed, 0.001f)
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(type.toByte()) + payload
        val crc = CRC8()
        body.forEach { crc.update(it) }
        return byteArrayOf(0xc8.toByte(), (body.size + 1).toByte()) +
            body + crc.value.toByte()
    }

    private fun feed(protocol: CrsfProtocol, bytes: ByteArray) {
        bytes.forEach { protocol.process(it.toInt() and 0xff) }
    }
}
