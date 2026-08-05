package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.decoder.DataDecoder
import juricabi.com.telemetry.protocol.decoder.FrskyDataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class FrskyDataDecoderTest {

    @Test
    fun precisionThreeAccelerometerUsesTheSameScaleOnEveryAxis() {
        var roll = Float.NaN
        val decoder = FrskyDataDecoder(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onRollData(rollAngle: Float) {
                roll = rollAngle
            }
        })

        // 0.5g Y and sqrt(3)/2 g Z is a 30 degree bank.
        decoder.decodeData(Protocol.Companion.TelemetryData(Protocol.DATA_ID_ACC_Y_1000, 500))
        decoder.decodeData(Protocol.Companion.TelemetryData(Protocol.DATA_ID_ACC_Z_1000, 866))

        assertEquals(30f, roll, 0.05f)
    }

    @Test
    fun sportAirspeedConvertsTenthsOfAKnotToKilometresPerHour() {
        var airspeed = Float.NaN
        val decoder = FrskyDataDecoder(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onAirSpeedData(speed: Float) {
                airspeed = speed
            }
        })

        decoder.decodeData(Protocol.Companion.TelemetryData(Protocol.ASPEED, 100))

        assertEquals(18.52f, airspeed, 0.001f)
    }

    @Test
    fun arduPilotVelocityKeepsVerticalSignAndUsesAppSpeedUnits() {
        var verticalSpeed = Float.NaN
        var groundSpeed = Float.NaN
        var reportedHeading = Float.NaN
        val decoder = FrskyDataDecoder(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onVSpeedData(vspeed: Float) {
                verticalSpeed = vspeed
            }

            override fun onGSpeedData(speed: Float) {
                groundSpeed = speed
            }

            override fun onHeadingData(heading: Float) {
                reportedHeading = heading
            }
        })

        // ArduPilot prep_number encoding: -3.4 m/s vertical, 25 m/s
        // horizontal, and 123.4 degrees yaw in passthrough frame 0x5005.
        val encoded = (34 shl 1) or (1 shl 8) or
            (51 shl 9) or (617 shl 17)
        decoder.decodeData(
            Protocol.Companion.TelemetryData(Protocol.ARDU_VEL_YAW, encoded)
        )

        assertEquals(-3.4f, verticalSpeed, 0.001f)
        assertEquals(90f, groundSpeed, 0.001f)
        assertEquals(123.4f, reportedHeading, 0.001f)
    }

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
