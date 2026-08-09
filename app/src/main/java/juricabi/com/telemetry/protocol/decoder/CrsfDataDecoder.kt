package juricabi.com.telemetry.protocol.decoder

import android.util.Log
import juricabi.com.telemetry.protocol.Protocol
import juricabi.com.telemetry.protocol.ProtocolFactory
import juricabi.com.telemetry.protocol.SourceFreshness

const val MAX_RSSI = -30
const val MIN_RSSI = -120

class CrsfDataDecoder(listener: Listener) : DataDecoder(listener) {

    private var newLatitude = false
    private var newLongitude = false
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var rcChannels = IntArray(16) {1500};

    /** The ArduPilot passthrough words, decoded where S.Port decodes them. */
    private val ardu = ArduPassthroughDecoder(listener)

    /**
     * Said once, when the first passthrough word arrives.
     *
     * Detection names the link, and the link is CRSF whether or not an
     * autopilot is talking over it — but which of the two it is decides what
     * half these readings mean, so it is worth saying. Reported down the same
     * road detection uses: the screen and the log both hear it already, and a
     * replay hears it again from the log's own bytes.
     */
    private var saidPassthrough = false

    private fun sayPassthrough() {
        if (saidPassthrough) return
        saidPassthrough = true
        listener.onProtocolDetected(ProtocolFactory.CRSF_WITH_PASSTHROUGH)
    }

    /**
     * Which of two streams answers, when both carry the same value.
     *
     * ArduPilot with passthrough enabled sends its native CRSF frames as well,
     * at a higher rate, so those stay authoritative for what they carry and
     * the passthrough word fills in only when they stop. The passthrough side
     * wins the other way round for what it alone knows properly: the real GPS
     * fix, the real armed bit, and the distance to the real home.
     */
    private val nativeGps = SourceFreshness()
    private val nativeBattery = SourceFreshness()
    private val nativeVario = SourceFreshness()
    private val nativeAttitude = SourceFreshness()
    private val nativeAltitude = SourceFreshness()
    private val passthroughStatus = SourceFreshness()
    private val passthroughGps = SourceFreshness()
    private val passthroughHome = SourceFreshness()

    private fun sq(v : Int) : Int {
        return v*v;
    }

    init {
        this.restart()
    }
    override fun restart() {
        this.newLatitude = false
        this.newLongitude = false
        this.latitude = 0.0
        this.longitude = 0.0
        this.rcChannels = IntArray(16) { 1500 };
        this.listener.onDecoderRestart()
    }

    override fun decodeData(data: Protocol.Companion.TelemetryData) {
        var decoded = true
        when (data.telemetryType) {
            Protocol.VBAT -> {
                val value = data.data / 10f
                listener.onVBATData(value)
            }
            Protocol.CURRENT -> {
                val value = data.data / 10f
                listener.onCurrentData(value)
            }
            Protocol.GPS_ALTITUDE -> {
                // Height above sea level, from the GPS frame. It was commented
                // out, so on a CRSF link nothing ever reported it — which left
                // the Altitude above MSL widget blank, and the flight without
                // the one height that can be compared against terrain.
                listener.onGPSAltitudeData(data.data.toFloat())
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
                // First word of the native GPS frame, so the frame is counted
                // here once. The satellite-count guess at a fix stands back
                // while the passthrough stream carries the receiver's real one.
                nativeGps.arrived()
                val satellites = data.data
                if (!passthroughGps.fresh()) {
                    listener.onGPSState(satellites, satellites > 6)
                }
            }
            Protocol.HEADING -> {
                val heading = data.data / 100f
//                listener.onHeadingData(heading)
            }
/*
TODO: recheck when pull reqests are merged
-------------
Fix CRSF telemetry corruption from PR #11025 #11189
https://github.com/iNavFlight/inav/pull/11189


CRSF_FRAMETYPE_BAROMETER_ALTITUDE = 0x09
crsfBarometerAltitude()
https://github.com/iNavFlight/inav/blob/e5bfe799c3d56fc95a7573b27bfec77ca8044249/src/main/telemetry/crsf.c#L295
----------------

CRSF Baro Altitude and Vario, AirSpeed (fixed conflicts from #11100) 
https://github.com/iNavFlight/inav/pull/11168
  

CRSF_FRAMETYPE_BAROMETER_ALTITUDE_VARIO_SENSOR = 0x09
crsfFrameBarometerAltitudeVarioSensor()
https://github.com/iNavFlight/inav/blob/135456936834ab4129e6ed540038b2e88dcb3c44/src/main/telemetry/crsf.c#L285

            Protocol.ALTITUDE -> {
                var altitude = data.data.toUShort().toInt();

                if (altitude == 0 ) {
                    altitude = - 1000; // -1000 m = -10000 dm
                } else if (altitude >= 0xffe)
                {
                    altitude = 32765;// 32765 m = (0x7ffe * 10 - 5) dm
                } else if ( (altitude and 0x8000) == 0x8000)
                {
                    altitude = altitude and 0x7fff;  //in m
                }
                else
                {
                    altitude = (altitude - 10000) / 10; //dm to m
                }

                listener.onAltitudeData(altitude.toFloat())
            }
*/

            Protocol.ALTITUDE -> {
                // Metres by the time it reaches here. Two frames feed this —
                // the GPS one and the barometric one — and only the protocol
                // knows which, so each unpacks its own encoding before sending.
                nativeAltitude.arrived()
                listener.onAltitudeData(data.data.toFloat())
            }
            Protocol.GSPEED -> {
                val speed = data.data / 10f
                listener.onGSpeedData(speed)
            }
            Protocol.VSPEED -> {
                // centimetres a second, as everywhere else here — a two metre
                // climb was being reported as twenty
                nativeVario.arrived()
                val speed = data.data / 100f
                listener.onVSpeedData(speed)
            }
            Protocol.RSSI -> {
                //data.data is RSSI in dbm, negative number like -76
                //convert RSSI to % like inav do with default MIN_RSSI, MAX_RSSI settings
                var v = (100 * sq(MAX_RSSI - MIN_RSSI) - (100 * sq(MAX_RSSI  - data.data))) / sq(MAX_RSSI - MIN_RSSI);
                if (data.data >= MAX_RSSI ) v = 99;
                if (data.data < MIN_RSSI) v = 0;

                listener.onRSSIData(v)
            }
            Protocol.CRSF_UP_LQ -> {
                listener.onUpLqData(data.data)
            }
            Protocol.CRSF_DN_LQ -> {
                listener.onDnLqData(data.data)
            }
            Protocol.ELRS_RF_MODE -> {
                listener.onElrsModeModeData(data.data)
            }
            Protocol.DEVICE_NAME -> {
                data.rawData?.let { listener.onDeviceName(String(it)) }
            }
            Protocol.FUEL -> {
                listener.onFuelData(data.data)
            }
            Protocol.FLYMODE -> {
                // The passthrough AP status carries the real armed bit; this
                // frame's mode string marks disarmed with an asterisk that
                // ArduPilot never sends, so while that stream runs it answers.
                data.rawData?.takeIf { !passthroughStatus.fresh() }?.let {
                    val stringLength = it.indexOfFirst { it == 0x00.toByte() }
                    // A frame with no terminator is malformed; reading past it
                    // would throw, and an exception here takes the link down.
                    if (stringLength < 2) return@let
                    val reported = String(it, 1, stringLength - 1)

                    // Betaflight marks a disarmed quad by putting an asterisk
                    // after the mode name, so a quad sitting on the bench sends
                    // "ACRO*". That matched none of the names below, so nothing
                    // was reported at all and the readout kept whatever it last
                    // said — showing "Armed | Acro" for a disarmed quad.
                    val armed = !reported.endsWith("*")
                    val flightMode = if (armed) reported else reported.dropLast(1)

                    when (flightMode) {
                        "AIR", "ACRO" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.ACRO, null)
                        }
                        "!FS!" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.FAILSAFE, null)
                        }
                        "MANU" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.MANUAL, null)
                        }
                        "RTH" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.RTH, null)
                        }
                        "WRTH" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.WAYPOINT, Companion.FlyMode.RTH)
                        }
                        "HOLD", "LOTR" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.LOITER, null)
                        }
                        "HRST" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.HOME_RESET, null)
                        }
                        "3CRS","CRUZ" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.CRUISE3D, null)
                        }
                        "CRS", "CRSH" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.CRUISE, null)
                        }
                        "AH" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.ALTHOLD, null)
                        }
                        "WP" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.WAYPOINT, null)
                        }
                        "ANGL", "STAB" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.ANGLE, null)
                        }
                        "HOR" -> {
                            listener.onFlyModeData(armed, false, Companion.FlyMode.HORIZON, null)
                        }
                        "WAIT" -> {
                            listener.onFlyModeData(false, false, Companion.FlyMode.WAIT, null)
                        }
                        "!ERR" -> {
                            listener.onFlyModeData(false, false, Companion.FlyMode.ERROR, null)
                        }
                        "LAND" -> {
                            listener.onFlyModeData(false, false, Companion.FlyMode.LANDING, null)
                        }
                        "GEO" -> {
                            listener.onFlyModeData(false, false, Companion.FlyMode.GEO, null)
                        }
                        "TURT" -> {
                            listener.onFlyModeData(false, false, Companion.FlyMode.TURTLE, null)
                        }
                        "ANGH" -> {
                            listener.onFlyModeData(false, false, Companion.FlyMode.ANGLE_HOLD, null)
                        }
                        "OK" -> {
                            listener.onFlyModeData(false, false, Companion.FlyMode.ACRO, null)
                        }
                        else -> {
                            // An unknown mode name is still worth reporting the
                            // arming state for, rather than leaving the readout
                            // on the previous mode indefinitely.
                            listener.onFlyModeData(armed, false, null, null)
                            Log.d("CrsfData", "Bad mode $flightMode")
                        }
                    }
                }
            }
            Protocol.PITCH -> {
                // first word of the native attitude frame: counted once here
                nativeAttitude.arrived()
                val pitch = Math.toDegrees(data.data.toDouble() / 10000)
                listener.onPitchData(pitch.toFloat())
            }
            Protocol.ROLL -> {
                val roll = Math.toDegrees(data.data.toDouble() / 10000)
                listener.onRollData(roll.toFloat())
            }
            Protocol.YAW -> {
                val yaw = Math.toDegrees(data.data.toDouble() / 10000)
                listener.onHeadingData(yaw.toFloat())
            }
            in Protocol.RC_CHANNEL_0..Protocol.RC_CHANNEL_15 -> {
                val index = data.telemetryType - Protocol.RC_CHANNEL_0;
                rcChannels[index] = data.data
                listener.onRCChannels(rcChannels)
            }
            Protocol.DN_SNR -> {
                listener.onDNSNRData(data.data)
            }
            Protocol.UP_SNR -> {
                listener.onUPSNRData(data.data)
            }
            Protocol.ANT -> {
                listener.onAntData(data.data)
            }
            Protocol.POWER -> {
                listener.onPowerData(data.data)
            }
            Protocol.RSSI_DBM_1 -> {
                listener.onRssiDbm1Data(data.data)
            }
            Protocol.RSSI_DBM_2 -> {
                listener.onRssiDbm2Data(data.data)
            }
            Protocol.RSSI_DBM_D -> {
                listener.onRssiDbmdData(data.data)
            }
            Protocol.VBAT_OR_CELL -> {
                nativeBattery.arrived()
                val value = data.data / 10f
                listener.onVBATOrCellData(value)
            }
            Protocol.DISTANCE -> {
                // Computed from wherever the model armed, which is a stand-in.
                // While the passthrough home word is arriving, the distance to
                // the real home is already being reported from it.
                if (!passthroughHome.fresh()) {
                    listener.onDistanceData(data.data)
                }
            }
            Protocol.ASPEED -> {
                listener.onAirSpeedData(data.data / 10f)
            }
            //ardupilot passthrough over CRSF, decoded where S.Port decodes it.
            //Native frames stay authoritative for what they carry at a higher
            //rate; the passthrough words answer for what only they know.
            Protocol.ARDU_AP_STATUS -> {
                sayPassthrough()
                passthroughStatus.arrived()
                ardu.apStatus(data.data, withMode = true)
            }
            Protocol.ARDU_GPS_STATUS -> {
                passthroughGps.arrived()
                ardu.gpsStatus(data.data, withAltitude = !nativeGps.fresh())
            }
            Protocol.ARDU_BATT_1 -> {
                if (!nativeBattery.fresh()) {
                    ardu.battery(data.data)
                }
            }
            Protocol.ARDU_HOME -> {
                passthroughHome.arrived()
                ardu.home(data.data, withAltitude = !nativeAltitude.fresh())
            }
            Protocol.ARDU_VEL_YAW -> {
                val nativeAttitudeFresh = nativeAttitude.fresh()
                ardu.velocityAndYaw(data.data,
                    withVSpeed = !nativeVario.fresh(),
                    withGSpeed = !nativeGps.fresh(),
                    withYaw = !nativeAttitudeFresh)
            }
            Protocol.ARDU_ATTITUDE -> {
                ardu.attitude(data.data, wanted = !nativeAttitude.fresh())
            }
            Protocol.STATUSTEXT -> {
                if (data.rawData != null) {
                    val terminator = data.rawData.indexOfFirst { it == 0.toByte() }
                    val end = if (terminator > 0) terminator else data.rawData.size
                    if (end > 1) {
                        listener.onStatusText(String(data.rawData, 1, end - 1))
                    }
                }
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
