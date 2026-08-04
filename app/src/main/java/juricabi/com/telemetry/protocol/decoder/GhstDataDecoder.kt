package juricabi.com.telemetry.protocol.decoder

import juricabi.com.telemetry.protocol.Protocol

class GhstDataDecoder(listener: Listener) : DataDecoder(listener) {

    private var newLatitude = false
    private var newLongitude = false
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    private fun sq(v: Int): Int {
        return v * v
    }

    init {
        this.restart()
    }

    override fun restart() {
        this.newLatitude = false
        this.newLongitude = false
        this.latitude = 0.0
        this.longitude = 0.0
        this.listener.onDecoderRestart()
    }

    override fun decodeData(data: Protocol.Companion.TelemetryData) {
        var decoded = true
        when (data.telemetryType) {
            Protocol.VBAT_OR_CELL -> {
                // sent in 10mV steps
                listener.onVBATOrCellData(data.data / 100f)
            }
            Protocol.CURRENT -> {
                // sent in 10mA steps
                listener.onCurrentData(data.data / 100f)
            }
            Protocol.FUEL -> {
                listener.onFuelData(data.data)
            }
            Protocol.GPS_LATITUDE -> {
                latitude = data.data / 10000000.toDouble()
                newLatitude = true
            }
            Protocol.GPS_LONGITUDE -> {
                longitude = data.data / 10000000.toDouble()
                newLongitude = true
            }
            Protocol.GPS_SATELLITES -> {
                val satellites = data.data
                listener.onGPSState(satellites, satellites > 6)
            }
            Protocol.GPS_ALTITUDE -> {
                listener.onGPSAltitudeData(data.data.toFloat())
            }
            Protocol.ALTITUDE -> {
                // metres, already signed
                listener.onAltitudeData(data.data.toFloat())
            }
            Protocol.GSPEED -> {
                // sent in cm/s, shown in km/h
                listener.onGSpeedData(data.data * 0.036f)
            }
            Protocol.HEADING -> {
                // sent in 0.1 degree steps
                listener.onHeadingData(data.data / 10f)
            }
            Protocol.RSSI -> {
                if (data.data == 0) {
                    // zero means the module has no reading. Without this the
                    // conversion below would report a full strength signal on a
                    // link that has actually dropped.
                    listener.onRSSIData(-1)
                } else {
                    // dBm, converted to a percentage the same way the CRSF decoder does
                    var v = (100 * sq(MAX_RSSI - MIN_RSSI) - (100 * sq(MAX_RSSI - data.data))) /
                            sq(MAX_RSSI - MIN_RSSI)
                    if (data.data >= MAX_RSSI) v = 99
                    if (data.data < MIN_RSSI) v = 0

                    listener.onRSSIData(v)
                    listener.onRssiDbm1Data(data.data)
                }
            }
            Protocol.CRSF_UP_LQ -> {
                listener.onUpLqData(data.data)
            }
            Protocol.UP_SNR -> {
                listener.onUPSNRData(data.data)
            }
            Protocol.ELRS_RF_MODE -> {
                listener.onElrsModeModeData(data.data)
            }
            Protocol.POWER -> {
                listener.onPowerData(data.data)
            }
            Protocol.DISTANCE -> {
                listener.onDistanceData(data.data)
            }
            else -> {
                decoded = false
            }
        }

        if (newLatitude && newLongitude) {
            if (latitude != 0.0 && longitude != 0.0) {
                listener.onGPSData(latitude, longitude)
            }
            newLatitude = false
            newLongitude = false
        }

        if (decoded) {
            listener.onSuccessDecode()
        }
    }
}
