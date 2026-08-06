package juricabi.com.telemetry.protocol.decoder

import juricabi.com.telemetry.protocol.Protocol
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.pow

class FrskyDataDecoder(listener: Listener) : DataDecoder(listener) {

    private var newLatitude = false
    private var newLongitude = false
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    private var acc_x = 0.0f;
    private var acc_y = 0.0f;
    private var acc_z = 0.0f;
    private var gotRollPitch = false;

    /** The passthrough words, decoded where CRSF decodes the same ones. */
    private val ardu = ArduPassthroughDecoder(listener)

    private val TAG: String = "FrSky Protocol"

    override fun restart() {
        newLatitude = false
        newLongitude = false
        latitude = 0.0
        longitude = 0.0
        acc_x = 0f
        acc_y = 0f
        acc_z = 0f
        gotRollPitch = false
        ardu.reset()
        listener.onDecoderRestart()
    }

    private fun bitExtracted(number: Int, num: Int, pos: Int): Int {
        return (1 shl num) - 1 and (number shr pos - 1)
    }

    //https://github.com/resourcepool/open-tm/blob/master/RESOURCEPOOL/widgets/horizon.lua
    //Round Acc data when close to bounds
    //In stationary state, acceleration should not be higher 1g
    private fun roundAccData(acc: Float):Float {
        if (Math.abs(acc) <= 0.02f )
                return 0f
        else if ( acc > 0.98f)
                return 1f
        else if ( acc < -0.98f )
                return -1f
        else return acc
    }

    //compute roll/pitch from acc data
    //it will be innacurate due to vehicle acceleration
    //but at least something
    private fun computeRollPitchFromAcc() {
        if ( this.gotRollPitch ) return;//preffer ROLL/PITCH sensor data

        var accX = this.roundAccData(this.acc_x)
        var accY = this.roundAccData(this.acc_y)
        var accZ = this.roundAccData(this.acc_z)

        var pitchDeg = 0f
        var rollDeg = 0f

        if ( accY != 0f || accZ != 0f)  {
            var div = Math.sqrt((accY * accY + accZ * accZ).toDouble())
            var pitchRad = Math.atan2(accX.toDouble(), div)
            pitchDeg = Math.toDegrees(pitchRad).toFloat()
        }

        if ( accZ != 0f)  {
            var rollRad = Math.atan2(accY.toDouble(), accZ.toDouble())
            rollDeg = Math.toDegrees(rollRad).toFloat()
        }

        listener.onPitchData(-pitchDeg)
        listener.onRollData(rollDeg)
    }

    override fun decodeData(data: Protocol.Companion.TelemetryData) {
        var decoded = true
        when (data.telemetryType) {
            Protocol.FUEL -> {
                listener.onFuelData(data.data)
            }
            Protocol.GPS -> {
                var gpsData = (data.data and 0x3FFFFFFF) / 10000.0 / 60.0
                if (data.data and 0x40000000 > 0) {
                    gpsData = -gpsData
                }
                if (data.data and 0x80000000.toInt() == 0) {
                    newLatitude = true
                    latitude = gpsData
                } else {
                    newLongitude = true
                    longitude = gpsData
                }
                if (newLatitude && newLongitude) {
                    newLongitude = false
                    newLatitude = false
                    listener.onGPSData(latitude, longitude)
                    try {
//                        outputStreamWriter?.append("$latitude, $longitude\r\n")
                    } catch (e: IOException) {
                        //ignore
                    }
//                  Log.d(TAG, "Decoded GPS lat=$latitude long=$longitude")
                }
            }
            Protocol.CELL_VOLTAGE -> {
                val value = data.data / 100f
                listener.onCellVoltageData(value)
//                Log.d(TAG, "Decoded cell voltage $value")
            }

            Protocol.VBAT_OR_CELL -> {
                val value = data.data / 100f
                listener.onVBATOrCellData(value)
//                Log.d(TAG, "Decoded vbat or cell  $value")
            }

            Protocol.CURRENT -> {
                val value = data.data / 10f
                listener.onCurrentData(value)
//                Log.d(TAG, "Decoded current $value")
            }

            Protocol.HEADING -> {
                val value = data.data / 100f
                listener.onHeadingData(value)
//                Log.d(TAG, "Decoded heading $value")
            }
            Protocol.RSSI -> {
                listener.onRSSIData(data.data.shr(24).and(0xff))
            }

            Protocol.FLYMODE -> {

                //inav: 1 - RTH
                //inav: 2 - ANGLE_HOLD
                val modeG = (data.data / 1000000) % 10

                //inav: 1 - autoland
                //inav: 2 - turtle
                //inav: 4 - send_to
                //inav: 8 - poshold (airplane)
                val modeF = (data.data / 100000) % 10

                //inav: 1 - flaperon
                //inav: 2 - autotune
                //inav: 4 - failsafe
                val modeA = (data.data / 10000) % 10

                //inav: 1 - RTH
                //inav: 2 - waypoint mission
                //inav: 4 - headfree
                //inav: 8 - course hold
                val modeB = data.data / 1000 % 10

                //inav: 1 - heading
                //inav: 2 - althold
                //inav: 4 - poshold (NOT airplane)
                val modeC = data.data / 100 % 10

                //inav: 1 - angle
                //inav: 2 - horizon
                //inav: 4 - manual
                val modeD = data.data / 10 % 10

                //inav: 1 - arming enabled
                //inav: 2 - arming disabled
                //inav: 4 - armed
                val modeE = data.data % 10

                val firstFlightMode: Companion.FlyMode
                val secondFlightMode: Companion.FlyMode?

                if (modeD and 2 == 2) {
                    firstFlightMode = Companion.FlyMode.HORIZON
                } else if (modeD and 1 == 1) {
                    firstFlightMode = Companion.FlyMode.ANGLE
                } else {
                    firstFlightMode = Companion.FlyMode.ACRO
                }

                val armed = modeE and 4 == 4
                val heading = modeC and 1 == 1

                if (modeA and 4 == 4) {
                    secondFlightMode = Companion.FlyMode.FAILSAFE
                } else if (modeB and 1 == 1) {
                    secondFlightMode = Companion.FlyMode.RTH
                } else if (modeG and 1 == 1) {
                    secondFlightMode = Companion.FlyMode.RTH
                } else if (modeD and 4 == 4) {
                    secondFlightMode = Companion.FlyMode.MANUAL
                } else if (modeB and 2 == 2) {
                    secondFlightMode = Companion.FlyMode.WAYPOINT
                } else if (modeB and 8 == 8) {
                    secondFlightMode = Companion.FlyMode.CRUISE
                } else if (modeC and 4 == 4) {
                    secondFlightMode = Companion.FlyMode.HOLD  //NOT airplane
                } else if (modeF and 8 == 8) {
                    secondFlightMode = Companion.FlyMode.HOLD  //airplane
                } else if (modeA and 2 == 2) {
                    secondFlightMode = Companion.FlyMode.AUTOTUNE
                } else if (modeF and 1 == 1) {
                    secondFlightMode = Companion.FlyMode.LANDING
                } else if (modeF and 2 == 2) {
                    secondFlightMode = Companion.FlyMode.TURTLE
                } else if (modeF and 4 == 4) {
                    secondFlightMode = Companion.FlyMode.GEO
                } else {
                    secondFlightMode = null
                }
                listener.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
            }
            Protocol.GPS_STATE -> {
                val satellites = data.data % 100
                val isFix = data.data > 1000
                listener.onGPSState(satellites, isFix)
//                Log.d(TAG, "Decoded satellites $satellites isFix=$isFix")
            }
            Protocol.VSPEED -> {
                val value = data.data / 100f
                listener.onVSpeedData(value)
//                Log.d(TAG, "Decoded vspeed $value")
            }
            Protocol.ALTITUDE -> {
                val value = data.data / 100f
                listener.onAltitudeData(value)
//                Log.d(TAG, "Decoded altitutde $value")
            }
            Protocol.GSPEED -> {
                val value = (data.data / (1944f / 100f)) / 27.778f
                listener.onGSpeedData(value)
//                Log.d(TAG, "Decoded GSpeed $value")
            }
            Protocol.DISTANCE -> {
                listener.onDistanceData(data.data)
//                Log.d(TAG, "Decoded distance ${data.data}")
            }
            Protocol.ROLL -> {
                val value = data.data / 10f
                listener.onRollData(value)
                this.gotRollPitch = true;
                //Log.d(TAG, "Decoded roll $value")
            }
            Protocol.GPS_ALTITUDE -> {
                val value = data.data / 100f
                listener.onGPSAltitudeData(value)
//                Log.d(TAG, "Decoded gps altitude $value")
            }
            Protocol.PITCH -> {
                val value = data.data / 10f
                listener.onPitchData(value)
                this.gotRollPitch = true;
                //Log.d(TAG, "Decoded pitch $value")
            }
            Protocol.ASPEED -> {
                listener.onAirSpeedData(data.data / 10f * 1.852f)
            }
            Protocol.GPS_LONGITUDE -> {

            }
            Protocol.GPS_LATITUDE -> {

            }
            Protocol.GPS_SATELLITES -> {
                listener.onGPSState(data.data, true)
            }
            //decode ardupilot passthrough sensors, shared with the CRSF road
            Protocol.ARDU_GPS_STATUS ->{ //0x5002
                ardu.gpsStatus(data.data, withAltitude = true)
            }

            Protocol.ARDU_BATT_1 ->{ //0x5003
                ardu.battery(data.data)
            }

            Protocol.ARDU_AP_STATUS ->{ //0x5001
                ardu.apStatus(data.data, withMode = true)
            }

            Protocol.ARDU_HOME ->{ //0x5004
                ardu.home(data.data, withAltitude = true)
            }

            Protocol.ARDU_VEL_YAW ->{ //0x5005
                ardu.velocityAndYaw(data.data,
                    withVSpeed = true, withGSpeed = true, withYaw = true)
            }

            Protocol.ARDU_ATTITUDE ->{ //0x5006
                if (ardu.attitude(data.data, wanted = true)) {
                    this.gotRollPitch = true;
                }
            }

            Protocol.DATA_ID_ACC_X_1000 -> {
                val value = data.data / 1000f
                //Log.d(TAG, "Decoded acc_x $value")
                this.acc_x = value
                this.computeRollPitchFromAcc()
            }

            Protocol.DATA_ID_ACC_Y_1000 -> {
                val value = data.data / 1000f
                //Log.d(TAG, "Decoded acc_y $value")
                this.acc_y = value
                this.computeRollPitchFromAcc()
            }

            Protocol.DATA_ID_ACC_Z_1000 -> {
                val value = data.data / 1000f
                //Log.d(TAG, "Decoded acc_z $value")
                this.acc_z = value
                this.computeRollPitchFromAcc()
            }

            Protocol.DATA_ID_ACC_X_100 -> {
                val value = data.data / 100f
                //Log.d(TAG, "Decoded acc_x $value")
                this.acc_x = value
                this.computeRollPitchFromAcc()
            }

            Protocol.DATA_ID_ACC_Y_100 -> {
                val value = data.data / 100f
                //Log.d(TAG, "Decoded acc_y $value")
                this.acc_y = value
                this.computeRollPitchFromAcc()
            }

            Protocol.DATA_ID_ACC_Z_100 -> {
                val value = data.data / 100f
                //Log.d(TAG, "Decoded acc_z $value")
                this.acc_z = value
                this.computeRollPitchFromAcc()
            }

            else -> {
                decoded = false
            }
        }

        if (decoded) {
            listener.onSuccessDecode()
        }
    }
}
