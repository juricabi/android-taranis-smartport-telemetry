package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.decoder.DataDecoder
import juricabi.com.telemetry.protocol.decoder.FrskyDataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class FrskyDataDecoderTest {

    @Test
    fun arduPilotPackVoltageUsesAWholeStableCellCount() {
        val cells = ArrayList<Float>()
        val decoder = FrskyDataDecoder(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onCellVoltageData(voltage: Float) {
                cells.add(voltage)
            }
        })

        decoder.decodeData(Protocol.Companion.TelemetryData(Protocol.ARDU_BATT_1, 168))
        decoder.decodeData(Protocol.Companion.TelemetryData(Protocol.ARDU_BATT_1, 120))

        assertEquals(4.2f, cells[0], 0.001f)
        assertEquals(3.0f, cells[1], 0.001f)
    }
}
