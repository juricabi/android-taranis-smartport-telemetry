package juricabi.com.telemetry.protocol

/**
 * The precision a GPS_RAW_INT carries, as one telemetry value or none.
 *
 * h_acc, a MAVLink 2 extension in millimetres, means the same from every
 * firmware and wins. eph is HDOP×100 from ArduPilot and PX4, but iNav fills it
 * with its accuracy in centimetres and calls itself generic — or ArduPilot,
 * when set to — so eph is taken only from a heartbeat naming one of the two,
 * and only while h_acc is missing: ArduPilot leaves it 0 behind an NMEA
 * receiver, and MAVLink 1 has no extensions. 99.99, in either field, is how
 * iNav and ArduPilot say they do not know.
 */
internal object MavGpsPrecision {
    const val AUTOPILOT_ARDUPILOT = 3
    const val AUTOPILOT_PX4 = 12
    const val AUTOPILOT_INVALID = 8
    private const val UNKNOWN = 9999

    fun of(eph: Int, hAccMm: Long, autopilot: Int): Protocol.Companion.TelemetryData? = when {
        hAccMm > 0 && hAccMm < UNKNOWN * 10L ->
            Protocol.Companion.TelemetryData(Protocol.GPS_ACCURACY_CM, ((hAccMm + 5) / 10).toInt())
        eph in 1 until UNKNOWN &&
            (autopilot == AUTOPILOT_ARDUPILOT || autopilot == AUTOPILOT_PX4) ->
            Protocol.Companion.TelemetryData(Protocol.GPS_HDOP, eph)
        else -> null
    }
}
