package juricabi.com.telemetry.protocol.decoder

import juricabi.com.telemetry.protocol.Protocol
import java.io.IOException
import kotlin.math.pow

class LinkTestDataDecoder(listener: Listener) : DataDecoder(listener) {

    override fun decodeData(data: Protocol.Companion.TelemetryData) {
        when (data.telemetryType) {
            Protocol.DISTANCE -> {
                listener.onDistanceData(data.data)
            }
            Protocol.RSSI -> {
                listener.onRSSIData(data.data)
                listener.onSuccessDecode()
            }
        }
    }
}