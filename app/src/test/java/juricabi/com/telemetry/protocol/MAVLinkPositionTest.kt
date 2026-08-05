package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRCMAVLink
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MAVLinkPositionTest {

    @Test
    fun globalPositionExpiresByLiveTime() {
        var now = 1L
        val source = MavPositionSource { now }
        source.globalArrived()

        assertTrue(source.preferGlobal())
        now += 3_000_000_001L
        assertFalse(source.preferGlobal())
    }

    @Test
    fun mavLink1FallsBackToRawGpsWhenGlobalPositionStops() {
        val listener = PositionListener()
        val protocol = MAVLinkProtocol(listener)

        feed(protocol, mav1Frame(GLOBAL_POSITION, globalPosition(45.0, 16.0, 123f)))
        repeat(80) {
            feed(protocol, mav1Frame(GPS_RAW, rawGps(46.0, 17.0, 456f)))
        }

        assertEquals(46.0 to 17.0, listener.positions.last())
        assertEquals(456f, listener.gpsAltitudes.last(), 0.001f)
    }

    @Test
    fun mavLink2FallsBackToRawGpsWhenGlobalPositionStops() {
        val listener = PositionListener()
        val protocol = MAVLink2Protocol(listener)

        feed(protocol, mav2Frame(GLOBAL_POSITION, globalPosition(45.0, 16.0, 123f)))
        repeat(80) {
            feed(protocol, mav2Frame(GPS_RAW, rawGps(46.0, 17.0, 456f)))
        }

        assertEquals(46.0 to 17.0, listener.positions.last())
        assertEquals(456f, listener.gpsAltitudes.last(), 0.001f)
    }

    private fun feed(protocol: Protocol, frame: ByteArray) {
        frame.forEach { protocol.process(it.toUByte().toInt()) }
    }

    private fun globalPosition(lat: Double, lon: Double, altitude: Float): ByteArray =
        ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_000)
            .putInt((lat * 10_000_000).toInt())
            .putInt((lon * 10_000_000).toInt())
            .putInt((altitude * 1_000).toInt())
            .putInt(10_000)
            .putShort(0).putShort(0).putShort(0)
            .putShort(9_000)
            .array()

    private fun rawGps(lat: Double, lon: Double, altitude: Float): ByteArray =
        ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(1_000_000)
            .putInt((lat * 10_000_000).toInt())
            .putInt((lon * 10_000_000).toInt())
            .putInt((altitude * 1_000).toInt())
            .putShort(100).putShort(100).putShort(0).putShort(9_000)
            .put(3).put(12)
            .array()

    private fun mav1Frame(messageId: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(payload.size.toByte(), 0, 1, 1, messageId.toByte())
        val checksum = checksum(header, payload, messageId)
        return byteArrayOf(0xFE.toByte()) + header + payload + checksum
    }

    private fun mav2Frame(messageId: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            payload.size.toByte(), 0, 0, 0, 1, 1,
            messageId.toByte(), (messageId shr 8).toByte(), (messageId shr 16).toByte()
        )
        val checksum = checksum(header, payload, messageId)
        return byteArrayOf(0xFD.toByte()) + header + payload + checksum
    }

    private fun checksum(header: ByteArray, payload: ByteArray, messageId: Int): ByteArray {
        val crc = CRCMAVLink()
        header.forEach { crc.update_checksum(it.toUByte().toInt()) }
        payload.forEach { crc.update_checksum(it.toUByte().toInt()) }
        crc.finish_checksum(messageId)
        return byteArrayOf(crc.lsb.toByte(), crc.msb.toByte())
    }

    private class PositionListener : DataDecoder.Companion.DefaultDecodeListener() {
        val positions = ArrayList<Pair<Double, Double>>()
        val gpsAltitudes = ArrayList<Float>()

        override fun onGPSData(latitude: Double, longitude: Double) {
            positions.add(latitude to longitude)
        }

        override fun onGPSAltitudeData(altitude: Float) {
            gpsAltitudes.add(altitude)
        }
    }

    private companion object {
        const val GPS_RAW = 24
        const val GLOBAL_POSITION = 33
    }
}
