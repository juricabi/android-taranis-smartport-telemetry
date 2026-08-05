package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class SportProtocolTest {

    @Test
    fun protocolTest() {
        val listener = RecordingListener()
        val protocol = FrSkySportProtocol(listener)
        val bytes = requireNotNull(
            this.javaClass.classLoader?.getResourceAsStream("sport.log")
        ).use { it.readBytes() }
        require(bytes.size % 10 == 0)
        for (offset in bytes.indices step 10) {
            require(bytes[offset].toUByte().toInt() == FrSkySportProtocol.SPORT_START_BYTE)
            var checksum = 0
            // S.Port excludes the physical sensor id immediately after 0x7e
            // and folds the seven payload bytes plus this checksum to 0xff.
            for (i in 2..8) checksum += bytes[offset + i].toUByte().toInt()
            while (checksum > 0xff) {
                checksum = (checksum and 0xff) + (checksum shr 8)
            }
            bytes[offset + 9] = (0xff - checksum).toByte()
        }
        bytes.forEach { protocol.process(it.toUByte().toInt()) }

        assertEquals(listOf(1, 255), listener.fuel)
        assertEquals(
            listOf(
                0.0 to 0.0,
                12.3456 to 12.3456,
                -12.3456 to 12.3456,
                -12.3456 to -12.3456
            ),
            listener.gps
        )
        assertEquals(listOf(16.80f), listener.reportedVoltage)
        assertEquals(listOf(5.1f), listener.current)
        assertEquals(listOf(180.25f), listener.heading)
    }

    private class RecordingListener : DataDecoder.Companion.DefaultDecodeListener() {
        val fuel = ArrayList<Int>()
        val gps = ArrayList<Pair<Double, Double>>()
        val reportedVoltage = ArrayList<Float>()
        val current = ArrayList<Float>()
        val heading = ArrayList<Float>()

        override fun onFuelData(fuel: Int) {
            this.fuel.add(fuel)
        }

        override fun onGPSData(latitude: Double, longitude: Double) {
            gps.add(latitude to longitude)
        }

        override fun onVBATOrCellData(voltage: Float) {
            reportedVoltage.add(voltage)
        }

        override fun onCurrentData(current: Float) {
            this.current.add(current)
        }

        override fun onHeadingData(heading: Float) {
            this.heading.add(heading)
        }
    }
}
