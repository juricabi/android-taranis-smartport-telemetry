package juricabi.com.telemetry.protocol.decoder

import juricabi.com.telemetry.protocol.Protocol
import kotlin.math.ceil
import kotlin.math.pow

/**
 * ArduPilot passthrough words, decoded once for every link that carries them.
 *
 * The bit layouts of 0x5001-0x5006 are the same whether the word arrived in a
 * FrSky S.Port frame or inside a CRSF 0x80 frame — only the framing differs.
 * The arithmetic here is the S.Port decoder's, moved rather than rewritten, so
 * its tests answer for both roads.
 *
 * The Boolean switches exist for CRSF, whose native frames already carry some
 * of these values at a higher rate: the caller says which halves of a word are
 * wanted, and an unwanted half is simply not published rather than fighting
 * the native stream.
 */
class ArduPassthroughDecoder(private val listener: DataDecoder.Listener) {

    /**
     * Kept as voltage falls, never lowered: turning a depleted 4S into a
     * healthy-looking 3S is unsafe.
     */
    private var inferredBatteryCells = 0

    fun reset() {
        inferredBatteryCells = 0
    }

    /** Bits [pos..pos+num-1] of [number], 1-based, as the Lua scripts count. */
    private fun bitExtracted(number: Int, num: Int, pos: Int): Int {
        return (1 shl num) - 1 and (number shr pos - 1)
    }

    /** Which telemetry type a passthrough appid carries, or null for none. */
    companion object {
        fun typeFor(appId: Int): Int? = when (appId) {
            0x5001 -> Protocol.ARDU_AP_STATUS
            0x5002 -> Protocol.ARDU_GPS_STATUS
            0x5003 -> Protocol.ARDU_BATT_1
            0x5004 -> Protocol.ARDU_HOME
            0x5005 -> Protocol.ARDU_VEL_YAW
            0x5006 -> Protocol.ARDU_ATTITUDE
            else -> null
        }
    }

    /** 0x5001: flight mode, armed, and the throttle percentage. */
    fun apStatus(word: Int, withMode: Boolean) {
        // Throttle regardless of the mode switch: nothing native carries it.
        // Six bits of magnitude scaled so 63 is full stick; the sign marks a
        // reversed throttle and the readout shows how much, not which way.
        val throttle = bitExtracted(word, 6, 20) * 100 / 63
        listener.onThrottleData(throttle)
        if (!withMode) return
        val arduFlightMode = bitExtracted(word, 5, 1)
        val arduArmed: Boolean
        val firstFlightMode: DataDecoder.Companion.FlyMode
        arduArmed = bitExtracted(word, 1, 9) == 1
        if (arduFlightMode == 1) {
            firstFlightMode = DataDecoder.Companion.FlyMode.MANUAL
        } else if (arduFlightMode == 2) {
            firstFlightMode = DataDecoder.Companion.FlyMode.CIRCLE
        } else if (arduFlightMode == 3) {
            firstFlightMode = DataDecoder.Companion.FlyMode.STABILIZE
        } else if (arduFlightMode == 4) {
            firstFlightMode = DataDecoder.Companion.FlyMode.TRAINING
        } else if (arduFlightMode == 5) {
            firstFlightMode = DataDecoder.Companion.FlyMode.ACRO
        } else if (arduFlightMode == 6) {
            firstFlightMode = DataDecoder.Companion.FlyMode.FBWA
        } else if (arduFlightMode == 7) {
            firstFlightMode = DataDecoder.Companion.FlyMode.FBWB
        } else if (arduFlightMode == 8) {
            firstFlightMode = DataDecoder.Companion.FlyMode.CRUISE
        } else if (arduFlightMode == 9) {
            firstFlightMode = DataDecoder.Companion.FlyMode.AUTOTUNE
        } else if (arduFlightMode == 11) {
            firstFlightMode = DataDecoder.Companion.FlyMode.AUTONOMOUS
        } else if (arduFlightMode == 12) {
            firstFlightMode = DataDecoder.Companion.FlyMode.RTH
        } else if (arduFlightMode == 13) {
            firstFlightMode = DataDecoder.Companion.FlyMode.LOITER
        } else if (arduFlightMode == 14) {
            firstFlightMode = DataDecoder.Companion.FlyMode.TAKEOFF
        } else if (arduFlightMode == 15) {
            firstFlightMode = DataDecoder.Companion.FlyMode.AVOID_ADSB
        } else if (arduFlightMode == 16) {
            firstFlightMode = DataDecoder.Companion.FlyMode.GUIDED
        } else if (arduFlightMode == 17) {
            firstFlightMode = DataDecoder.Companion.FlyMode.INITIALISING
        } else if (arduFlightMode == 18) {
            firstFlightMode = DataDecoder.Companion.FlyMode.QSTABILIZE
        } else if (arduFlightMode == 19) {
            firstFlightMode = DataDecoder.Companion.FlyMode.QHOVER
        } else if (arduFlightMode == 20) {
            firstFlightMode = DataDecoder.Companion.FlyMode.QLOITER
        } else if (arduFlightMode == 21) {
            firstFlightMode = DataDecoder.Companion.FlyMode.QLAND
        } else if (arduFlightMode == 22) {
            firstFlightMode = DataDecoder.Companion.FlyMode.QRTL
        } else if (arduFlightMode == 23) {
            firstFlightMode = DataDecoder.Companion.FlyMode.QAUTOTUNE
        } else if (arduFlightMode == 24) {
            firstFlightMode = DataDecoder.Companion.FlyMode.QACRO
        } else {
            firstFlightMode = DataDecoder.Companion.FlyMode.FBWA
        }
        listener.onFlyModeData(arduArmed, false, firstFlightMode, null)
    }

    /** 0x5002: satellites, fix, and the GPS altitude above the sea. */
    fun gpsStatus(word: Int, withAltitude: Boolean) {
        val satellites = bitExtracted(word, 4, 1)
        val gpsStatus = bitExtracted(word, 2, 5)
        val isFix = gpsStatus >= 3
        listener.onGPSState(satellites, isFix)
        if (!withAltitude) return
        val gpsAlt: Double =
            bitExtracted(word, 7, 25) * 10.0.pow(bitExtracted(word, 2, 23).toDouble()) / 10f
        if (bitExtracted(word, 1, 32) == 1) listener.onGPSAltitudeData(gpsAlt.toFloat() * -1)
        else listener.onGPSAltitudeData(gpsAlt.toFloat())
    }

    /** 0x5003: pack voltage, a cell estimate from it, current and consumed mAh. */
    fun battery(word: Int) {
        val fr_bat1_amps =
            bitExtracted(word, 7, 11) * 10.0.pow(bitExtracted(word, 1, 10).toDouble()) / 10f
        val fr_bat1_mAh = bitExtracted(word, 15, 18)
        val fr_bat1_volts = bitExtracted(word, 9, 1).toFloat() / 10f
        val inferred = ceil((fr_bat1_volts / 4.4f).toDouble()).toInt()
            .coerceAtLeast(1)
        if (inferred > inferredBatteryCells) inferredBatteryCells = inferred
        val cellVoltage = fr_bat1_volts / inferredBatteryCells.coerceAtLeast(1)
        listener.onCellVoltageData(cellVoltage)
        listener.onVBATData(fr_bat1_volts)
        listener.onCurrentData(fr_bat1_amps.toFloat())
        listener.onFuelData(fr_bat1_mAh)
    }

    /** 0x5004: distance to home, and altitude above it. */
    fun home(word: Int, withAltitude: Boolean) {
        val fr_home_dist =
            bitExtracted(word, 10, 3) * 10.0.pow(bitExtracted(word, 2, 1)).toDouble()
        listener.onDistanceData(fr_home_dist.toInt())
        if (!withAltitude) return
        val alt_frome_home =
            bitExtracted(word, 10, 15) * 10.0.pow(bitExtracted(word, 2, 13)).toDouble() * 0.1
        if (bitExtracted(word, 1, 25) == 1) listener.onAltitudeData(alt_frome_home.toFloat() * -1)
        else listener.onAltitudeData(alt_frome_home.toFloat())
    }

    /** 0x5005: vertical speed, ground speed and yaw. */
    fun velocityAndYaw(word: Int, withVSpeed: Boolean, withGSpeed: Boolean, withYaw: Boolean) {
        if (withVSpeed) {
            var verticalSpeed = bitExtracted(word, 7, 2) *
                10.0.pow(bitExtracted(word, 1, 1).toDouble()) / 10f
            if (bitExtracted(word, 1, 9) == 1) {
                verticalSpeed = -verticalSpeed
            }
            listener.onVSpeedData(verticalSpeed.toFloat())
        }
        if (withGSpeed) {
            val groundSpeed = bitExtracted(word, 7, 11) *
                10.0.pow(bitExtracted(word, 1, 10).toDouble()) / 10f * 3.6f
            listener.onGSpeedData(groundSpeed.toFloat())
        }
        if (withYaw) {
            val heading = bitExtracted(word, 11, 18) * 0.2f
            listener.onHeadingData(heading)
        }
    }

    /** 0x5006: roll and pitch. Returns whether they were published. */
    fun attitude(word: Int, wanted: Boolean): Boolean {
        if (!wanted) return false
        val Roll = bitExtracted(word, 11, 1)
        val Pitch = bitExtracted(word, 10, 12)
        listener.onRollData((Roll - 900) * 0.2f)
        listener.onPitchData((Pitch - 450) * 0.2f)
        return true
    }
}
