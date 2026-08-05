package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRCMAVLink
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MAVLinkRssiTest {

    @Test
    fun radioStatusPreferenceExpiresByLiveTime() {
        var now = 1L
        val source = MavRssiSource { now }
        source.radioStatusArrived()

        org.junit.Assert.assertTrue(source.preferRadioStatus())
        now += 3_000_000_001L
        org.junit.Assert.assertFalse(source.preferRadioStatus())
    }

    @Test
    fun mavLink1FallsBackToRcRssiWhenRadioStatusStops() {
        val listener = RssiListener()
        val protocol = MAVLinkProtocol(listener)

        feed(protocol, mav1Frame(RADIO_STATUS, radioStatus(200)))
        repeat(80) { feed(protocol, mav1Frame(RC_CHANNELS_RAW, rcChannelsRaw(254))) }

        assertEquals(100, listener.values.last())
    }

    @Test
    fun mavLink2FallsBackToRcRssiWhenRadioStatusStops() {
        val listener = RssiListener()
        val protocol = MAVLink2Protocol(listener)

        feed(protocol, mav2Frame(RADIO_STATUS, radioStatus(200)))
        repeat(80) { feed(protocol, mav2Frame(RC_CHANNELS_RAW, rcChannelsRaw(254))) }

        assertEquals(100, listener.values.last())
    }

    private fun radioStatus(rssi: Int): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(0)
            .put(rssi.toByte())
            .put(0).put(0).put(0).put(0)
            .array()

    private fun rcChannelsRaw(rssi: Int): ByteArray =
        ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_000)
            .apply { repeat(8) { putShort(1_500) } }
            .put(0)
            .put(rssi.toByte())
            .array()

    private fun feed(protocol: Protocol, frame: ByteArray) {
        frame.forEach { protocol.process(it.toInt() and 0xFF) }
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
        header.forEach { crc.update_checksum(it.toInt() and 0xFF) }
        payload.forEach { crc.update_checksum(it.toInt() and 0xFF) }
        crc.finish_checksum(messageId)
        return byteArrayOf(crc.lsb.toByte(), crc.msb.toByte())
    }

    private class RssiListener : DataDecoder.Companion.DefaultDecodeListener() {
        val values = ArrayList<Int>()

        override fun onRSSIData(rssi: Int) {
            values.add(rssi)
        }
    }

    private companion object {
        const val RC_CHANNELS_RAW = 35
        const val RADIO_STATUS = 109
    }
}
