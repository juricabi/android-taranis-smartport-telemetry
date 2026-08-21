package juricabi.com.telemetry.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.nex3z.flowlayout.FlowLayout
import juricabi.com.telemetry.R
import juricabi.com.telemetry.manager.PreferenceManager
import juricabi.com.telemetry.manager.SensorTimeoutManager
import juricabi.com.telemetry.protocol.ProtocolFactory
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import kotlin.math.roundToInt

/**
 * The telemetry readouts: every tile on the top and bottom bars, its
 * formatting, its icon, and its greying when the sensor goes quiet.
 *
 * One adapter at the decoder-listener seam. The activity forwards the
 * display-only callbacks here whole; for values it also flies the flight by
 * (altitude, GPS), it keeps the flight half and hands this the number to
 * show. Decode callbacks arrive on the poller's thread, exactly as they do
 * at the activity, and post their own hops to the UI thread; the named
 * show/reset/place methods expect the UI thread, where their callers already
 * are.
 */
class TelemetryPanel(
    private val activity: Activity,
    private val preferenceManager: PreferenceManager,
    /** The one dialog funnel the activity keeps — no dialog opens around it. */
    private val showDialog: (AlertDialog) -> Unit,
    /** Nothing is feeding this screen: a decode posted after the link went. */
    private val idle: () -> Boolean,
    /** A replay's battery is history — it is never asked about. */
    private val replaying: () -> Boolean,
    /**
     * Which protocol the link speaks, "" until it has said. The fact is the
     * activity's — flight decisions hang on it — and this panel only renders
     * under it: the GHST tables, the rate icon, the long-press guard.
     */
    private val linkProtocol: () -> String
) : DataDecoder.Companion.DefaultDecodeListener(), SensorTimeoutManager.Listener {

    private val sensorTimeoutManager = SensorTimeoutManager(this)

    private val satellites: TextView = activity.findViewById(R.id.satellites)
    private val fuel: TextView = activity.findViewById(R.id.fuel)
    private val rssi: TextView = activity.findViewById(R.id.rssi)
    private val current: TextView = activity.findViewById(R.id.current)
    private val voltage: TextView = activity.findViewById(R.id.voltage)
    private val phoneBattery: TextView = activity.findViewById(R.id.phone_battery)
    private val speed: TextView = activity.findViewById(R.id.speed)
    private val airspeed: TextView = activity.findViewById(R.id.airspeed)
    private val vspeed: TextView = activity.findViewById(R.id.vspeed)
    private val distance: TextView = activity.findViewById(R.id.distance)
    private val traveledDistance: TextView = activity.findViewById(R.id.traveled_distance)
    private val altitude: TextView = activity.findViewById(R.id.altitude)
    private val altitudeMsl: TextView = activity.findViewById(R.id.altitude_msl)
    private val statustext: TextView = activity.findViewById(R.id.statustext)
    private val rcWidget: RCWidget = activity.findViewById(R.id.rc_widget)
    private val dnSnr: TextView = activity.findViewById(R.id.dn_snr)
    private val upSnr: TextView = activity.findViewById(R.id.up_snr)
    private val upLq: TextView = activity.findViewById(R.id.up_lq)
    private val dnLq: TextView = activity.findViewById(R.id.dn_lq)
    private val elrsRate: TextView = activity.findViewById(R.id.elrs_rate)
    private val ant: TextView = activity.findViewById(R.id.ant)
    private val power: TextView = activity.findViewById(R.id.power)
    private val rssiDbm1: TextView = activity.findViewById(R.id.up_rssi_dbm1)
    private val rssiDbm2: TextView = activity.findViewById(R.id.up_rssi_dbm2)
    private val rssiDbmd: TextView = activity.findViewById(R.id.dn_rssi_dbm)
    private val cellVoltage: TextView = activity.findViewById(R.id.cell_voltage)
    private val throttle: TextView = activity.findViewById(R.id.throttle)
    private val protocolView: TextView = activity.findViewById(R.id.protocol)
    private val tlmRate: TextView = activity.findViewById(R.id.tlm_rate)
    private val mode: TextView = activity.findViewById(R.id.mode)
    private val topList: FlowLayout = activity.findViewById(R.id.top_list)
    private val bottomList: FlowLayout = activity.findViewById(R.id.bottom_list)

    private val sensorViewMap: HashMap<String, View> = hashMapOf(
        Pair(PreferenceManager.sensors.elementAt(0).name, satellites),
        Pair(PreferenceManager.sensors.elementAt(1).name, fuel),
        Pair(PreferenceManager.sensors.elementAt(2).name, voltage),
        Pair(PreferenceManager.sensors.elementAt(3).name, current),
        Pair(PreferenceManager.sensors.elementAt(4).name, speed),
        Pair(PreferenceManager.sensors.elementAt(5).name, distance),
        Pair(PreferenceManager.sensors.elementAt(6).name, traveledDistance),
        Pair(PreferenceManager.sensors.elementAt(7).name, altitude),
        Pair(PreferenceManager.sensors.elementAt(8).name, phoneBattery),
        Pair(PreferenceManager.sensors.elementAt(9).name, rcWidget),
        Pair(PreferenceManager.sensors.elementAt(10).name, rssi),
        Pair(PreferenceManager.sensors.elementAt(11).name, dnSnr),
        Pair(PreferenceManager.sensors.elementAt(12).name, upSnr),
        Pair(PreferenceManager.sensors.elementAt(13).name, upLq),
        Pair(PreferenceManager.sensors.elementAt(14).name, dnLq),
        Pair(PreferenceManager.sensors.elementAt(15).name, elrsRate),
        Pair(PreferenceManager.sensors.elementAt(16).name, ant),
        Pair(PreferenceManager.sensors.elementAt(17).name, power),
        Pair(PreferenceManager.sensors.elementAt(18).name, rssiDbm1),
        Pair(PreferenceManager.sensors.elementAt(19).name, rssiDbm2),
        Pair(PreferenceManager.sensors.elementAt(20).name, rssiDbmd),
        Pair(PreferenceManager.sensors.elementAt(21).name, airspeed),
        Pair(PreferenceManager.sensors.elementAt(22).name, vspeed),
        Pair(PreferenceManager.sensors.elementAt(23).name, cellVoltage),
        Pair(PreferenceManager.sensors.elementAt(24).name, altitudeMsl),
        Pair(PreferenceManager.sensors.elementAt(25).name, throttle),
        Pair(PreferenceManager.sensors.elementAt(26).name, tlmRate),
        Pair(PreferenceManager.sensors.elementAt(27).name, protocolView)
    )

    companion object {
        // Ghost RF profiles, matching EdgeTX ghstRfProfileValue
        private val GHST_RF_PROFILES = arrayOf(
            "Auto", "Norm", "Race", "Pure", "Long", "Unused", "Race2", "Pure2"
        )
    }

    init {
        elrsRate.setOnLongClickListener {
            // Only meaningful on CRSF (or before a link, to preset it); on GHST or
            // any other link the rate is not a CRSF rf_mode, so say so rather than
            // let the choice do nothing. (An empty name IS "before a link" — the
            // old null-check here could never be true of a non-null field, and
            // quietly refused the preset the comment promised.)
            if (linkProtocol().isEmpty() || linkProtocol() == "CRSF") showCrsfSystemDialog()
            else Toast.makeText(
                activity, "Rate system applies to CRSF links", Toast.LENGTH_SHORT
            ).show()
            true
        }
    }

    /**
     * Turning the phone round builds the screen again from nothing: an answer
     * already given to the cell question must not be asked for a second time.
     */
    fun saveInto(outState: android.os.Bundle) {
        outState.putInt("cells", detectedCells)
        outState.putBoolean("cells_answered", cellsAnswered)
        outState.putBoolean("cells_asked", cellsAsked)
    }

    fun restoreFrom(savedInstanceState: android.os.Bundle) {
        detectedCells = savedInstanceState.getInt("cells", 0)
        cellsAnswered = savedInstanceState.getBoolean("cells_answered", false)
        cellsAsked = savedInstanceState.getBoolean("cells_asked", false)
    }

    // ------------------------------------------------------------- placement

    /** The tiles laid onto the two bars as the settings order them. UI thread. */
    fun placeSensors() {
        val sensorsSettings = preferenceManager.getSensorsSettings().sortedBy { it.index }
        topList.removeAllViews()
        bottomList.removeAllViews()
        sensorsSettings.forEach {
            val sensorView = sensorViewMap[it.name]
            sensorView?.visibility = if (it.shown) View.VISIBLE else View.GONE
            if (it.position == "top") {
                topList.addView(sensorView)
            } else {
                bottomList.addView(sensorView)
            }
        }
    }

    // ------------------------------------------------------------ formatting

    private fun formatDistance(v: Float): String {
        if (v < 1000) {
            return "${"%.0f".format(v)} m"
        } else {
            return "${"%.2f".format(v / 1000)} km"
        }
    }

    private fun formatHeight(v: Float): String {
        if (v < -1000) {
            return "${"%.1f".format(v / 1000)} km"
        } else if (v < -10) {
            return "${"%.0f".format(v)} m"
        } else if (v < 0) {
            return "${"%.1f".format(v)} m"
        } else  if (v < 10) {
            return "${"%.2f".format(v)} m"
        } else if (v < 100) {
            return "${"%.1f".format(v)} m"
        } else if (v < 1000) {
            return "${"%.0f".format(v)} m"
        } else {
            return "${"%.2f".format(v / 1000)} km"
        }
    }

    private fun formatPower(v: Int, suffix: String): String {
        if (v < 1000) {
            return "$v $suffix"
        } else {
            if (suffix == "mAh") {
                return "${"%.2f".format(v / 1000f)} Ah"
            } else {
                return "${"%.2f".format(v / 1000f)} Wh"
            }
        }
    }

    // ------------------------------------------------------------------ icons

    /** Start of the text held landscape, above it held portrait — one rule, every tile. */
    private fun setIcon(view: TextView, res: Int) {
        val drawable = ContextCompat.getDrawable(activity, res)
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            view.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
        } else {
            view.setCompoundDrawablesWithIntrinsicBounds(null, drawable, null, null)
        }
    }

    private fun rssiIconRes(rssi: Int): Int = when (rssi) {
        in 81..100 -> R.drawable.ic_rssi_5
        in 61..80 -> R.drawable.ic_rssi_4
        in 41..69 -> R.drawable.ic_rssi_3
        in 21..40 -> R.drawable.ic_rssi_2
        in 1..20 -> R.drawable.ic_rssi_1
        0 -> R.drawable.ic_rssi_0
        else -> R.drawable.ic_rssi_5
    }

    private fun upLqIconRes(lq: Int): Int = when (lq) {
        in 81..100 -> R.drawable.ic_up_lq_5
        in 61..80 -> R.drawable.ic_up_lq_4
        in 41..69 -> R.drawable.ic_up_lq_3
        in 21..40 -> R.drawable.ic_up_lq_2
        in 1..20 -> R.drawable.ic_up_lq_1
        0 -> R.drawable.ic_up_lq_0
        else -> R.drawable.ic_up_lq_5
    }

    private fun dnLqIconRes(lq: Int): Int = when (lq) {
        in 81..100 -> R.drawable.ic_dn_lq_5
        in 61..80 -> R.drawable.ic_dn_lq_4
        in 41..69 -> R.drawable.ic_dn_lq_3
        in 21..40 -> R.drawable.ic_dn_lq_2
        in 1..20 -> R.drawable.ic_dn_lq_1
        0 -> R.drawable.ic_dn_lq_0
        else -> R.drawable.ic_dn_lq_5
    }

    private fun rssiDbm1IconRes(rssi: Int): Int = when (rssi) {
        in -31..0 -> R.drawable.ic_rssi_dbm1_5
        in -51..-30 -> R.drawable.ic_rssi_dbm1_4
        in -71..-59 -> R.drawable.ic_rssi_dbm1_3
        in -91..-70 -> R.drawable.ic_rssi_dbm1_2
        in -120..-90 -> R.drawable.ic_rssi_dbm1_1
        0 -> R.drawable.ic_rssi_dbm1_0
        else -> R.drawable.ic_rssi_dbm1_5
    }

    private fun rssiDbm2IconRes(rssi: Int): Int = when (rssi) {
        in -31..0 -> R.drawable.ic_rssi_dbm2_5
        in -51..-30 -> R.drawable.ic_rssi_dbm2_4
        in -71..-50 -> R.drawable.ic_rssi_dbm2_3
        in -91..-70 -> R.drawable.ic_rssi_dbm2_2
        in -121..-90 -> R.drawable.ic_rssi_dbm2_1
        0 -> R.drawable.ic_rssi_dbm2_0
        else -> R.drawable.ic_rssi_dbm2_5
    }

    private fun rssiDbmdIconRes(rssi: Int): Int = when (rssi) {
        in -31..0 -> R.drawable.ic_rssi_dbmd_5
        in -51..-30 -> R.drawable.ic_rssi_dbmd_4
        in -71..50 -> R.drawable.ic_rssi_dbmd_3
        in -91..-70 -> R.drawable.ic_rssi_dbmd_2
        in -120..-90 -> R.drawable.ic_rssi_dbmd_1
        0 -> R.drawable.ic_rssi_dbmd_0
        else -> R.drawable.ic_rssi_dbmd_5
    }

    private fun fuelIconRes(percentage: Int): Int = when (percentage) {
        in 91..100 -> R.drawable.ic_battery_full
        in 81..90 -> R.drawable.ic_battery_90
        in 61..80 -> R.drawable.ic_battery_80
        in 51..60 -> R.drawable.ic_battery_60
        in 31..50 -> R.drawable.ic_battery_50
        in 21..30 -> R.drawable.ic_battery_30
        in 0..20 -> R.drawable.ic_battery_alert
        else -> R.drawable.ic_battery_unknown
    }

    // ------------------------------------------------- display-only callbacks

    override fun onVSpeedData(vspeed: Float) {
        sensorTimeoutManager.onVSpeedData(vspeed)
        activity.runOnUiThread {
            this.vspeed.text = "${"%.1f".format(vspeed)} m/s"
        }
    }

    override fun onThrottleData(throttle: Int) {
        sensorTimeoutManager.onThrottleData(throttle)
        activity.runOnUiThread {
            this.throttle.text = throttle.toString()
        }
    }

    override fun onDistanceData(distance: Int) {
        sensorTimeoutManager.onDistanceData(distance)
        activity.runOnUiThread {
            this.distance.text = formatDistance(distance.toFloat())
        }
    }

    override fun onGSpeedData(speed: Float) {
        sensorTimeoutManager.onGSpeedData(speed)
        activity.runOnUiThread {
            this.speed.text = "${speed.roundToInt()} km/h"
        }
    }

    override fun onAirSpeedData(speed: Float) {
        sensorTimeoutManager.onAirSpeedData(speed)
        activity.runOnUiThread {
            this.airspeed.text = "${speed.roundToInt()} km/h"
        }
    }

    override fun onRCChannels(rcChannels: IntArray) {
        sensorTimeoutManager.onRCChannels(rcChannels)
        activity.runOnUiThread {
            rcWidget.setChannels(rcChannels)
        }
    }

    override fun onStatusText(message: String) {
        sensorTimeoutManager.onStatusText(message)
        activity.runOnUiThread {
            statustext.text = message
        }
    }

    override fun onGPSState(satellites: Int, gpsFix: Boolean) {
        sensorTimeoutManager.onGPSState(satellites, gpsFix)
        activity.runOnUiThread {
            // Nothing is feeding this screen, so this was decoded before the
            // link went and posted after: it belongs to a flight that has
            // ended. The activity guards its fix state the same way.
            if (idle()) return@runOnUiThread
            this.satellites.text = if (satellites == 99) "ES" else satellites.toString()
        }
    }

    // the freshness half of callbacks whose values the activity flies by
    override fun onGPSData(latitude: Double, longitude: Double) {
        sensorTimeoutManager.onGPSData(latitude, longitude)
    }

    override fun onGPSData(list: List<juricabi.com.telemetry.maps.Position>, addToEnd: Boolean) {
        sensorTimeoutManager.onGPSData(list, addToEnd)
    }

    override fun onAltitudeData(altitude: Float) {
        sensorTimeoutManager.onAltitudeData(altitude)
        showAltitude(altitude, false)
    }

    override fun onGPSAltitudeData(altitude: Float) {
        sensorTimeoutManager.onGPSAltitudeData(altitude)
        // the shown number waits for the activity, which knows the lift
    }

    override fun onRSSIData(rssi: Int) {
        sensorTimeoutManager.onRSSIData(rssi)
        activity.runOnUiThread {
            this.rssi.text = if (rssi == -1) "-" else rssi.toString()
            setIcon(this.rssi, rssiIconRes(rssi))
        }
    }

    override fun onUpLqData(lq: Int) {
        sensorTimeoutManager.onUpLqData(lq)
        activity.runOnUiThread {
            upLq.text = if (lq == -1) "-" else lq.toString()
            setIcon(upLq, upLqIconRes(lq))
        }
    }

    override fun onDnLqData(lq: Int) {
        sensorTimeoutManager.onDnLqData(lq)
        activity.runOnUiThread {
            dnLq.text = if (lq == -1) "-" else lq.toString()
            setIcon(dnLq, dnLqIconRes(lq))
        }
    }

    override fun onRssiDbm1Data(rssi: Int) {
        sensorTimeoutManager.onRssiDbm1Data(rssi)
        activity.runOnUiThread {
            rssiDbm1.text = if (rssi == 0) "-" else rssi.toString()
            setIcon(rssiDbm1, rssiDbm1IconRes(rssi))
        }
    }

    override fun onRssiDbm2Data(rssi: Int) {
        sensorTimeoutManager.onRssiDbm2Data(rssi)
        activity.runOnUiThread {
            rssiDbm2.text = if (rssi == 0) "-" else rssi.toString()
            setIcon(rssiDbm2, rssiDbm2IconRes(rssi))
        }
    }

    override fun onRssiDbmdData(rssi: Int) {
        sensorTimeoutManager.onRssiDbmdData(rssi)
        activity.runOnUiThread {
            rssiDbmd.text = if (rssi == 0) "-" else rssi.toString()
            setIcon(rssiDbmd, rssiDbmdIconRes(rssi))
        }
    }

    override fun onCurrentData(current: Float) {
        sensorTimeoutManager.onCurrentData(current)
        activity.runOnUiThread {
            this.current.text = "${"%.2f".format(current)} A"
        }
    }

    override fun onDNSNRData(snr: Int) {
        sensorTimeoutManager.onDNSNRData(snr)
        activity.runOnUiThread {
            dnSnr.text = snr.toString()
        }
    }

    override fun onUPSNRData(snr: Int) {
        sensorTimeoutManager.onUPSNRData(snr)
        activity.runOnUiThread {
            upSnr.text = snr.toString()
        }
    }

    override fun onAntData(activeAntena: Int) {
        sensorTimeoutManager.onAntData(activeAntena)
        activity.runOnUiThread {
            ant.text = (activeAntena + 1).toString()
        }
    }

    override fun onPowerData(power: Int) {
        sensorTimeoutManager.onPowerData(power)
        if (linkProtocol() == "GHST") {
            // Ghost reports the power in mW and uses levels the CRSF table does not have
            activity.runOnUiThread {
                this.power.text =
                    if (power >= 1000 && power % 1000 == 0) "${power / 1000}W" else "${power}mW"
            }
            return
        }
        activity.runOnUiThread {
            when (power) {
                1 -> this.power.text = "10mW"
                2 -> this.power.text = "25mW"
                3 -> this.power.text = "100mW"
                4 -> this.power.text = "500mW"
                5 -> this.power.text = "1W"
                6 -> this.power.text = "2W"
                7 -> this.power.text = "250mW"
                8 -> this.power.text = "50mW"
                else -> this.power.text = power.toString()
            }
        }
    }

    override fun onTelemetryByte() {
        sensorTimeoutManager.onTelemetryByte()
    }

    // ------------------------------------------------------------- fly mode

    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) {
        activity.runOnUiThread {
            var text = if (armed) "Armed" else "Disarmed"
            if (heading) text += " | Heading"
            flyModeWord(firstFlightMode)?.let { text += " | $it" }
            flyModeWord(secondFlightMode)?.let { text += " | $it" }
            mode.text = text
        }
    }

    private fun flyModeWord(flyMode: DataDecoder.Companion.FlyMode?): String? =
        when (flyMode) {
            DataDecoder.Companion.FlyMode.ACRO -> "Acro"
            DataDecoder.Companion.FlyMode.HORIZON -> "Horizon"
            DataDecoder.Companion.FlyMode.ANGLE -> "Angle"
            DataDecoder.Companion.FlyMode.FAILSAFE -> "Failsafe"
            DataDecoder.Companion.FlyMode.RTH -> "RTH"
            DataDecoder.Companion.FlyMode.WAYPOINT -> "Waypoint"
            DataDecoder.Companion.FlyMode.MANUAL -> "Manual"
            DataDecoder.Companion.FlyMode.CRUISE -> "Cruise"
            DataDecoder.Companion.FlyMode.HOLD -> "Hold"
            DataDecoder.Companion.FlyMode.HOME_RESET -> "Home reset"
            DataDecoder.Companion.FlyMode.CRUISE3D -> "3D Cruise"
            DataDecoder.Companion.FlyMode.ALTHOLD -> "Alt hold"
            DataDecoder.Companion.FlyMode.ERROR -> "!ERROR!"
            DataDecoder.Companion.FlyMode.WAIT -> "GPS wait"
            DataDecoder.Companion.FlyMode.CIRCLE -> "Circle"
            DataDecoder.Companion.FlyMode.STABILIZE -> "Stabilize"
            DataDecoder.Companion.FlyMode.TRAINING -> "Training"
            DataDecoder.Companion.FlyMode.FBWA -> "FBWA"
            DataDecoder.Companion.FlyMode.FBWB -> "FBWB"
            DataDecoder.Companion.FlyMode.AUTOTUNE -> "Autotune"
            DataDecoder.Companion.FlyMode.LOITER -> "Loiter"
            DataDecoder.Companion.FlyMode.TAKEOFF -> "Takeoff"
            DataDecoder.Companion.FlyMode.AVOID_ADSB -> "AVOID_ADSB"
            DataDecoder.Companion.FlyMode.GUIDED -> "Guided"
            DataDecoder.Companion.FlyMode.INITIALISING -> "Initializing"
            DataDecoder.Companion.FlyMode.LANDING -> "Landing"
            DataDecoder.Companion.FlyMode.MISSION -> "Mission"
            DataDecoder.Companion.FlyMode.QSTABILIZE -> "QSTABILIZE"
            DataDecoder.Companion.FlyMode.QHOVER -> "QHOVER"
            DataDecoder.Companion.FlyMode.QLOITER -> "QLOITER"
            DataDecoder.Companion.FlyMode.QLAND -> "QLAND"
            DataDecoder.Companion.FlyMode.QRTL -> "QRTL"
            DataDecoder.Companion.FlyMode.QAUTOTUNE -> "QAUTOTUNE"
            DataDecoder.Companion.FlyMode.QACRO -> "QACRO"
            DataDecoder.Companion.FlyMode.AUTONOMOUS -> "Autonomous"
            DataDecoder.Companion.FlyMode.GEO -> "Geo"
            DataDecoder.Companion.FlyMode.TURTLE -> "Turtle"
            DataDecoder.Companion.FlyMode.RATE -> "Rate"
            DataDecoder.Companion.FlyMode.ANGLE_HOLD -> "Angle Hold"
            null -> null
        }

    /** The link's standing, in the mode row: up, gone, or never yet. */
    fun showConnected() {
        mode.text = "Connected"
    }

    fun showDisconnected() {
        mode.text = "Disconnected"
    }

    override fun onSuccessDecode() {
        sensorTimeoutManager.onSuccessDecode()
    }

    // ----------------------------------------------------------------- height

    /**
     * The height on screen, written once however many arrive.
     *
     * Seeking a replay backwards replays the log from its beginning, and every
     * height in it now comes through — which is what the flight needs. A screen
     * only needs the last of them: posting each one across to the other thread
     * to be laid out and drawn is thousands of pieces of work for one line of
     * text, and it is felt as a rewind that drags.
     */
    @Volatile private var altitudeShown = Float.NaN
    @Volatile private var altitudeMslShown = Float.NaN
    @Volatile private var altitudePosted = false

    fun showAltitude(metres: Float, msl: Boolean) {
        if (msl) altitudeMslShown = metres else altitudeShown = metres
        if (altitudePosted) return
        altitudePosted = true
        activity.runOnUiThread {
            altitudePosted = false
            if (!altitudeShown.isNaN()) altitude.text = formatHeight(altitudeShown)
            if (!altitudeMslShown.isNaN()) altitudeMsl.text = formatHeight(altitudeMslShown)
        }
    }

    // ---------------------------------------------------------------- battery

    private var lastCellVoltage = 0f
    @Volatile private var detectedCells = 0
    @Volatile private var highestPackVoltage = 0f
    private var cellsAsked = false
    private var cellsAnswered = false

    override fun onVBATData(voltage: Float) {
        sensorTimeoutManager.onVBATData(voltage)
        activity.runOnUiThread {
            this.voltage.text = "${"%.2f".format(voltage)} V"
        }
    }

    override fun onCellVoltageData(voltage: Float) {
        lastCellVoltage = voltage
        activity.runOnUiThread {
            cellVoltage.text = "${"%.2f".format(voltage)} V"
        }
    }

    // The reported value is either the whole pack or a single cell, depending on
    // how the flight controller is set up (report_cell_voltage). Work out the
    // other one from the cell count, which is either set by hand or taken from
    // the first sensible pack reading.
    /**
     * How many cells the pack has, when the flight controller reports the whole
     * pack and the setting is left on Auto.
     *
     * One reading cannot always settle this: 21.0V is a full 5S or a half used
     * 6S, and both are ordinary. So the best guess is made and shown straight
     * away, and where two sizes people actually fly are both plausible, the
     * question is put to the one person who knows — once, without stopping
     * anything, with the safer of the two in use until it is answered.
     */
    private fun cellCount(packVoltage: Float): Int {
        val setting = preferenceManager.getBatteryCells()
        if (setting != "auto") {
            return setting.toIntOrNull() ?: 1
        }

        // Deliberately nothing here that revisits the count when the volts per
        // cell look too low. That would fire on the one reading which must
        // never lie: a pack being run into the ground. A 6S at 2.4V a cell is
        // 14.4V, which on its own looks like a 4S and would then read a healthy
        // 3.60V a cell on a battery destroying itself. Holding the count keeps
        // the number falling, which is the truth.
        //
        // The cost is a smaller pack fitted without reconnecting: the count
        // stays high, the reading reads low, which is visible and harmless, and
        // connecting again clears it.
        if (cellsAnswered) return detectedCells

        if (packVoltage > 2f && packVoltage > highestPackVoltage) {
            highestPackVoltage = packVoltage

            // Sizes that would make this a plausible pack as connected. The top
            // of the range is where 4.35V belongs — the most a cell can hold,
            // and exactly what a high voltage cell is charged to, so a hair
            // above it keeps LiHV packs on the right count instead of one too
            // many. As a *divisor* that same 4.35 is what read a 6S at 3.6V a
            // cell as a fully charged 5S.
            //
            // Rare sizes are left out so their neighbours do not muddy the
            // question; they can still be set by hand.
            val plausible = intArrayOf(1, 2, 3, 4, 5, 6, 8, 12, 16)
                .filter { packVoltage / it in 3.5f..4.4f }

            if (plausible.size > 1) {
                // The larger count reads lower volts per cell, which is the safe
                // way to be wrong while waiting for an answer.
                val safest = plausible.max() ?: plausible[0]
                if (safest > detectedCells) detectedCells = safest
                if (!cellsAsked && !replaying()) {
                    cellsAsked = true
                    askCellCount(packVoltage, plausible)
                }
            } else if (plausible.size == 1) {
                if (plausible[0] > detectedCells) detectedCells = plausible[0]
            } else {
                // Nothing plausible: the pack is well used, or these are high
                // voltage cells. Divide by the most a cell can hold and round up
                // to a real size — erring towards more cells, and so towards a
                // lower reading.
                var cells = Math.ceil((packVoltage / 4.25f).toDouble()).toInt()
                for (common in intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16)) {
                    if (common >= cells) {
                        cells = common
                        break
                    }
                }
                if (cells < 1) cells = 1
                if (cells > 16) cells = 16
                if (cells > detectedCells) detectedCells = cells
            }
        }
        return if (detectedCells > 0) detectedCells else 1
    }

    private fun askCellCount(packVoltage: Float, options: List<Int>) {
        val labels = options
            .map { it.toString() + "S — " + "%.2f".format(packVoltage / it) + " V per cell" }
            .toTypedArray()
        // The title carries the voltage rather than a message: an AlertDialog
        // shows either a message or a list, never both, and the message won —
        // so the dialog appeared with nothing in it to choose.
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle("%.1f".format(packVoltage) + " V — which battery?")
                .setItems(labels) { d, which ->
                    detectedCells = options[which]
                    cellsAnswered = true
                    d.dismiss()
                }
                .create()
        )
    }

    override fun onVBATOrCellData(voltage: Float) {
        activity.runOnUiThread {
            if (preferenceManager.getReportVoltage() == "Battery") {
                // reported value is the pack
                sensorTimeoutManager.onVBATData(voltage)
                this.voltage.text = "${"%.2f".format(voltage)} V"

                val cells = cellCount(voltage)
                if (cells > 0) {
                    val perCell = voltage / cells
                    sensorTimeoutManager.onCellVoltageData(perCell)
                    cellVoltage.text = "${"%.2f".format(perCell)} V"
                    lastCellVoltage = perCell
                }
            } else {
                // reported value is one cell
                sensorTimeoutManager.onCellVoltageData(voltage)
                cellVoltage.text = "${"%.2f".format(voltage)} V"
                lastCellVoltage = voltage

                // The pack figure is only shown when the user has said how many
                // cells there are, because multiplying by a guessed count would
                // be inventing a number. Without this the voltage widget — the
                // one on screen by default — simply stayed blank for anyone
                // whose flight controller reports per cell.
                val setting = preferenceManager.getBatteryCells()
                val cells = if (setting == "auto") 0 else (setting.toIntOrNull() ?: 0)
                if (cells > 0) {
                    val pack = voltage * cells
                    sensorTimeoutManager.onVBATData(pack)
                    this.voltage.text = "${"%.2f".format(pack)} V"
                }
            }
        }
    }

    override fun onFuelData(fuel: Int) {
        sensorTimeoutManager.onFuelData(fuel)
        activity.runOnUiThread {
            val batteryUnits = preferenceManager.getBatteryUnits()
            var percentage = fuel

            when (batteryUnits) {
                "mAh", "mWh" -> {
                    this.fuel.text = formatPower(fuel, batteryUnits)
                    //for icon, calculate percentage from cell voltage if available
                    if ((lastCellVoltage > 0) && (lastCellVoltage <= 4.4)) {
                        percentage = ((1 - (4.2f - lastCellVoltage)).coerceIn(0f, 1f) * 100).toInt()
                    } else {
                        percentage = -1  //unknnow icon
                    }
                }
                "Percentage" -> {
                    this.fuel.text = "$fuel%"
                }
            }

            setIcon(this.fuel, fuelIconRes(percentage))
        }
    }

    // ------------------------------------------------------- CRSF rate system

    /**
     * Which radio system is on the other end, learned from the name it reports
     * in a CRSF DEVICE_INFO frame.
     *
     * It matters because rf_mode is a CRSF field that ExpressLRS, Crossfire and
     * Tracer all send and all number differently: mode 2 is 50 Hz on ExpressLRS
     * and 150 Hz on a Crossfire. Showing one system's table for another is not
     * a cosmetic problem — it is the wrong number.
     */
    private var crsfSystem: String? = null

    /**
     * A manual fallback for the CRSF system, for links whose module name never
     * identifies it — a Bluetooth telemetry mirror carries no device-name frame,
     * so a Crossfire link is read under the ExpressLRS table. Long-press the rate
     * tile to set it. A real name always wins over it, so a properly-identified
     * link (WebSocket, USB) is never mislabelled by a stale override.
     */
    private var crsfSystemOverride: String? = preferenceManager.getCrsfSystemOverride()
    private fun effectiveCrsfSystem(): String? = crsfSystem ?: crsfSystemOverride

    /** The last RF mode seen, so the reading can be redrawn if the system changes. */
    private var lastRfMode: Int? = null

    override fun onDeviceName(name: String) {
        val lower = name.lowercase(java.util.Locale.US)
        val system = when {
            lower.contains("tracer") -> "TRACER"
            lower.contains("elrs") || lower.contains("expresslrs") -> "ELRS"
            // TBS names its Crossfire hardware "XF ..." — "XF Micro TX", "XF WiFi"
            lower.startsWith("xf ") || lower.contains("crossfire") -> "XF"
            else -> null
        }
        if (system != null && system != crsfSystem) {
            crsfSystem = system
            // The name arrives after the link is already up, so a rate may
            // already be on screen — under the wrong system's table and beside
            // the wrong mark. Both are redone now that the system is known.
            activity.runOnUiThread {
                applyRateIcon()
                lastRfMode?.let { renderRate(it) }
            }
        }
    }

    /**
     * Crossfire and Tracer RF modes, from ArduPilot's table: 4, 50, 150, 250 Hz.
     * ExpressLRS keeps its own, longer list, which is what the labels below are.
     */
    private fun crossfireRate(mode: Int): String {
        val rates = intArrayOf(4, 50, 150, 250)
        return if (mode in rates.indices) rates[mode].toString() + "Hz" else mode.toString()
    }

    /**
     * Long-press the rate tile to say which CRSF system the link is, for when the
     * name never did — a Crossfire over Bluetooth reads under the ExpressLRS table
     * otherwise, and mode 2 is 50 Hz there but 150 Hz on a Crossfire. Auto hands it
     * back to the module name; the choice is remembered.
     */
    private fun showCrsfSystemDialog() {
        val labels = arrayOf("Auto (from name)", "ExpressLRS", "Crossfire", "Tracer")
        val values = arrayOf<String?>(null, "ELRS", "XF", "TRACER")
        val current = values.indexOf(crsfSystemOverride).let { if (it < 0) 0 else it }
        // A named link decides for itself, so on one this choice serves only
        // future nameless links — said above the list, or the dialog shows a
        // checked ExpressLRS beside a tile reading Crossfire and looks broken.
        // It rides a custom title view because an AlertDialog shows either a
        // message or a list, never both, and the stock title clips at two
        // lines.
        val spoken = when (crsfSystem) {
            "ELRS" -> "ExpressLRS"
            "XF" -> "Crossfire"
            "TRACER" -> "Tracer"
            else -> null
        }
        val builder = AlertDialog.Builder(activity)
        if (spoken == null) {
            builder.setTitle("Rate system")
        } else {
            val caveat = TextView(activity)
            caveat.text = "Rate system\n\nThis link says it is a $spoken, and " +
                "the name decides. The choice below serves links that send " +
                "no name."
            caveat.textSize = 16f
            val d = activity.resources.displayMetrics.density
            caveat.setPadding(
                (24 * d).toInt(), (18 * d).toInt(), (24 * d).toInt(), (4 * d).toInt())
            builder.setCustomTitle(caveat)
        }
        showDialog(
            builder
                .setSingleChoiceItems(labels, current) { d, which ->
                    crsfSystemOverride = values[which]
                    preferenceManager.setCrsfSystemOverride(crsfSystemOverride)
                    applyRateIcon()
                    lastRfMode?.let { renderRate(it) }
                    d.dismiss()
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .create()
        )
    }

    override fun onElrsModeModeData(mode: Int) {
        sensorTimeoutManager.onElrsModeModeData(mode)
        lastRfMode = mode
        activity.runOnUiThread { renderRate(mode) }
    }

    private fun renderRate(mode: Int) {
        if (linkProtocol() == "GHST") {
            elrsRate.text = GHST_RF_PROFILES.getOrNull(mode) ?: mode.toString()
            return
        }
        // A Crossfire or Tracer numbers these differently, so use its own table.
        // The icon beside it is what says which system it is, so the number does
        // not repeat it — there is little enough room on the bar as it is.
        val system = effectiveCrsfSystem()
        if (system == "XF" || system == "TRACER") {
            elrsRate.text = crossfireRate(mode)
            return
        }
        when (mode) {
            13 -> elrsRate.text = "F1000"
            12 -> elrsRate.text = "F500"
            11 -> elrsRate.text = "D500"
            10 -> elrsRate.text = "D250"
            9 -> elrsRate.text = "L500"
            8 -> elrsRate.text = "L333c" //8ch
            7 -> elrsRate.text = "L250"
            6 -> elrsRate.text = "L200"
            5 -> elrsRate.text = "L150"
            4 -> elrsRate.text = "L100"
            3 -> elrsRate.text = "L100c"  //8ch
            2 -> elrsRate.text = "L50"
            1 -> elrsRate.text = "L25"
            0 -> elrsRate.text = "L4"
            else -> elrsRate.text = mode.toString()
        }
    }

    /**
     * The mark shown beside the rate: whose numbering the reading follows.
     *
     * GHST and ExpressLRS already had one each. A Crossfire needs its own,
     * because the same mode number means a different rate on each system and
     * the icon is the fastest way to see which table is in use.
     */
    private fun rateIconRes(): Int {
        val system = effectiveCrsfSystem()
        return when {
            linkProtocol() == "GHST" -> R.drawable.ic_ghst_rate
            system == "XF" || system == "TRACER" -> R.drawable.ic_xf_rate
            else -> R.drawable.ic_elrs_rate
        }
    }

    private fun applyRateIcon() {
        val icon = ContextCompat.getDrawable(activity, rateIconRes())
        // keep the icon on the side the current layout puts it on
        if (elrsRate.compoundDrawablesRelative[1] != null) {
            elrsRate.setCompoundDrawablesRelativeWithIntrinsicBounds(null, icon, null, null)
        } else {
            elrsRate.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
        }
    }

    override fun onProtocolDetected(protocolName: String) {
        // Rendering only — the fact itself, and the toast that announces a
        // change of it, are the activity's.
        activity.runOnUiThread {
            // what the link turned out to speak, in the row with the rest
            // of what it is saying
            protocolView.text = ProtocolFactory.shortNameOf(protocolName)
            applyRateIcon()
        }
    }

    // ------------------------------------------------------- timeout greying

    override fun onSensorTimeout(sensorId: Int) {
        activity.runOnUiThread {
            updateSensorGreyed(sensorId)
        }
    }

    override fun onSensorData(sensorId: Int) {
        activity.runOnUiThread {
            updateSensorGreyed(sensorId)
        }
    }

    override fun onTelemetryRate(rate: Int) {
        activity.runOnUiThread {
            if (rate < 1000) {
                tlmRate.text = "${rate} b/s"
            } else {
                tlmRate.text = "${"%.1f".format(rate / 1000f)} kb/s"
            }
        }
    }

    private fun updateSensorGreyed(sensorId: Int) {
        var alpha = 1f
        if (sensorTimeoutManager.getSensorTimeout(sensorId)) alpha = 0.5f
        when (sensorId) {
            SensorTimeoutManager.SENSOR_GPS -> {
                satellites.alpha = alpha
                traveledDistance.alpha = alpha
            }
            SensorTimeoutManager.SENSOR_DISTANCE -> distance.alpha = alpha
            SensorTimeoutManager.SENSOR_ALTITUDE -> altitude.alpha = alpha
            SensorTimeoutManager.SENSOR_GPS_ALTITUDE -> altitudeMsl.alpha = alpha
            SensorTimeoutManager.SENSOR_RSSI -> rssi.alpha = alpha
            SensorTimeoutManager.SENSOR_UP_LQ -> upLq.alpha = alpha
            SensorTimeoutManager.SENSOR_DN_LQ -> dnLq.alpha = alpha
            SensorTimeoutManager.SENSOR_ELRS_MODE -> elrsRate.alpha = alpha
            SensorTimeoutManager.SENSOR_VOLTAGE -> voltage.alpha = alpha
            SensorTimeoutManager.SENSOR_CELL_VOLTAGE -> cellVoltage.alpha = alpha
            SensorTimeoutManager.SENSOR_CURRENT -> current.alpha = alpha
            SensorTimeoutManager.SENSOR_SPEED -> speed.alpha = alpha
            SensorTimeoutManager.SENSOR_AIRSPEED -> airspeed.alpha = alpha
            SensorTimeoutManager.SENSOR_VSPEED -> vspeed.alpha = alpha
            SensorTimeoutManager.SENSOR_THROTTLE -> throttle.alpha = alpha
            SensorTimeoutManager.SENSOR_FUEL -> fuel.alpha = alpha
            SensorTimeoutManager.SENSOR_RC_CHANNELS -> rcWidget.alpha = alpha
            SensorTimeoutManager.SENSOR_STATUSTEXT -> {
                if (sensorTimeoutManager.getSensorTimeout(sensorId)) {
                    statustext.text = ""
                }
            }
            SensorTimeoutManager.SENSOR_DN_SNR -> dnSnr.alpha = alpha
            SensorTimeoutManager.SENSOR_UP_SNR -> upSnr.alpha = alpha
            SensorTimeoutManager.SENSOR_ANT -> ant.alpha = alpha
            SensorTimeoutManager.SENSOR_POWER -> power.alpha = alpha
            SensorTimeoutManager.SENSOR_RSSI_DBM_1 -> rssiDbm1.alpha = alpha
            SensorTimeoutManager.SENSOR_RSSI_DBM_2 -> rssiDbm2.alpha = alpha
            SensorTimeoutManager.SENSOR_RSSI_DBM_D -> rssiDbmd.alpha = alpha
        }
    }

    // ------------------------------------------------------- named UI-thread

    /** The travelled reading, from the flight the activity measures. UI thread. */
    fun showTraveledDistance(metres: Double) {
        traveledDistance.text = formatDistance(metres.toFloat())
    }

    /** A fresh link has travelled nowhere yet. UI thread. */
    fun showNoTraveledDistance() {
        traveledDistance.text = "-"
    }

    /** The phone's own battery, from the activity's receiver. UI thread. */
    fun showPhoneBattery(percent: Int) {
        phoneBattery.text = "$percent%"
    }

    /** Every tile back to its resting face. UI thread. */
    fun reset() {
        mode.text = "Disconnected"
        satellites.text = "0"
        rssi.text = "-"
        setIcon(rssi, rssiIconRes(100))
        voltage.text = "-"
        phoneBattery.text = "-"
        current.text = "-"
        fuel.text = "-"
        setIcon(fuel, fuelIconRes(-1))
        altitude.text = "-"
        altitudeMsl.text = "-"
        speed.text = "-"
        airspeed.text = "-"
        vspeed.text = "-"
        distance.text = "-"
        traveledDistance.text = "0 m"
        statustext.text = ""
        dnSnr.text = "-"
        upSnr.text = "-"
        dnLq.text = "-"
        elrsRate.text = "-"
        setIcon(dnLq, dnLqIconRes(100))
        upLq.text = "-"
        setIcon(upLq, upLqIconRes(100))
        ant.text = "-"
        power.text = "-"
        rssiDbm1.text = "-"
        setIcon(rssiDbm1, rssiDbm1IconRes(0))
        rssiDbm2.text = "-"
        setIcon(rssiDbm2, rssiDbm2IconRes(0))
        rssiDbmd.text = "-"
        setIcon(rssiDbmd, rssiDbmdIconRes(0))
        cellVoltage.text = "-"
        lastCellVoltage = 0.0f
        throttle.text = "-"
        protocolView.text = "-"
        tlmRate.text = "0 b/s"
    }

    /** The cell question starts over — with any new link or replay. */
    fun forgetCells() {
        detectedCells = 0
        highestPackVoltage = 0f
        cellsAsked = false
        cellsAnswered = false
    }

    /**
     * The rate system a name earned belongs to the link or replay that said
     * it. A replayed Crossfire log latches "XF" here exactly as a live link
     * does — right for that replay, poison for the next one, whose ELRS
     * rates would read under the Crossfire table. Forgotten wherever a new
     * link or replay begins.
     */
    fun forgetLinkName() {
        crsfSystem = null
        // else the next link would redraw the old rate under its own table
        lastRfMode = null
    }

    /**
     * A connection asked for by hand starts over: the cell question, the rate
     * system a name earned, and the protocol row all belonged to the last
     * link. A reconnect does not come through here, so a link coming back
     * keeps the name it earned.
     */
    fun newLink() {
        forgetCells()
        forgetLinkName()
        // or the row would name the last link until the new one had said two
        // valid frames — long enough to read, and wrong
        protocolView.text = "-"
    }

    // ------------------------------------------------------------- lifecycle

    fun resume() = sensorTimeoutManager.resume()
    fun pause() = sensorTimeoutManager.pause()
    fun setTimeoutWindow(ms: Int) = sensorTimeoutManager.setTimeoutWindow(ms)

    /** A replay's readings never time out, and its rate row means nothing. */
    fun enterReplay() {
        sensorTimeoutManager.disableTimeouts()
        tlmRate.alpha = 0.5f
    }

    fun leaveReplay() {
        sensorTimeoutManager.enableTimeouts()
        // a high-latency link widened this; whatever connects next starts
        // from the ordinary window
        sensorTimeoutManager.setTimeoutWindow(SensorTimeoutManager.DEFAULT_TIMEOUT_MS)
        tlmRate.alpha = 1.0f
    }
}
