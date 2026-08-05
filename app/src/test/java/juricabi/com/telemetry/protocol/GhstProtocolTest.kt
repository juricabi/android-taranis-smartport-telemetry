package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class GhstProtocolTest {

    @Test
    fun completeFrameIsDecodedWithoutWaitingForTheNextFrame() {
        var linkQuality = -1
        val protocol = GhstProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onUpLqData(lq: Int) {
                linkQuality = lq
            }
        })

        feed(protocol, frame(0x21, byteArrayOf(
            70, 73, 4,             // RSSI, LQ, SNR
            0, 100,                // power, big endian
            0, 0, 0, 0,           // frame rate and latency
            2                      // RF profile
        )))

        assertEquals(73, linkQuality)
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        require(payload.size == 10)
        val body = byteArrayOf(type.toByte()) + payload
        val crc = CRC8()
        body.forEach { crc.update(it) }
        return byteArrayOf(0x80.toByte(), 12) + body + crc.value.toByte()
    }

    private fun feed(protocol: GhstProtocol, bytes: ByteArray) {
        bytes.forEach { protocol.process(it.toInt() and 0xFF) }
    }
}
