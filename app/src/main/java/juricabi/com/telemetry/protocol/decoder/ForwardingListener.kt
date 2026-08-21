package juricabi.com.telemetry.protocol.decoder

import juricabi.com.telemetry.maps.Position

/**
 * A listener that hands everything on, written once.
 *
 * The relays used to be hand-copied — DataService's generation guard alone
 * was forty wrappers of the same three lines — and a hand-copied relay is
 * where a reading silently stops (the onDeviceName incident). Subclasses
 * override [relay] to guard the whole stream in one place, or individual
 * callbacks to intercept them.
 *
 * When a callback is added to DataDecoder.Listener, the compiler brings you
 * here: forward it below, and in MulticastListener beside this.
 */
open class ForwardingListener(
    @Volatile var next: DataDecoder.Listener? = null
) : DataDecoder.Listener {

    /**
     * Every callback passes through here, so one override guards them all —
     * the generation guard holds its lock across the delivery, a multicast
     * loops its ears. Each delivery captures its arguments in a small
     * lambda — a cost the old hand-copied relays did not pay, accepted for
     * the single table; the hot paths' real work (decoding, drawing)
     * dwarfs it, on a replay seek included.
     */
    protected open fun relay(deliver: (DataDecoder.Listener) -> Unit) {
        next?.let(deliver)
    }

    override fun onConnectionFailed() = relay { it.onConnectionFailed() }
    override fun onFuelData(fuel: Int) = relay { it.onFuelData(fuel) }
    override fun onConnected() = relay { it.onConnected() }
    override fun onGPSData(latitude: Double, longitude: Double) =
        relay { it.onGPSData(latitude, longitude) }
    override fun onGPSData(list: List<Position>, addToEnd: Boolean) =
        relay { it.onGPSData(list, addToEnd) }
    override fun onVBATData(voltage: Float) = relay { it.onVBATData(voltage) }
    override fun onCellVoltageData(voltage: Float) = relay { it.onCellVoltageData(voltage) }
    override fun onVBATOrCellData(voltage: Float) = relay { it.onVBATOrCellData(voltage) }
    override fun onCurrentData(current: Float) = relay { it.onCurrentData(current) }
    override fun onHeadingData(heading: Float) = relay { it.onHeadingData(heading) }
    override fun onRSSIData(rssi: Int) = relay { it.onRSSIData(rssi) }
    override fun onUpLqData(lq: Int) = relay { it.onUpLqData(lq) }
    override fun onDnLqData(lq: Int) = relay { it.onDnLqData(lq) }
    override fun onElrsModeModeData(mode: Int) = relay { it.onElrsModeModeData(mode) }
    override fun onDisconnected() = relay { it.onDisconnected() }
    override fun onGPSState(satellites: Int, gpsFix: Boolean) =
        relay { it.onGPSState(satellites, gpsFix) }
    override fun onVSpeedData(vspeed: Float) = relay { it.onVSpeedData(vspeed) }
    override fun onThrottleData(throttle: Int) = relay { it.onThrottleData(throttle) }
    override fun onAltitudeData(altitude: Float) = relay { it.onAltitudeData(altitude) }
    override fun onGPSAltitudeData(altitude: Float) = relay { it.onGPSAltitudeData(altitude) }
    override fun onDistanceData(distance: Int) = relay { it.onDistanceData(distance) }
    override fun onHomeData(latitude: Double, longitude: Double, altitudeMsl: Float) =
        relay { it.onHomeData(latitude, longitude, altitudeMsl) }
    override fun onRollData(rollAngle: Float) = relay { it.onRollData(rollAngle) }
    override fun onPitchData(pitchAngle: Float) = relay { it.onPitchData(pitchAngle) }
    override fun onGSpeedData(speed: Float) = relay { it.onGSpeedData(speed) }
    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) = relay { it.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode) }
    override fun onAirSpeedData(speed: Float) = relay { it.onAirSpeedData(speed) }
    override fun onRCChannels(rcChannels: IntArray) = relay { it.onRCChannels(rcChannels) }
    override fun onStatusText(message: String) = relay { it.onStatusText(message) }
    override fun onDNSNRData(snr: Int) = relay { it.onDNSNRData(snr) }
    override fun onUPSNRData(snr: Int) = relay { it.onUPSNRData(snr) }
    override fun onAntData(activeAntena: Int) = relay { it.onAntData(activeAntena) }
    override fun onPowerData(power: Int) = relay { it.onPowerData(power) }
    override fun onRssiDbm1Data(rssi: Int) = relay { it.onRssiDbm1Data(rssi) }
    override fun onRssiDbm2Data(rssi: Int) = relay { it.onRssiDbm2Data(rssi) }
    override fun onRssiDbmdData(rssi: Int) = relay { it.onRssiDbmdData(rssi) }
    override fun onTelemetryByte() = relay { it.onTelemetryByte() }
    override fun onSuccessDecode() = relay { it.onSuccessDecode() }
    override fun onDecoderRestart() = relay { it.onDecoderRestart() }
    override fun onProtocolDetected(protocolName: String) =
        relay { it.onProtocolDetected(protocolName) }
    override fun onDeviceName(name: String) = relay { it.onDeviceName(name) }
    override fun commit() = relay { it.commit() }
}
