package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.Protocol.Companion.TelemetryData
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.*
import org.junit.Test

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
}
