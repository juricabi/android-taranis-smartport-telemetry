package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRCMAVLink
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MAVLinkBatteryTest {

    @Test
    fun mavLink1SupportsBatteryVoltageAboveSignedShortRange() {
        val listener = BatteryListener()
        val protocol = MAVLinkProtocol(listener)

        feed(protocol, mav1Frame(SYS_STATUS, systemStatus(50_400)))

        assertEquals(50.4f, listener.voltages.last(), 0.001f)
    }

    @Test
    fun mavLink2SupportsBatteryVoltageAboveSignedShortRange() {
        val listener = BatteryListener()
        val protocol = MAVLink2Protocol(listener)

        feed(protocol, mav2Frame(SYS_STATUS, systemStatus(50_400)))

        assertEquals(50.4f, listener.voltages.last(), 0.001f)
    }

    @Test
    fun unavailableBatteryVoltageIsNotDisplayedAsAReading() {
        val listener = BatteryListener()
        val protocol = MAVLink2Protocol(listener)

        feed(protocol, mav2Frame(SYS_STATUS, systemStatus(0xffff)))

        assertEquals(emptyList<Float>(), listener.voltages)
    }

    @Test
    fun signedFrameCannotDesynchroniseTheFollowingFrame() {
        val listener = BatteryListener()
        val protocol = MAVLink2Protocol(listener)

        val signature = byteArrayOf(0xfd.toByte()) + ByteArray(12)
        feed(protocol, mav2Frame(SYS_STATUS, systemStatus(12_000), incompatibility = 1) + signature)
        feed(protocol, mav2Frame(SYS_STATUS, systemStatus(13_000)))

        assertEquals(listOf(12f, 13f), listener.voltages)
    }

    private fun systemStatus(voltageMillivolts: Int): ByteArray =
        ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(0).putInt(0)
            .putShort(0)
            .putShort(voltageMillivolts.toShort())
            .putShort(100)
            .putShort(0).putShort(0)
            .putShort(0).putShort(0).putShort(0).putShort(0)
            .put(80)
            .array()

    private fun feed(protocol: Protocol, frame: ByteArray) {
        frame.forEach { protocol.process(it.toInt() and 0xff) }
    }

    private fun mav1Frame(messageId: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(payload.size.toByte(), 0, 1, 1, messageId.toByte())
        return byteArrayOf(0xfe.toByte()) + header + payload + checksum(header, payload, messageId)
    }

    private fun mav2Frame(
        messageId: Int,
        payload: ByteArray,
        incompatibility: Byte = 0
    ): ByteArray {
        val header = byteArrayOf(
            payload.size.toByte(), incompatibility, 0, 0, 1, 1,
            messageId.toByte(), (messageId shr 8).toByte(), (messageId shr 16).toByte()
        )
        return byteArrayOf(0xfd.toByte()) + header + payload + checksum(header, payload, messageId)
    }

    private fun checksum(header: ByteArray, payload: ByteArray, messageId: Int): ByteArray {
        val crc = CRCMAVLink()
        header.forEach { crc.update_checksum(it.toInt() and 0xff) }
        payload.forEach { crc.update_checksum(it.toInt() and 0xff) }
        crc.finish_checksum(messageId)
        return byteArrayOf(crc.lsb.toByte(), crc.msb.toByte())
    }

    private class BatteryListener : DataDecoder.Companion.DefaultDecodeListener() {
        val voltages = ArrayList<Float>()

        override fun onVBATData(voltage: Float) {
            voltages.add(voltage)
        }
    }

    private companion object {
        const val SYS_STATUS = 1
    }
}
