package juricabi.com.telemetry.protocol.decoder

import android.util.Log
import juricabi.com.telemetry.utils.GeoUtils
import juricabi.com.telemetry.protocol.Protocol
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LTMDataDecoder(listener: Listener) : DataDecoder(listener) {

    companion object {
        private const val LTM_MODE_MANUAL = 0
        private const val LTM_MODE_RATE = 1
        private const val LTM_MODE_ANGLE = 2
        private const val LTM_MODE_HORIZON = 3
        private const val LTM_MODE_ACRO = 4
        private const val LTM_MODE_STABALIZED1 = 5
        private const val LTM_MODE_STABALIZED2 = 6
        private const val LTM_MODE_STABILIZED3 = 7
        private const val LTM_MODE_ALTHOLD = 8
        private const val LTM_MODE_GPSHOLD = 9
        private const val LTM_MODE_WAYPOINTS = 10
        private const val LTM_MODE_HEADHOLD = 11
        private const val LTM_MODE_CIRCLE = 12
        private const val LTM_MODE_RTH = 13
        private const val LTM_MODE_FOLLOWWME = 14
        private const val LTM_MODE_LAND = 15
        private const val LTM_MODE_FLYBYWIRE1 = 16
        private const val LTM_MODE_FLYBYWIRE2 = 17
        private const val LTM_MODE_CRUISE = 18
        private const val LTM_MODE_UNKNOWN = 19
        // INAV specific extensions
        private const val LTM_MODE_LAUNCH = 20
        private const val LTM_MODE_AUTOTUNE = 21
    }

    private var newLatitude = false
    private var newLongitude = false
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var homeLatitude: Double = 0.0
    private var homeLongitude: Double = 0.0

    init {
        this.restart()
    }

    override fun restart() {
        this.newLatitude = false
        this.newLongitude = false
        this.latitude = 0.0
        this.longitude = 0.0
        this.homeLatitude = 0.0
        this.homeLongitude = 0.0
        this.listener.onDecoderRestart()
    }

    override fun decodeData(data: Protocol.Companion.TelemetryData) {
        var decoded = true
        val byteBuffer = ByteBuffer.wrap(data.rawData).order(ByteOrder.LITTLE_ENDIAN)
        when (data.telemetryType) {
            Protocol.GPS -> {
                val latitude = byteBuffer.int / 10000000.toDouble()
                val longitude = byteBuffer.int / 10000000.toDouble()
                val speed = byteBuffer.get().toInt() and 0xFF
                val altitude = byteBuffer.int
                val gpsState = byteBuffer.get().toInt() and 0xFF
                // The low two bits are the fix TYPE, as iNav's ltm.c sends it:
                // 0 no GPS, 1 GPS but NO fix yet, 2 a 2D fix, 3 a 3D fix.
                // Testing the low bit alone read "still hunting satellites" as
                // a fix — the remembered-position state everything downstream
                // exists to reject — and a genuine 2D fix as none.
                listener.onGPSState(gpsState ushr 2, (gpsState and 3) >= 2)
                listener.onGPSData(latitude, longitude)
                listener.onGSpeedData(speed * (18 / 5f))
                // Height above home, not above the sea, whatever the frame is
                // called: ArduPilot fills it from get_relative_position_D_home
                // and INAV from its fused estimate relative to home. Reporting
                // it as a sea level height as well put a flight from a hilltop
                // at zero metres above the sea.
                listener.onAltitudeData(altitude / 100f)
                this.latitude = latitude;
                this.longitude = longitude;
                this.newLatitude = true;
                this.newLongitude = true;
            }

            Protocol.ATTITUDE -> {
                val pitch = byteBuffer.short
                val roll = byteBuffer.short
                val heading = byteBuffer.short
                listener.onRollData(roll.toFloat())
                listener.onPitchData(pitch.toFloat())
                listener.onHeadingData(heading.toFloat())
            }

            Protocol.FLYMODE -> {
                val batteryVoltage = byteBuffer.short.toUShort(); //in mv
                val batteryMahDrawn = byteBuffer.short.toInt() and 0xffff; // in mAh
                val rssi = byteBuffer.get().toInt() and 0xff; //0...254
                val airSpeed = byteBuffer.get().toInt() and 0xff; //in m/s
                val status = byteBuffer.get().toInt() and 0xff;

                val ltmFlightMode = status shr 2
                val armed = (status and 1) != 0
                val failsafe = (status and 2) != 0
                var heading = false;
                var firstFlightMode: DataDecoder.Companion.FlyMode? = null
                var secondFlightMode: DataDecoder.Companion.FlyMode? = null

                when (ltmFlightMode) {
                    LTM_MODE_MANUAL -> firstFlightMode = DataDecoder.Companion.FlyMode.MANUAL
                    LTM_MODE_WAYPOINTS -> firstFlightMode = DataDecoder.Companion.FlyMode.MISSION
                    LTM_MODE_RTH -> firstFlightMode = DataDecoder.Companion.FlyMode.RTH
                    LTM_MODE_GPSHOLD -> firstFlightMode = DataDecoder.Companion.FlyMode.LOITER
                    LTM_MODE_CRUISE -> firstFlightMode = DataDecoder.Companion.FlyMode.CRUISE
                    LTM_MODE_LAUNCH -> firstFlightMode = DataDecoder.Companion.FlyMode.TAKEOFF
                    LTM_MODE_AUTOTUNE -> firstFlightMode = DataDecoder.Companion.FlyMode.AUTOTUNE
                    LTM_MODE_ALTHOLD -> firstFlightMode = DataDecoder.Companion.FlyMode.ALTHOLD
                    LTM_MODE_HEADHOLD -> {
                        firstFlightMode = DataDecoder.Companion.FlyMode.ACRO
                        heading = true
                    }
                    LTM_MODE_ANGLE -> firstFlightMode = DataDecoder.Companion.FlyMode.ANGLE
                    LTM_MODE_HORIZON -> firstFlightMode = DataDecoder.Companion.FlyMode.HORIZON
                    LTM_MODE_RATE -> firstFlightMode = DataDecoder.Companion.FlyMode.ACRO
                }

                if ( failsafe) secondFlightMode = DataDecoder.Companion.FlyMode.FAILSAFE

                listener.onVBATData( batteryVoltage.toInt() / 1000f)
                listener.onFuelData( batteryMahDrawn )
                listener.onRSSIData(rssi * 100 / 254)
                listener.onAirSpeedData(airSpeed * 3.6f)
                listener.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
            }
            Protocol.ORIGIN -> {
                this.homeLatitude = byteBuffer.int / 10000000.toDouble()
                this.homeLongitude = byteBuffer.int / 10000000.toDouble()
                // The one protocol that says where home is also says how
                // high it stands — GPS_home's own altitude, centimetres.
                // Together with the relative heights every other frame
                // carries, that is the absolute altitude this protocol
                // never sends directly, straight off the wire, no terrain
                // model owed — and a launch from a roof is measured at the
                // roof, which no ground map could know.
                val homeAltitude = byteBuffer.int / 100f
                if (homeLatitude != 0.0 || homeLongitude != 0.0) {
                    listener.onHomeData(homeLatitude, homeLongitude, homeAltitude)
                }
            }
            else -> {
                decoded = false
            }
        }

        if (this.newLatitude && this.newLongitude &&
            this.latitude != 0.0 && this.longitude != 0.0 &&
            this.homeLatitude != 0.0 && this.homeLongitude != 0.0) {

                val distance = GeoUtils.computeDistanceBetween(
                    this.homeLatitude, this.homeLongitude, this.latitude, this.longitude
                )

                listener.onDistanceData(distance.toInt())
                this.newLatitude = false
                this.newLongitude = false
        }

        if (decoded) {
            listener.onSuccessDecode()
        }
    }
}
