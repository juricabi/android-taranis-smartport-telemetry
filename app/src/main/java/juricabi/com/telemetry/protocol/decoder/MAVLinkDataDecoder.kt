package juricabi.com.telemetry.protocol.decoder

import android.util.Log
import juricabi.com.telemetry.protocol.Protocol
import java.nio.ByteBuffer
import java.nio.ByteOrder


class MAVLinkDataDecoder(listener: Listener) : DataDecoder(listener) {

    private var newLatitude = false
    private var newLongitude = false
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var fix = false
    private var satellites = 0
    private var rcChannels = IntArray(8) {1500};

    private var expectChankId = -1;
    private var expectImageSize = 0;
    private var gotImageSize = 0;
    private var expectChunkSize = 0;
    private lateinit var image: ByteArray;
    private var imagesLost = 0;
    private var imagesReceived = 0;

    companion object {
        private const val MAV_MODE_FLAG_STABILIZE_ENABLED = 16
        private const val MAV_MODE_FLAG_GUIDED_ENABLED = 8
        public const val MAV_MODE_FLAG_SAFETY_ARMED = 128
        private const val MAV_MODE_FLAG_CUSTOM_MODE_ENABLED = 1

        private const val MAV_TYPE_FIXED_WING = 1
        private const val MAV_TYPE_GROUND_ROVER = 10
        private const val MAV_TYPE_SURFACE_BOAT = 11

        private const val PLANE_MODE_MANUAL = 0
        private const val PLANE_MODE_CIRCLE = 1
        private const val PLANE_MODE_STABILIZE = 2
        private const val PLANE_MODE_TRAINING = 311
        private const val PLANE_MODE_ACRO = 4
        private const val PLANE_MODE_FLY_BY_WIRE_A = 5
        private const val PLANE_MODE_FLY_BY_WIRE_B = 6
        private const val PLANE_MODE_CRUISE = 7
        private const val PLANE_MODE_AUTOTUNE = 8
        private const val PLANE_MODE_AUTO = 10
        private const val PLANE_MODE_RTL = 11
        private const val PLANE_MODE_LOITER = 12
        private const val PLANE_MODE_TAKEOFF = 13
        private const val PLANE_MODE_AVOID_ADSB = 14
        private const val PLANE_MODE_GUIDED = 15
        private const val PLANE_MODE_INITIALIZING = 16
        private const val PLANE_MODE_QSTABILIZE = 17
        private const val PLANE_MODE_QHOVER = 18
        private const val PLANE_MODE_QLOITER = 19
        private const val PLANE_MODE_QLAND = 20
        private const val PLANE_MODE_QRTL = 21
        private const val PLANE_MODE_QAUTOTUNE = 22
        private const val PLANE_MODE_ENUM_END = 23

        private const val COPTER_MODE_STABILIZE = 0
        private const val COPTER_MODE_ACRO = 1
        private const val COPTER_MODE_ALT_HOLD = 2
        private const val COPTER_MODE_AUTO = 3
        private const val COPTER_MODE_GUIDED = 4
        private const val COPTER_MODE_LOITER = 5
        private const val COPTER_MODE_RTL = 6
        private const val COPTER_MODE_CIRCLE = 7
        private const val COPTER_MODE_LAND = 9
        private const val COPTER_MODE_DRIFT = 11
        private const val COPTER_MODE_SPORT = 13
        private const val COPTER_MODE_FLIP = 14
        private const val COPTER_MODE_AUTOTUNE = 15
        private const val COPTER_MODE_POSHOLD = 16
        private const val COPTER_MODE_BRAKE = 17
        private const val COPTER_MODE_THROW = 18
        private const val COPTER_MODE_AVOID_ADSB = 19
        private const val COPTER_MODE_GUIDED_NOGPS = 20
        private const val COPTER_MODE_SMART_RTL = 21
        private const val COPTER_MODE_ENUM_END = 22

        private const val MAV_STATE_CRITICAL = 5
    }

    init {
        this.restart()
    }

    /**
     * The vehicle's own mode number as a [FlyMode], keyed by what it is.
     *
     * The same tables serve HEARTBEAT and HIGH_LATENCY2: ArduPilot puts the
     * identical number in both, merely truncated to sixteen bits in the
     * second, and mode numbers are all small.
     * INAV's mapping: https://github.com/iNavFlight/inav/blob/2.6.0/src/main/telemetry/mavlink.c
     */
    private fun customModeToFlyMode(
        customMode: Int, aircraftType: Int, isFailsafe: Boolean
    ): DataDecoder.Companion.FlyMode? {
        if ((aircraftType == MAV_TYPE_FIXED_WING) ||
            (aircraftType == MAV_TYPE_GROUND_ROVER) ||
            (aircraftType == MAV_TYPE_SURFACE_BOAT)
        ) {
            return when (customMode) {
                PLANE_MODE_MANUAL -> DataDecoder.Companion.FlyMode.MANUAL
                PLANE_MODE_ACRO -> DataDecoder.Companion.FlyMode.ACRO
                PLANE_MODE_FLY_BY_WIRE_A -> DataDecoder.Companion.FlyMode.ANGLE
                PLANE_MODE_STABILIZE -> DataDecoder.Companion.FlyMode.HORIZON
                PLANE_MODE_FLY_BY_WIRE_B -> DataDecoder.Companion.FlyMode.ALTHOLD
                PLANE_MODE_LOITER -> DataDecoder.Companion.FlyMode.LOITER
                PLANE_MODE_RTL -> DataDecoder.Companion.FlyMode.RTH
                //Can not decode Waypoint or RTH after mission - use Mission. Can not decode Landing or Mission on failsafe - show nothing.
                PLANE_MODE_AUTO -> if (isFailsafe) null else DataDecoder.Companion.FlyMode.MISSION
                PLANE_MODE_CRUISE -> DataDecoder.Companion.FlyMode.CRUISE  //can not decode Cruise or Cruise3D, not enough data
                PLANE_MODE_TAKEOFF -> DataDecoder.Companion.FlyMode.TAKEOFF
                else -> null
            }
        }
        return when (customMode) {
            COPTER_MODE_ACRO -> DataDecoder.Companion.FlyMode.ACRO
            COPTER_MODE_STABILIZE -> DataDecoder.Companion.FlyMode.STABILIZE  //can not decode Angle or Horizon, not enough data
            COPTER_MODE_ALT_HOLD -> DataDecoder.Companion.FlyMode.ALTHOLD
            COPTER_MODE_POSHOLD -> DataDecoder.Companion.FlyMode.HOLD
            COPTER_MODE_GUIDED -> DataDecoder.Companion.FlyMode.GUIDED
            // was never in the table, and it is the commonest copter mode
            // there is — every LOITER flight showed no mode at all
            COPTER_MODE_LOITER -> DataDecoder.Companion.FlyMode.LOITER
            COPTER_MODE_RTL -> DataDecoder.Companion.FlyMode.RTH
            COPTER_MODE_AUTO -> if (isFailsafe) null else DataDecoder.Companion.FlyMode.MISSION
            COPTER_MODE_THROW -> DataDecoder.Companion.FlyMode.TAKEOFF
            COPTER_MODE_LAND -> DataDecoder.Companion.FlyMode.LANDING
            else -> null
        }
    }

    override fun restart() {
        this.newLatitude = false
        this.newLongitude = false
        this.latitude = 0.0
        this.longitude = 0.0
        this.fix = false
        this.satellites = 0
        this.rcChannels = IntArray(8) {1500};
        this.listener.onDecoderRestart()
    }


    override fun decodeData(data: Protocol.Companion.TelemetryData) {
        var decoded = true
        when (data.telemetryType) {
            Protocol.VBAT -> {
                val value = data.data / 1000f
                listener.onVBATData(value)
            }
            Protocol.CURRENT -> {
                val value = data.data / 100f
                listener.onCurrentData(value)
            }
            Protocol.GPS_ALTITUDE -> {
                val gps_altitude = data.data / 1000.0f
                listener.onGPSAltitudeData(gps_altitude)
            }
            Protocol.GPS_LONGITUDE -> {
                longitude = data.data / 10000000.toDouble()
                newLongitude = true
            }
            Protocol.GPS_LATITUDE -> {
                latitude = data.data / 10000000.toDouble()
                newLatitude = true
            }
            Protocol.GPS_SATELLITES -> {
                satellites = data.data
                listener.onGPSState(satellites, fix)
            }
            Protocol.GPS_STATE -> {
                fix = data.data >= 3
                listener.onGPSState(satellites, fix)
            }
            Protocol.HEADING -> {
                // Hundredths of a degree, from the estimator or from the course
                // the receiver is making good. Both protocols have been sending
                // this for years and nothing here was listening, so a MAVLink
                // model was drawn pointing north for the whole of every flight.
                listener.onHeadingData(data.data / 100f)
            }
            Protocol.ALTITUDE -> {
                val altitude = data.data / 100f
                listener.onAltitudeData(altitude)
            }
            Protocol.GSPEED -> {
                val speed = (data.data / 100f) * 3.6f
                listener.onGSpeedData(speed)
            }
            Protocol.ASPEED -> {
                val speed = (data.data / 100f) * 3.6f
                listener.onAirSpeedData(speed)
            }
            Protocol.VSPEED -> {
                val speed = (data.data / 100f)
                listener.onVSpeedData(speed)
            }
            Protocol.FUEL -> {
                listener.onFuelData(data.data)
            }
            Protocol.FLYMODE -> {
                val rawMode = data.data

                val byteBuffer = ByteBuffer.wrap(data.rawData).order(ByteOrder.LITTLE_ENDIAN)

                val customMode = byteBuffer.int
                val aircraftType = byteBuffer.get().toUByte().toInt()
                val autopilotClass = byteBuffer.get()
                val mode = byteBuffer.get()
                val state = byteBuffer.get().toUByte().toInt()
                val version = byteBuffer.get()

                val isStabilized =
                    (rawMode and MAV_MODE_FLAG_STABILIZE_ENABLED) == MAV_MODE_FLAG_STABILIZE_ENABLED
                val isGuided =
                    (rawMode and MAV_MODE_FLAG_GUIDED_ENABLED) == MAV_MODE_FLAG_GUIDED_ENABLED
                val armed = (rawMode and MAV_MODE_FLAG_SAFETY_ARMED) == MAV_MODE_FLAG_SAFETY_ARMED
                val isFailsafe = state == MAV_STATE_CRITICAL;

                var flyMode: DataDecoder.Companion.FlyMode? = null
                if (isGuided) {
                    flyMode = DataDecoder.Companion.FlyMode.AUTONOMOUS
                } else {
                    if (isStabilized) {
                        flyMode = DataDecoder.Companion.FlyMode.ACRO
                    } else {
                        flyMode = DataDecoder.Companion.FlyMode.MANUAL
                    }
                }

                if ((rawMode and MAV_MODE_FLAG_CUSTOM_MODE_ENABLED) == MAV_MODE_FLAG_CUSTOM_MODE_ENABLED) {
                    flyMode = customModeToFlyMode(customMode, aircraftType, isFailsafe)
                }

                if ( isFailsafe ) {
                    listener.onFlyModeData(armed, false, flyMode, DataDecoder.Companion.FlyMode.FAILSAFE)
                }
                else {
                    listener.onFlyModeData(armed, false, flyMode )
                }
            }
            Protocol.STATUSTEXT -> {
                if (data.rawData !=null) {
                    val byteBuffer = data.rawData.sliceArray( 1..50 )
                    val message = String( byteBuffer )
                    listener.onStatusText(message)
                }
            }
            Protocol.HIGH_LATENCY -> {
                // HIGH_LATENCY2, all of it. One of these per five seconds is
                // the whole of what a satellite-class link carries, so every
                // usable field is published — and none that ArduPilot only
                // pads: eph, epv and climb rate are always sent as zero, and
                // decoding a zero as data would draw a lie.
                val b = ByteBuffer.wrap(data.rawData).order(ByteOrder.LITTLE_ENDIAN)
                val timestamp = b.int
                val lat = b.int
                val lon = b.int
                val customMode = b.short.toInt() and 0xFFFF
                val altitudeMsl = b.short.toInt()
                val targetAltitude = b.short
                val targetDistance = b.short
                val wpNum = b.short
                val failureFlags = b.short.toInt() and 0xFFFF
                val aircraftType = b.get().toInt() and 0xFF
                val autopilot = b.get()
                val heading = (b.get().toInt() and 0xFF) * 2
                val targetHeading = b.get()
                val throttle = b.get().toInt() and 0xFF
                val airspeed = (b.get().toInt() and 0xFF) / 5f
                val airspeedSetpoint = b.get()
                val groundspeed = (b.get().toInt() and 0xFF) / 5f
                val windspeed = b.get()
                val windHeading = b.get()
                val eph = b.get()
                val epv = b.get()
                val temperature = b.get()
                val climbRate = b.get()
                val battery = b.get().toInt()
                // custom0 carries the HEARTBEAT base mode on ArduPilot — and
                // it sits in a signed byte, where the armed flag is the sign
                // bit. Masked first, or every armed flight read as disarmed.
                val baseMode = b.get().toInt() and 0xFF

                latitude = lat / 10000000.toDouble()
                newLatitude = true
                longitude = lon / 10000000.toDouble()
                newLongitude = true
                // No satellite count exists in this message; the fix flag is
                // the inverse of the GPS failure bit, for a position that is
                // actually being reported.
                val gpsFailed = (failureFlags and 1) != 0
                listener.onGPSState(0, !gpsFailed && (lat != 0 || lon != 0))
                listener.onGPSAltitudeData(altitudeMsl.toFloat())
                // Metres above the sea — the altitude frame settles what
                // heights mean exactly as it does for links that only send MSL.
                listener.onAltitudeData(altitudeMsl.toFloat())
                listener.onHeadingData(heading.toFloat())
                listener.onThrottleData(throttle)
                listener.onGSpeedData(groundspeed * 3.6f)
                listener.onAirSpeedData(airspeed * 3.6f)
                if (battery >= 0) {
                    listener.onFuelData(battery)
                }
                val armed = (baseMode and MAV_MODE_FLAG_SAFETY_ARMED) != 0
                listener.onFlyModeData(armed, false,
                    customModeToFlyMode(customMode, aircraftType, false))
            }
            Protocol.ATTITUDE -> {
                val byteBuffer = ByteBuffer.wrap(data.rawData).order(ByteOrder.LITTLE_ENDIAN)
                val time = byteBuffer.int
                val roll = byteBuffer.float
                val pitch = -byteBuffer.float
                val yaw = byteBuffer.float
                val rollSpeed = byteBuffer.float
                val pitchSpeed = byteBuffer.float
                val yawSpeed = byteBuffer.float
                listener.onRollData(Math.toDegrees(roll.toDouble()).toFloat())
                listener.onPitchData(Math.toDegrees(pitch.toDouble()).toFloat())
                listener.onHeadingData(Math.toDegrees(yaw.toDouble()).toFloat())
            }

            Protocol.GPS_ORIGIN_LONGITUDE -> {
            }

            Protocol.GPS_ORIGIN_LATITUDE -> {
            }

            Protocol.GPS_HOME_LONGITUDE -> {
            }

            Protocol.GPS_HOME_LATITUDE -> {
            }

            Protocol.RSSI -> {
                //https://github.com/mavlink/mavlink/issues/1027
				//send 0..100% 
                listener.onRSSIData( if ( data.data == 255) -1 else data.data * 100 / 254);
            }
            Protocol.THROTTLE -> {
                listener.onThrottleData(data.data)
            }
            in Protocol.RC_CHANNEL_0..Protocol.RC_CHANNEL_17 -> {
                val index = data.telemetryType - Protocol.RC_CHANNEL_0;
                if ( index >= rcChannels.size) rcChannels = IntArray(index+1) { i -> if (i < rcChannels.size) rcChannels[i] else 1500 }
                rcChannels[index] = data.data
                listener.onRCChannels(rcChannels)
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
