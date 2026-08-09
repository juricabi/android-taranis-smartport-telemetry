package juricabi.com.telemetry.ui

import android.app.Activity
import android.app.PendingIntent
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.ActivityInfo
import android.location.Location
import android.location.LocationManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.net.Uri
import android.os.*
import android.text.Html
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.nex3z.flowlayout.FlowLayout
import juricabi.com.telemetry.R
import juricabi.com.telemetry.converter.Converter
import juricabi.com.telemetry.manager.FlightPlanManager
import juricabi.com.telemetry.protocol.GhstProtocol
import juricabi.com.telemetry.manager.Fr24Manager
import juricabi.com.telemetry.manager.PreferenceManager
import juricabi.com.telemetry.manager.SensorTimeoutManager
import juricabi.com.telemetry.maps.MapLine
import juricabi.com.telemetry.maps.MapMarker
import juricabi.com.telemetry.maps.LineWeights
import juricabi.com.telemetry.maps.MapWrapper
import juricabi.com.telemetry.maps.Position
import juricabi.com.telemetry.maps.maplibre.MapLibreMapWrapper
import juricabi.com.telemetry.maps.maplibre.MapLibreStyles
import org.maplibre.android.MapLibre
import juricabi.com.telemetry.utils.GeoUtils
import juricabi.com.telemetry.utils.PlusCode
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import juricabi.com.telemetry.protocol.pollers.NetworkDataPoller
import juricabi.com.telemetry.protocol.pollers.LogPlayer
import juricabi.com.telemetry.utils.LocalNetworks
import juricabi.com.telemetry.utils.WifiNetworkBinder
import juricabi.com.telemetry.service.DataService
import uk.co.deanwild.materialshowcaseview.IShowcaseListener
import uk.co.deanwild.materialshowcaseview.MaterialShowcaseView
import juricabi.com.telemetry.logger.OperatorTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.ceil
import kotlin.math.roundToInt

//class MapsActivity : AppCompatActivity(), DataDecoder.Listener {
class MapsActivity : androidx.appcompat.app.AppCompatActivity(), DataDecoder.Listener, SensorTimeoutManager.Listener, Fr24Manager.Listener {

    @Volatile private var detectedProtocol: String = ""
    /**
     * The flight as flown: position with height above sea level, which is what
     * the profile and the 3D view need and neither the map nor the polyline
     * keeps. Bounded, so a long session cannot grow without end.
     */
    private val flightPath: List<juricabi.com.telemetry.gl.TerrainScene.TrackPoint>
        get() = juricabi.com.telemetry.gl.LiveFlightPath.snapshot()
    private val flightAltitude = juricabi.com.telemetry.gl.FlightAltitude()

    @Volatile private var detectedCells = 0
    @Volatile private var highestPackVoltage = 0f
    private var cellsAsked = false
    private var cellsAnswered = false

    companion object {

        // Ghost RF profiles, matching EdgeTX ghstRfProfileValue
        private val GHST_RF_PROFILES = arrayOf(
            "Auto", "Norm", "Race", "Pure", "Long", "Unused", "Race2", "Pure2"
        )
        /**
         * The speed slider's stops: 3x slower to 10x faster, in halves both
         * ways. One list, because the two sides mean different arithmetic —
         * a stop of 2.5 on the slow side is 1/2.5, not 0.4 met somewhere.
         */
        private val SPEED_STOPS = FloatArray(23) { i ->
            if (i < 4) 1f / (3f - 0.5f * i) else 1f + 0.5f * (i - 4)
        }
        private const val SPEED_STOP_AS_FLOWN = 4

        /**
         * The least a sped-up replay may be squeezed into. A long flight at
         * ten times is fine; ten times a minute of flight is a blur nobody
         * can follow — never quicker than this, unless the flight itself was
         * shorter, which plays as flown.
         */
        private const val REPLAY_FLOOR_SECONDS = 10

        /**
         * Packets a second a telemetry link broadly runs at, standing in for
         * the clock a recording did not keep. Links range from tens to over a
         * hundred, so this is a pace to start from, not a measurement — the
         * speed slider is the correction.
         */
        private const val TELEMETRY_PACKETS_PER_SECOND = 50
        private const val REQUEST_ENABLE_BT: Int = 0
        private const val REQUEST_LOCATION_PERMISSION: Int = 1
        private const val REQUEST_WRITE_PERMISSION: Int = 2
        private const val REQUEST_READ_PERMISSION: Int = 3
        private const val ACTION_USB_DEVICE = "action_usb_device"
        private val MAP_TYPE_ITEMS = arrayOf(
            "OpenStreetMap (can be cached)",
            "OpenTopoMap (can be cached)",
            "Satellite - ESRI (can be cached)",
            "Satellite + Streets - ESRI (can be cached)",
            "3D terrain (can be cached)"
        )

        /** The entry that opens the 3D screen instead of changing the map. */
        private const val ITEM_3D = 4




        // zoom used when jumping to a position; 18 is the deepest real satellite level
        private const val LOCATE_ZOOM = 18f

        private const val CONNTYPE_NONE = 0
        private const val CONNTYPE_BT = 1
        private const val CONNTYPE_BLE = 2
        private const val CONNTYPE_USB = 3
        private const val CONNTYPE_NET = 4
    }

    enum class RequestWritePermissionSequenceType {
        NONE, CONNECT, RENAME, DELETE, LOG_PICKER, EXPORT_GPX, EXPORT_KML
    }

    private var map: MapWrapper? = null

    private var soundPool: SoundPool? = null
    private var connectedSoundId: Int = 0
    private var disconnectedSoundId: Int = 0
    private var connectionFailedSoundId: Int = 0
    private var reconnectingSoundId : Int = 0

    private var marker: MapMarker? = null
    private var polyLine: MapLine? = null
    private var fr24Manager: Fr24Manager? = null
    private val airplaneMarkers = mutableMapOf<Int, MapMarker>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var headingPolyline: MapLine? = null
    private var flightPlanLines: MutableList<MapLine> = mutableListOf()
    private var homeLine: MapLine? = null

    /** Replay only: the line to where this phone is now, not then. */
    private var operatorLine: MapLine? = null
    private var flightHeadLine: MapLine? = null
    /** The fix being believed, so a worse one cannot take its place. */
    @Volatile private var bestPhoneFix: Location? = null

    /** Cached here for whichever of the two views is currently being drawn. */
    @Volatile private var phoneHeading = Float.NaN
    private var phoneWatchWanted = false

    private fun onPhoneHeading(degrees: Float) {
        phoneHeading = degrees
        // Not over a replay unless its menu also asks for the live phone.
        if (showLiveArrow()) {
            map?.setPhoneBearing(degrees)
            terrain3D?.setMyHeading(degrees)
        }
    }

    private fun setPhoneWatch(wanted: Boolean) {
        phoneWatchWanted = wanted
        val service = dataService ?: return
        if (wanted) {
            service.watchPhone(
                { fix -> onPhoneFix(fix) },
                { heading -> onPhoneHeading(heading) }
            )
        } else {
            service.watchPhone(null, null)
        }
    }

    /**
     * Whether where this phone is now is worth drawing.
     *
     * Always, except over a replay, where it is a choice: the flight may have
     * been recorded a hundred kilometres from where it is being watched, and
     * the arrow is then either the useful part of the picture or a distraction
     * at the far edge of it.
     */
    private fun showLiveArrow(): Boolean =
        !isInReplayMode() || preferenceManager.isLiveShownInReplay()

    /**
     * What the screen knows about this phone, onto everything that draws it.
     *
     * The map and the ground view are both fed by fixes arriving, which is
     * fine while they arrive — but a view built between two of them, or an
     * arrow switched back on between two of them, would have waited with
     * nothing drawn until the next one came round.
     */
    private fun tellViewsWhereIAm() {
        // One answer for both views: the freshest fix this screen has heard,
        // or the system's last known place until it hears one. These used to
        // be two roads — the map fell back to the last known place, the
        // ground view waited for a live fix — and whichever view had been
        // built most recently disagreed with the other about whether the
        // phone existed at all.
        val fix = bestPhoneFix ?: myLastKnownFix() ?: return
        if (!showLiveArrow()) return
        map?.setPhoneLocation(
            Position(fix.latitude, fix.longitude),
            if (fix.hasAccuracy()) fix.accuracy else Float.NaN
        )
        // NaN is meaningful: draw a dot rather than retaining an old arrow.
        map?.setPhoneBearing(phoneHeading)
        terrain3D?.setMyPosition(fix.latitude, fix.longitude,
            if (fix.hasAccuracy()) fix.accuracy else 0f)
        terrain3D?.setMyHeading(phoneHeading)
    }

    /**
     * A fix, from the service, which is the only thing here listening for one.
     *
     * The service hears the satellites for the recording's sake and goes on
     * doing it while this screen is away; a second listener here answered the
     * same question a little differently, and drew the arrow somewhere the
     * recording did not agree with.
     */
    private fun onPhoneFix(location: Location) {
        bestPhoneFix = location
        // kept for the 3D view, which draws the same accuracy circle the map does
        phoneAccuracy = if (location.hasAccuracy()) location.accuracy else 0f
        updateHomeLine()
        // not over a replay, which is drawing where the phone was then
        if (showLiveArrow()) {
            tellViewsWhereIAm()
        } else {
            terrain3D?.hideMyLocation()
        }
    }

    @Volatile private var phoneAccuracy = 0f
    private var terrain3D: Terrain3DView? = null

    /**
     * The 3D world, kept warm behind the map. Rebuilding it on every view
     * switch cost twenty seconds of rippling for a world whose every
     * picture was already on disk — three times in one four-minute field
     * session. Parked, it keeps its meshes and its settled quadtree in
     * heap; the GL textures die with the context on detach and come back
     * from the disk cache in a couple of seconds. Discarded wherever a
     * new flight begins, because a world belongs to its flight.
     */
    private var parked3D: Terrain3DView? = null

    private var lastPitch = 0f
    private var lastRoll = 0f

    private lateinit var connectButton: Button
    private lateinit var replayButton: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: FloatingActionButton
    private lateinit var fuel: TextView
    private lateinit var rssi: TextView
    private lateinit var satellites: TextView
    private lateinit var current: TextView
    private lateinit var voltage: TextView
    private lateinit var phoneBattery: TextView
    private lateinit var speed: TextView
    private lateinit var airspeed: TextView
    private lateinit var vspeed: TextView
    private lateinit var distance: TextView
    private lateinit var traveled_distance: TextView
    private lateinit var altitude: TextView
    private lateinit var altitude_msl: TextView
    private lateinit var mode: TextView
    private lateinit var statustext: TextView
    private lateinit var followButton: FloatingActionButton
    private lateinit var chaseButton: FloatingActionButton
    private lateinit var mapTypeButton: FloatingActionButton
    private lateinit var northUpButton: FloatingActionButton
    private lateinit var myLocationButton: FloatingActionButton
    private lateinit var findQuadButton: FloatingActionButton
    private lateinit var fullscreenButton: ImageView
    private lateinit var menuButton: FloatingActionButton
    private lateinit var settingsButton: ImageView
    private lateinit var topLayout: RelativeLayout
    private lateinit var bottomLayout: RelativeLayout
    private lateinit var horizonView: HorizonView
    private lateinit var topList: FlowLayout
    private lateinit var bottomList: FlowLayout
    private lateinit var rootLayout: CoordinatorLayout
    private lateinit var compassHeading: TextViewOutline
    private lateinit var loadingGrid: LoadingGrid
    private lateinit var clock_text: TextViewOutline
    private lateinit var mapHolder: FrameLayout
    private lateinit var mapViewHolder: FrameLayout
    private lateinit var rc_widget: RCWidget
    private lateinit var dnSnr: TextView
    private lateinit var upSnr: TextView
    private lateinit var upLq: TextView
    private lateinit var dnLq: TextView
    private lateinit var elrsRate: TextView
    private lateinit var ant: TextView
    private lateinit var power: TextView
    private lateinit var rssiDbm1: TextView
    private lateinit var rssiDbm2: TextView
    private lateinit var rssiDbmd: TextView
    private lateinit var cell_voltage: TextView
    private lateinit var throttle: TextView
    private lateinit var tlmRate: TextView

    private lateinit var sensorViewMap: HashMap<String, View>
    private lateinit var sensorsConverters: HashMap<String, Converter>

    private lateinit var preferenceManager: PreferenceManager

    private var mapType = MapLibreStyles.MAP_TYPE_DEFAULT

    private var lastGPS = Position(0.0, 0.0)

    // Where the model was last seen, kept across a disconnect on purpose.
    // lastGPS is cleared when the UI goes idle, which is exactly the moment
    // this matters most: a link that drops because the model went down is when
    // someone needs to be told where it was.
    private var lastKnownGPS: Position? = null

    /** Where the course over the ground is measured from, for links with no heading. */
    private var lastCourseFrom = Position(0.0, 0.0)

    /** The bearing from one place to another, in compass degrees. */
    private fun courseOverGround(fromLat: Double, fromLon: Double,
                                 toLat: Double, toLon: Double): Float {
        val east = (toLon - fromLon) * Math.cos(Math.toRadians((fromLat + toLat) / 2))
        val north = toLat - fromLat
        if (east == 0.0 && north == 0.0) return lastHeading
        return ((Math.toDegrees(Math.atan2(east, north)).toFloat() % 360f) + 360f) % 360f
    }
    private var lastKnownGPSAt: Long = 0L
    private var lastHeading = 0f

    /** How much of a reported turn the model takes up on each drawn frame. */
    private val HEADING_EASE = 0.18f

    private var followMode = true
    private var chaseMode = false
    private var hasGPSFix = false
    private var replayFileString: String? = null

    /**
     * A replay held back until there is ground to watch it over.
     *
     * A log starts playing the moment it has finished decoding, and the ground
     * it happened over takes a few seconds the first time it is fetched —
     * sixty-four pictures for each tile of it. So the flight raced across bare
     * mesh while the terrain came in behind it, and by the time there was
     * anything to see, most of it had already happened. Only ever the first
     * visit to a field: after that the pictures are on the phone and the wait
     * is nothing.
     */
    private var replayWaitingForGround = false

    /**
     * The flight's own record of the operator: where they stood, which way they
     * faced, how good the fix was, and the time on every row of it.
     *
     * A log is a recording of the bytes off the link and has nothing in it
     * about the person holding the phone, nor any clock — nothing here decodes
     * a time out of any protocol. Both come from the CSV recorded beside it,
     * and nowhere else: not the log's name, which a rename takes away, and not
     * the file's own dates, which say when it was last written and would put
     * the start of a flight at the end of it.
     *
     * A log with no CSV beside it says nothing about either, and is made to
     * say nothing: no clock, no arrow, no ring, no line home.
     */
    private var operatorTrack: OperatorTrack? = null
    private var recordedMe: Position? = null

    private val timeOfDayFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val flightDayFormat = SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.getDefault())

    /**
     * The clock of the day, for when nothing else is moving it.
     *
     * A replay drives the clock from the log itself, every time the position
     * changes; this is the live one, and the seconds are all it has to show.
     */
    private val clockTicker = object : Runnable {
        override fun run() {
            showTime()
            // and where the operator was standing: a replay standing paused
            // moves nothing, so a map or a view built while it stood there had
            // nothing to draw the arrow from until the flight moved again
            showOperator()
            clock_text.postDelayed(this, 1000)
        }
    }
    private var dataService: DataService? = null
    /** One bind request for this Activity, including while its callback is pending. */
    private var dataServiceBound = false
    private var lastPhoneBattery = 0
    private var lastTraveledDistance = 0.0
    private var lastCellVoltage = 0.0f

    private var fullscreenWindow = false

    private var gotHeading = false;

    /**
     * Whether ANY model heading exists — attitude or course over ground.
     * Chase turns nothing until one does: with no drone up, both views
     * faithfully chased the default zero, each in its own idiom — the map
     * snapped north-up and resisted, the ground view swung behind a
     * phantom — and the two read as different bugs.
     */
    private var modelHeadingKnown = false

    private var logPlayer : LogPlayer? = null;

    private var requestWritePermissionSequence = RequestWritePermissionSequenceType.NONE;

    private var lastFileDialogSelectionIndex = -1;
    private var lastFileDialogSelection = "";

    private var lastSelectedDataPooler = "";
    private var lastSelectedBluetoothDeviceAddress = "";
    private var lastSelectedBLEDeviceAddress = "";

    private var reconnectionStartTime = 0L;

    /**
     * Whether this disconnection was asked for.
     *
     * Both kinds arrive at [onDisconnected], and they mean opposite things: a
     * link that drops may be a model in a field, where the last place it was
     * seen is the one thing worth keeping; pressing Disconnect is a person
     * saying they are finished, and then the flight goes.
     */
    private var disconnectAsked = false
    private var lastConnectionType = CONNTYPE_NONE;
    private var lastBluetoothDevice: BluetoothDevice? = null;
    private var reconnectOnFailure = false;

    // what a network reconnect needs to repeat; there is no device object to
    // hold on to as there is for Bluetooth
    private var lastNetworkHost = ""
    private var lastNetworkPort = 0
    private var lastNetworkMode = 0
    private var lastNetworkHighLatency = false

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(p0: ComponentName?) {
            onDisconnected()
        }

        override fun onServiceConnected(p0: ComponentName?, p1: IBinder?) {
            dataService = (p1 as DataService.DataBinder).getService()
            dataService?.setDataListener(this@MapsActivity)
            dataService?.let {
                setPhoneWatch(phoneWatchWanted)
                if (it.isConnected()) {
                    switchToConnectedState()
                    redrawFlightLine()
                }
            }
        }
    }

    private val sensorTimeoutManager: SensorTimeoutManager = SensorTimeoutManager(this);

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        // diagnostics into the file "Copy debug info" copies, so a tester
        // away from the desk can send what logcat would have said — crashes too
        juricabi.com.telemetry.utils.DebugLog.init(applicationContext)

        preferenceManager = PreferenceManager(this)

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        soundPool = SoundPool(5, AudioManager.STREAM_NOTIFICATION, 0)
        connectedSoundId = soundPool!!.load(this, R.raw.connected, 1)
        disconnectedSoundId = soundPool!!.load(this, R.raw.disconnected, 1)
        connectionFailedSoundId = soundPool!!.load(this, R.raw.connection_failed, 1)
        reconnectingSoundId = soundPool!!.load(this, R.raw.reconnecting, 1)

        // A build along the way stored the 3D entry in here as though it were a
        // map type, and there is no tile source at that number: the map fell
        // through to the topo one and the choice looked forgotten. The 3D
        // choice keeps its own setting now, and anything unknown here is the
        // ordinary map again.
        mapType = preferenceManager.getMapType()
        if (mapType < MapLibreStyles.MAP_TYPE_DEFAULT ||
            mapType > MapLibreStyles.MAP_TYPE_SATELLITE_HYBRID) {
            mapType = MapLibreStyles.MAP_TYPE_DEFAULT
            preferenceManager.setMapType(mapType)
        }
        followMode = savedInstanceState?.getBoolean("follow_mode", true) ?: true
        detectedCells = savedInstanceState?.getInt("cells", 0) ?: 0
        cellsAnswered = savedInstanceState?.getBoolean("cells_answered", false) ?: false
        cellsAsked = savedInstanceState?.getBoolean("cells_asked", false) ?: false
        replayFileString = savedInstanceState?.getString("replay_file_name")
        fullscreenWindow = preferenceManager.isFullscreenWindow()

        lastSelectedDataPooler = preferenceManager.getLastSelectedDataPooler()
        lastSelectedBluetoothDeviceAddress = preferenceManager.getLastSelectedBluetoothDeviceAddress()
        lastSelectedBLEDeviceAddress = preferenceManager.getLastSelectedBLEDeviceAddress()

        rootLayout = findViewById(R.id.rootLayout)
        fuel = findViewById(R.id.fuel)
        rssi = findViewById(R.id.rssi)
        satellites = findViewById(R.id.satellites)
        topLayout = findViewById(R.id.top_layout)
        bottomLayout = findViewById(R.id.bottom_layout)
        connectButton = findViewById(R.id.connect_button)
        current = findViewById(R.id.current)
        voltage = findViewById(R.id.voltage)
        phoneBattery = findViewById(R.id.phone_battery)
        speed = findViewById(R.id.speed)
        airspeed = findViewById(R.id.airspeed)
        vspeed = findViewById(R.id.vspeed)
        distance = findViewById(R.id.distance)
        traveled_distance = findViewById(R.id.traveled_distance)
        altitude = findViewById(R.id.altitude)
        altitude_msl = findViewById(R.id.altitude_msl)
        mode = findViewById(R.id.mode)
        statustext = findViewById(R.id.statustext)
        followButton = findViewById(R.id.follow_button)
        chaseButton = findViewById(R.id.chase_button)
        chaseButton.imageAlpha = 128
        if (savedInstanceState?.getBoolean("chase_mode", false) == true) {
            setChaseMode(true)
        }
        mapTypeButton = findViewById(R.id.map_type_button)
        northUpButton = findViewById(R.id.north_up_button)
        compassHeading = findViewById(R.id.compass_heading)
        loadingGrid = findViewById(R.id.loading_grid)
        clock_text = findViewById(R.id.clock_text)
        myLocationButton = findViewById(R.id.my_location_button)
        findQuadButton = findViewById(R.id.find_quad_button)
        settingsButton = findViewById(R.id.settings_button)
        replayButton = findViewById(R.id.replay_button)
        seekBar = findViewById(R.id.seekbar)
        playButton = findViewById(R.id.play_button)
        horizonView = findViewById(R.id.horizon_view)
        fullscreenButton = findViewById(R.id.fullscreen_button)
        menuButton = findViewById(R.id.replay_menu_button)
        topList = findViewById(R.id.top_list)
        bottomList = findViewById(R.id.bottom_list)
        mapHolder = findViewById(R.id.map_holder)
        mapViewHolder = findViewById(R.id.mapViewHolder)
        rc_widget = findViewById(R.id.rc_widget)
        dnSnr = findViewById(R.id.dn_snr)
        upSnr = findViewById(R.id.up_snr)
        upLq = findViewById(R.id.up_lq)
        dnLq = findViewById(R.id.dn_lq)
        elrsRate = findViewById(R.id.elrs_rate)
        ant = findViewById(R.id.ant)
        power = findViewById(R.id.power)
        rssiDbm1 = findViewById(R.id.up_rssi_dbm1)
        rssiDbm2 = findViewById(R.id.up_rssi_dbm2)
        rssiDbmd = findViewById(R.id.dn_rssi_dbm)
        cell_voltage = findViewById(R.id.cell_voltage)
        throttle = findViewById(R.id.throttle)
        tlmRate = findViewById(R.id.tlm_rate)

        sensorViewMap = hashMapOf(
            Pair(PreferenceManager.sensors.elementAt(0).name, satellites),
            Pair(PreferenceManager.sensors.elementAt(1).name, fuel),
            Pair(PreferenceManager.sensors.elementAt(2).name, voltage),
            Pair(PreferenceManager.sensors.elementAt(3).name, current),
            Pair(PreferenceManager.sensors.elementAt(4).name, speed),
            Pair(PreferenceManager.sensors.elementAt(5).name, distance),
            Pair(PreferenceManager.sensors.elementAt(6).name, traveled_distance),
            Pair(PreferenceManager.sensors.elementAt(7).name, altitude),
            Pair(PreferenceManager.sensors.elementAt(8).name, phoneBattery),
            Pair(PreferenceManager.sensors.elementAt(9).name, rc_widget),
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
            Pair(PreferenceManager.sensors.elementAt(23).name, cell_voltage),
            Pair(PreferenceManager.sensors.elementAt(24).name, altitude_msl),
            Pair(PreferenceManager.sensors.elementAt(25).name, throttle),
            Pair(PreferenceManager.sensors.elementAt(26).name, tlmRate)
        )

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        fullscreenButton.setOnClickListener {
            updateFullscreenState()
            this.fullscreenWindow = !this.fullscreenWindow
            preferenceManager.setFullscreenWindow(fullscreenWindow)
            updateWindowFullscreenDecoration()
        }

        /*
        rootLayout.setOnClickListener {
            var layout = preferenceManager.getMainLayout()
            if (layout == 2) setNextLayout()
        }
        */

        followButton.setOnClickListener {
            centreOnModel()
            // Plain tracking on or off, and nothing about the other button —
            // asking for tracking lets go of the chase, which setFollowMode
            // does. So from behind the model this is a step back to plain
            // tracking rather than a step to nothing.
            setFollowMode(!followMode)
            // the same honesty as the chase button: armed is not engaged,
            // and a button that visibly does nothing owes a word
            if (followMode && lastGPS.lat == 0.0 && lastGPS.lon == 0.0) {
                Toast.makeText(this,
                    "Following centres on the model - it engages when a flight is up",
                    Toast.LENGTH_SHORT).show()
            }
            terrain3D?.setFollowing(keepingUp())
            if (keepingUp()) {
                marker?.let {
                    if (map?.initialized() ?: false) {
                        map?.moveCamera(it.position)
                    }
                }
            }
        }

        chaseButton.setOnClickListener {
            centreOnModel()
            setChaseMode(!chaseMode)
        }

        mapTypeButton.setOnClickListener {
            showMapTypeSelectorDialog()
        }

        northUpButton.setOnClickListener {
            // north up and heading up are two answers to the same question
            setChaseMode(false)
            map?.resetMapOrientation()
            terrain3D?.faceNorth()
        }

        myLocationButton.setOnClickListener {
            // going to where you are standing is leaving the model behind, so
            // it ends both ways of watching it
            centreOnModel()
            setChaseMode(false)
            terrain3D?.let {
                // follow ends only when the trip actually happens — a failed
                // locate leaving the camera unmoored matched neither view
                if (it.goToMyLocation()) {
                    setFollowMode(false)
                    it.setFollowing(false)
                } else {
                    Toast.makeText(this, "Phone location not available", Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            val pos = map?.getMyLocation()
            if (pos != null) {
                setFollowMode(false)
                map?.flyTo(pos, LOCATE_ZOOM)
            } else {
                Toast.makeText(this, "Phone location not available", Toast.LENGTH_SHORT).show()
            }
        }

        findQuadButton.setOnClickListener {
            // Both views go and look at the quad, then say how to walk to it.
            // The camera move belonged to 3D only, and the follow release
            // skipped 3D's own fallback branch — three behaviours for one
            // button.
            val live = juricabi.com.telemetry.gl.LiveFlightPath.latest()
            val quad = live?.let { Position(it.lat, it.lon) }
                ?: lastKnownGPS
            terrain3D?.let { view ->
                if (live != null) {
                    setFollowMode(false)
                    view.lookAt(live.lat, live.lon, live.altitudeMsl)
                } else if (quad != null) {
                    setFollowMode(false)
                    view.lookAt(quad.lat, quad.lon, null)
                }
            }
            if (terrain3D == null && quad != null) {
                setFollowMode(false)
                map?.flyTo(quad, LOCATE_ZOOM)
            }
            showFindMyQuad()
        }

        // What is left is what needs a log already open. Renaming, deleting and
        // copying the model location all moved to where they belong: the first
        // two to the log picker, which is the only place that lists logs, and
        // the last to the Find my quad button.
        menuButton.setOnClickListener { showPlaybackActions() }

        playButton.setOnClickListener {
            // asked for by hand: nothing is owed to it afterwards
            replayWaitingForGround = false
            if ( this.logPlayer != null) {
                if ( this.logPlayer?.isPlaying() == true) {
                    this.logPlayer?.stop()
                } else {
                    this.logPlayer?.startPlayback()
                }
            }
        }

        if (isInReplayMode()) {
            startReplay(
                File(
                    Environment.getExternalStoragePublicDirectory("TelemetryLogs"),
                    replayFileString
                )
            )
        } else {
            switchToIdleState()
        }

        startDataService()

        checkAppInstallDate()
        initMap(false)
        map?.onCreate(savedInstanceState)
        // Chosen last time from the map type list, so it opens that way again.
        // Quietly: with nothing to show yet the map stays, rather than greeting
        // a cold start with a complaint.
        if (preferenceManager.is3DMapChosen()) show3DView(quiet = true)

        updateWindowFullscreenDecoration()

        updateScreenOrientation()

        this.registerReceiver(this.batInfoReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private fun updateWindowFullscreenDecoration() {
        if (!this.fullscreenWindow) {
            window.decorView.systemUiVisibility = 0
        } else {
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE)
        }
    }

    private fun updateFullscreenState() {
        //user may have brought system ui with a swipe. Update state
        this.fullscreenWindow = window.decorView.systemUiVisibility ==
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE)
    }


    private fun initMap(simulateLifecycle: Boolean) {

        headingPolyline?.remove();
        headingPolyline = null;
        polyLine?.remove();
        polyLine = null;
        flightHeadLine?.remove()
        flightHeadLine = null
        flightPlanLines.forEach { it.remove() }
        flightPlanLines.clear()
        homeLine?.remove()
        homeLine = null
        operatorLine?.remove()
        operatorLine = null
        marker?.remove();
        marker = null;
        airplaneMarkers.values.forEach { it.remove() }
        airplaneMarkers.clear()

        initMapLibreMap()
    }

    /**
     * The map. Which of the four it is, is a style built from the tile URLs.
     */
    private fun initMapLibreMap() {
        MapLibre.getInstance(applicationContext)
        val mapView = org.maplibre.android.maps.MapView(this)
        // A MapLibre view renders nothing until it has been through these, and
        // a map built here has already missed the screen's own — the type can
        // be changed, or the view switched back from the ground, long after
        // onCreate, and neither goes through the screen's again.
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapHolder.addView(mapView)
        map = MapLibreMapWrapper(
            applicationContext,
            mapView,
            mapType
        ) {
            initHeadingLine()
            // The style lands after the screen has finished with the map, and
            // a marker cannot be made before it does. Again, now it can.
            pointMapAtTheFlight()
            if (pendingVisualTrack.isNotEmpty()) keepSmoothing()
        }
        finishMapSetup()
    }


    /**
     * Everything a map needs once it exists, whichever one it is.
     *
     * Was the tail of initOSMMap, and is the whole reason a second map is
     * tractable at all: only the few lines that build the view differ.
     */
    private fun finishMapSetup() {
        map?.setOnCameraMoveStartedListener {
            // No gesture gives up following or the chase, here as in three
            // dimensions. What the hand does is kept — the map goes on keeping
            // up with the model from wherever it has been put, and goes on
            // turning with its heading from whatever angle it has been left at.
            // The buttons put it back to the middle.
            leanOutOfFollowing()
        }
        map?.setArrowColours(
            preferenceManager.getLiveArrowColor(), preferenceManager.getLoggedArrowColor()
        )
        map?.setOnOrientationChangedListener { orientation ->
            updateCompassHeading(orientation)
        }
        polyLine = map?.addFlightLine(LineWeights.FLIGHT, preferenceManager.getRouteColor())
        // Only a flight that is still going, or one being replayed. The service
        // outlives this screen and keeps the points of whatever it last heard,
        // so an unconnected map opened afterwards drew the last flight as
        // though it were happening — which the 3D ground never did.
        redrawFlightLine()
        homeLine = map?.addHomeLine(LineWeights.HOME, preferenceManager.getHomeLineColor())
        // Keep a real two-point line from the start, as the heading line does.
        // A missing phone fix can then leave it alone instead of repeatedly
        // dismantling and rebuilding its renderer source.
        homeLine?.addPoints(listOf(lastGPS, lastGPS))
        operatorLine = map?.addOperatorLine(LineWeights.HOME, preferenceManager.getOperatorLineColor())
        operatorLine?.addPoints(listOf(lastGPS, lastGPS))
        drawFlightPlans()
        flightHeadLine = map?.addFlightHeadLine(
            LineWeights.FLIGHT, preferenceManager.getRouteColor()
        )
        flightHeadLine?.addPoints(listOf(lastGPS, lastGPS))
        showMyLocation()
        // Twice over the building of a map, deliberately. Here it is what
        // points the camera — the map remembers where it was aimed and obeys
        // the moment it exists — but it cannot make a marker, since there is no
        // style yet to hang one on. It runs again when the style lands and
        // makes the marker then. Nothing in it shows for being done twice.
        pointMapAtTheFlight()
        // and the traffic, which is otherwise gone until the next poll comes
        // round — half a minute of empty sky after every switch of view
        if (lastAirplanes.isNotEmpty()) onAirplanesUpdated(lastAirplanes)
    }

    /**
     * Put the map on the flight, and run again once the map can draw.
     *
     * tryCreateMarker will not make a marker for a map that is not drawable
     * yet, and a MapLibre map is not: its style arrives a few frames after the
     * screen has finished setting it up. So the model got no marker at all
     * until the next fix moved it — and with a replay standing paused, or a
     * link that has dropped, there is no next fix and there never was one.
     * A map that could draw the moment it was made would not need this.
     */
    private fun pointMapAtTheFlight() {
        // A map is built looking at the whole world, and it is a fix arriving
        // that puts the model on screen. Where the model already is — coming
        // back from the 3D view, or a replay standing paused — there may be no
        // fix for a while, and there was nothing to see until there was one.
        if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) {
            tryCreateMarker()
            marker?.let {
                // and pointing the way it was pointing. A marker is made facing
                // north and only ever turned by the frame loop, which needs new
                // data — so a map built after the link dropped showed the model
                // facing north wherever it had really been going.
                val heading =
                    if (shownMarkerHeading.isNaN()) lastHeading else shownMarkerHeading
                it.place(shownPosition(), heading)
            }
            // Straight at it, and not still leaning wherever the last map was
            // dragged to. The lean outlives the map it was made on, so a map
            // built after a change of view put the model off to one side — and
            // turning the map to a heading swung it round the empty middle
            // instead of round the model.
            centreOnModel()
            map?.moveCamera(shownPosition(), LOCATE_ZOOM)
            updateHeading()
        } else {
            // Nothing flown yet: open on where this phone is, at the same
            // height the locate button uses. A map is built looking at the
            // whole world from zoom four, which is no use to anybody.
            myLastKnownPlace()?.let { map?.moveCamera(it, LOCATE_ZOOM) }
        }
        // A map that has just been built knows nothing until it is told —
        // and the phone, the operator and the line home exist whether or not
        // the model has a fix yet. Fed only alongside the model, a map built
        // during a paused replay or before the link came up had no arrow, no
        // ring and no operator until the next live fix happened along.
        tellViewsWhereIAm()
        updateHomeLine()
        showOperator()
    }

    /**
     * The whole flight onto a line that has just been made.
     *
     * Committing matters as much as handing over: handing points to a line only
     * stages them, and until something else committed, a map built during a
     * paused replay had the flight staged and invisible.
     */
    private fun redrawFlightLine() {
        val flown = publishedVisualTrack
        if (flown.isEmpty()) return
        polyLine?.submitPoints(ArrayList(flown))
        commitRouteLinePoints()
    }

    /**
     * The clock over the map, which is over the 3D ground as well: both are
     * drawn under the same overlay.
     *
     * The screen runs without the system's own bar most of the time, so there
     * was otherwise nowhere on it that said what time it was — and a replay
     * never said what day it was flown at all.
     */
    private fun showTime() {
        if (!preferenceManager.isClockEnabled()) {
            clock_text.visibility = View.GONE
            return
        }
        val text = if (isInReplayMode()) {
            replayTimeNow()?.let { flightDayFormat.format(it) }
        } else {
            timeOfDayFormat.format(Date())
        }
        clock_text.text = text ?: ""
        clock_text.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /**
     * The time of day at the point the replay has reached.
     *
     * The CSV says when the first row was written and when the last one was, so
     * the flight has a real length; the position in the log says how far
     * through it we are. A telemetry link talks at a steady rate, so packets
     * counted measure time passed well enough: exact at both ends of the flight
     * and within a few seconds between them.
     */
    private fun replayTimeNow(): Date? {
        val track = operatorTrack ?: return null
        // Where the replay has got to in the recording, looked up in the CSV,
        // which was written on a clock. Packets counted would do only if the
        // link talked at a steady rate — and the whole point of asking is the
        // minute where it said nothing at all.
        val player = logPlayer
        if (player != null) {
            // the packet just played, not the one about to be: seeking to a
            // position decodes everything before it, so the last one on screen
            // is the one before. Reading the next one's place in the recording
            // told the time on the far side of a link outage while the model
            // was still sitting on the near side of it.
            track.timeAtMark(player.bytesAt(player.currentPosition - 1))?.let { return Date(it) }
        }
        // an older recording, with no marks in it: spread evenly, as before
        val span = track.endedAt - track.startedAt
        if (span <= 0L) return Date(track.startedAt)
        val total = seekBar.max
        if (total <= 0) return Date(track.startedAt)
        val at = (logPlayer?.currentPosition ?: 0).toFloat() / total
        val part = Math.max(0f, Math.min(1f, at))
        return Date(track.startedAt + (span * part).toLong())
    }

    /**
     * Read the flight's record of the operator, off the screen's thread.
     *
     * A CSV of a long flight is five rows a second of it, and the log it
     * belongs to is being decoded at the same moment behind a progress dialog.
     */
    private fun readOperatorTrack(log: File) {
        forgetOperator()
        val csv = File(log.parentFile, replaceExtension(log.name, ".csv"))
        val worker = Thread(Runnable {
            val track = try {
                OperatorTrack.read(csv)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                operatorTrack = track
                showTime()
                showOperator()
                // A replay started before this arrived was running to a length
                // estimated from its packets. Now that the recording's own
                // clock is here, it runs to that. Not one at its end, though:
                // its timer marks itself stopped a tick after the last packet,
                // and a restart in that window plays the whole flight again.
                val player = logPlayer
                if (player != null && player.isPlaying() &&
                    player.currentPosition < player.packetCount()) {
                    player.stop()
                    player.startPlayback()
                }
            }
        })
        worker.name = "operator-track"
        worker.start()
    }

    /**
     * Put the operator back where they were standing at this point of the
     * flight: the arrow, the ring around it, the way they were facing, and the
     * line home, which is drawn to them.
     *
     * With no record of it, none of the four is drawn. Where somebody stood is
     * not a thing worth guessing at — replayed against wherever this phone
     * happens to be now, the line home crosses the county.
     */
    private fun showOperator() {
        // the ground view decides its operator line by the same rule the map does
        terrain3D?.homeFromRecorded = isInReplayMode()
        if (!isInReplayMode()) return
        // The recorded operator has a main switch: off, the arrow, its ring
        // and its line go together — a line pointing at a hidden arrow
        // points at nothing.
        if (!preferenceManager.isRecordedOperatorShown()) {
            map?.showRecordedLocation(null, 0f, 0f)
            terrain3D?.hideLoggedLocation()
            recordedMe = null
            updateHomeLine()
            return
        }
        val track = operatorTrack
        val now = replayTimeNow()
        if (track == null || now == null) {
            // nothing recorded of where anybody stood, so nothing orange drawn
            map?.showRecordedLocation(null, 0f, 0f)
            terrain3D?.hideLoggedLocation()
            updateHomeLine()
            return
        }
        // A flight recorded with the app in the background has times and no
        // places: the clock runs, and nothing orange is drawn.
        val where = track.at(now.time)
        if (where == null) {
            map?.showRecordedLocation(null, 0f, 0f)
            terrain3D?.hideLoggedLocation()
            recordedMe = null
            updateHomeLine()
            return
        }
        val here = Position(where.lat, where.lon)
        recordedMe = here

        // Its own arrow in both views — orange, beside the blue one that is
        // where the phone is now. On the map it is a second overlay of the same
        // kind, so it is drawn exactly as the live one is.
        map?.showRecordedLocation(here, where.accuracy, where.heading)
        terrain3D?.setLoggedPosition(where.lat, where.lon, where.accuracy, where.heading)
        updateHomeLine()
    }

    /** For a replay that has been closed, opened or renamed. */
    private fun forgetOperator() {
        operatorTrack = null
        recordedMe = null
        // the orange arrow belongs to a replay and goes with it — into the
        // garage as well, or a world parked during one hands the recorded
        // operator back over the next flight
        map?.showRecordedLocation(null, 0f, 0f)
        (terrain3D ?: parked3D)?.hideLoggedLocation()
        showMyLocation()
        tellViewsWhereIAm()
        showTime()
    }

    private fun updateCompassHeading(orientation: Float) {
        val heading = (((-orientation % 360f) + 360f) % 360f).roundToInt() % 360
        compassHeading.text = "↑ %03d°".format(heading)
        compassHeading.visibility = View.VISIBLE
    }

    private fun showMyLocation() {
        if (checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Where this phone is now. Over a replay that is worth seeing
            // beside where it stood at the time — it says how far away the
            // flight was, and where you are standing to watch it — so it is a
            // setting rather than simply off.
            map?.isMyLocationEnabled = showLiveArrow()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_LOCATION_PERMISSION
            )
            map?.isMyLocationEnabled = false
        }
    }


    private fun initHeadingLine() {
        polyLine?.let { it.color = preferenceManager.getRouteColor() }
        flightHeadLine?.let { it.color = preferenceManager.getRouteColor() }
        marker?.setIcon(modelIcon(), preferenceManager.getPlaneColor())
        if (!isIdle()) {
            if (preferenceManager.isHeadingLineEnabled() && headingPolyline == null) {
                headingPolyline = createHeadingPolyline()
                updateHeading()
            } else if (!preferenceManager.isHeadingLineEnabled() && headingPolyline != null) {
                headingPolyline?.remove()
                headingPolyline = null
            }
            headingPolyline?.let { it.color = preferenceManager.getHeadLineColor() }
            marker?.setIcon(modelIcon(), preferenceManager.getPlaneColor())
        }
        drawFlightPlans()
    }

    /**
     * The home line: from the drone to where this phone is now, live or
     * playing back alike — walking to a downed model is its whole use,
     * which is also why it draws above every other line. Blue, like the
     * arrow that says the same thing.
     */
    private fun updateHomeLine(displayedDrone: Position? = null) {
        updateOperatorLine(displayedDrone)
        val line = homeLine ?: return
        line.color = preferenceManager.getHomeLineColor()
        // hidden with its arrow: a line pointing at an arrow that has been
        // switched off points at nothing
        if (!preferenceManager.isHomeLineEnabled() || !showLiveArrow()) {
            line.clear()
            return
        }
        // from where the model is drawn, not where the fix was, so the line
        // stays joined to it as it moves
        val drone = if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) {
            displayedDrone ?: presentedPosition()
        } else return
        // where this phone is, from the system if the map's own overlay has
        // not found it yet: a newly built map takes a while to get its first
        // fix, and the line waited all of it
        val phone = myLastKnownPlace() ?: return
        line.setPoints(listOf(drone, phone))
    }

    /**
     * The operator line, playback only: from the drone to where the
     * operator stood as recorded. Orange, like the arrow it points at, and
     * gone with it when the recorded operator's main switch is off.
     */
    private fun updateOperatorLine(displayedDrone: Position? = null) {
        val line = operatorLine ?: return
        line.color = preferenceManager.getOperatorLineColor()
        if (!preferenceManager.isOperatorLineEnabled() || !isInReplayMode()) {
            line.clear()
            return
        }
        val drone = if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) {
            displayedDrone ?: presentedPosition()
        } else return
        // No record right now — the operator track ran out, or its main
        // switch went off — means no line NOW: returning without clearing
        // left the last segment hanging to a vanished arrow.
        val phone = recordedMe
        if (phone == null) {
            line.clear()
            return
        }
        line.setPoints(listOf(drone, phone))
    }

    private fun drawFlightPlans() {
        flightPlanLines.forEach { it.remove() }
        flightPlanLines.clear()
        if (!preferenceManager.isFlightPlansEnabled()) return
        val plans = FlightPlanManager(this).getPlans()
        for (plan in plans) {
            if (!plan.visible || plan.waypoints.size < 2) continue
            // The same weight as the flight itself, so a plan and the flight
            // flown against it read as the same kind of thing.
            val line = map?.addFlightPlanLine(
                LineWeights.PLAN, plan.color, *plan.waypoints.toTypedArray()
            )
            if (line != null) {
                flightPlanLines.add(line)
            }
        }
    }

    // Everything needed to walk to a downed model: where it was last seen, how
    // far and in which direction from where you are standing, and a plus code
    // that can be typed into any maps app or read out to someone else.
    private fun showFindMyQuad() {
        val live = if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) lastGPS else null
        // Falls back to the last position from before the disconnect, which is
        // the whole point of the button after a link is lost.
        val pos = live ?: lastKnownGPS
        if (pos == null) {
            Toast.makeText(this, "No position received from the model yet", Toast.LENGTH_LONG).show()
            return
        }

        val nl = 10.toChar().toString()
        val age = if (live == null && lastKnownGPSAt > 0L) {
            val seconds = (System.currentTimeMillis() - lastKnownGPSAt) / 1000
            when {
                seconds < 60 -> "last seen " + seconds + "s ago"
                seconds < 3600 -> "last seen " + (seconds / 60) + " min ago"
                else -> "last seen " + (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m ago"
            }
        } else {
            null
        }
        val plusCode = PlusCode.encode(pos.lat, pos.lon)
        // Locale.US: a comma decimal separator would corrupt the maps link
        val coords = String.format(java.util.Locale.US, "%.6f,%.6f", pos.lat, pos.lon)
        val mapsUrl = "https://www.google.com/maps/search/?api=1&query=" + coords

        val text = StringBuilder()
        if (age != null) text.append(age).append(nl).append(nl)
        text.append("Plus code").append(nl).append(plusCode).append(nl).append(nl)
        text.append("Coordinates").append(nl).append(coords)

        val me = map?.getMyLocation()
        if (me != null) {
            val results = FloatArray(2)
            android.location.Location.distanceBetween(me.lat, me.lon, pos.lat, pos.lon, results)
            val bearing = ((results[1] % 360f) + 360f) % 360f
            text.append(nl).append(nl).append("From you").append(nl)
            text.append("%.0f m".format(results[0]))
            text.append("   bearing ")
            text.append("%03d".format(bearing.toInt())).append(" true")
        }

        // via showDialog like every other map dialog, or it drops fullscreen
        showDialog(AlertDialog.Builder(this)
            .setTitle("Find my quad")
            .setMessage(text.toString())
            // Directions rather than a plain view: the point of this dialog is
            // walking to a model that is lying in a field, and the directions
            // screen shows the location as well. This is what the menu called
            // "Show route to UAV", now that the menu no longer carries it.
            .setPositiveButton("Route") { _, _ ->
                val daddr = "%.7f,%.7f".format(Locale.US, pos.lat, pos.lon)
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("http://maps.google.com/maps?daddr=" + daddr)
                        )
                    )
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "No maps app found", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Share") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Model last seen at " + plusCode + " (" + coords + ")" + nl + mapsUrl
                    )
                }
                startActivity(Intent.createChooser(share, "Share location"))
            }
            .setNegativeButton("Copy") { _, _ ->
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboardManager.setPrimaryClip(
                    ClipData.newPlainText("Location", plusCode + " (" + coords + ")"))
                Toast.makeText(this, "Copied " + plusCode, Toast.LENGTH_LONG).show()
            }
            .create())
    }

    private fun checkAppInstallDate() {
        val installTime = packageManager.getPackageInfo(packageName, 0).firstInstallTime
        val delta = System.currentTimeMillis() - installTime

        if (delta / 1000 / 60 / 60 / 24 > 3 && !preferenceManager.isYoutubeChannelShown()) {
            this.showDialog(AlertDialog.Builder(this)
                .setTitle("Thanks for using my application")
                .setMessage(
                    "Thanks for using my application. As it's does not contain any ads and completely free, " +
                            "you can help me by subscribing to my youtube channel"
                )
                .setPositiveButton("Subscribe") { dialog: DialogInterface?, i: Int ->
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/channel/UCjAhODF0Achhc1fynxEXQLg?view_as=subscriber&sub_confirmation=1")
                        )
                    )
                }
                .setNegativeButton("Cancel", null)
                .setOnDismissListener { preferenceManager.setYoutubeShown() }
                .create());
        }
    }

    private fun isInReplayMode(): Boolean {
        return replayFileString != null
    }

    private fun isIdle(): Boolean {
        return !isInReplayMode() && !(dataService?.isConnected() ?: false)
    }

    private fun replay() {
        if (dataService?.isConnected() != true) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_DENIED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQUEST_READ_PERMISSION
                )
            } else {
                val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
                val listed = if (dir.exists()) {
                    dir.listFiles { file ->
                        ((file.extension == "log") || (file.extension == "tlm")) && (file.length() > 0)
                    }
                } else {
                    null
                }
                if (listed == null || listed.isEmpty()) {
                    Toast.makeText(this, "No logs available", Toast.LENGTH_SHORT).show()
                } else {
                    val files = listed.sorted().reversed()

                    if ( lastFileDialogSelectionIndex >= files.size) {
                        lastFileDialogSelectionIndex = files.size-1;
                    }

                    val dialog = AlertDialog.Builder(this)
                        .setAdapter(
                            ArrayAdapter(
                                this,
                                android.R.layout.simple_list_item_1,
                                files.map { i ->
                                    if ( i.name == lastFileDialogSelection ) {
                                        val b = "${i.nameWithoutExtension} (${ceil(i.length() / 102.4) / 10} Kb)"
                                        val boldOption = SpannableString(b)
                                        boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, b.length, 0)
                                        boldOption
                                    } else {
                                        "${i.nameWithoutExtension} (${ceil(i.length() / 102.4) / 10} Kb)"
                                    }
                                })
                        ) { _, i ->
                            updateWindowFullscreenDecoration()
                            lastFileDialogSelectionIndex = i;
                            lastFileDialogSelection = files[i].name
                            startReplay(files[i])
                        }
                        .setNegativeButton("Delete all") { d, _ ->
                            d.dismiss()
                            showDeleteAllLogsDialog(files)
                        }
                        .create();

                    dialog.setOnShowListener {
                        val alertDialog = it as AlertDialog
                        alertDialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                            dialog.dismiss()
                            showLogActionsDialog(files[position])
                            true
                        }
                        if ( lastFileDialogSelectionIndex != -1) {
                            val centerY = alertDialog.listView.height / 2 // Calculate the center position vertically
                            alertDialog.listView.smoothScrollToPositionFromTop(lastFileDialogSelectionIndex, centerY)
                        }
                    }

                    this.showDialog(dialog);
                }
            }
        } else {
            Toast.makeText(this, "You need to disconnect first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startReplay(file: File?) {
        // A hold belonging to the replay being closed. Left set, tearing down
        // the old 3D view below released it — onto a player just disposed.
        replayWaitingForGround = false
        logPlayer?.dispose()
        GhstProtocol.forgetLaunchAltitude()
        juricabi.com.telemetry.gl.AltitudeFrame.forget()
        detectedCells = 0
        highestPackVoltage = 0f
        cellsAsked = false
        cellsAnswered = false
        forgetFlight()
        // In replay mode before the ground is begun again: the 3D view decides
        // at birth whether its ground follows the phone, and a view born for a
        // replay that still read as live built the phone's world first, only
        // to throw it away when the flight arrived from the log.
        replayFileString = file?.name
        startFlightIn3D()
        file?.also {
            val progressDialog = ProgressDialog(this)
            progressDialog.setCancelable(false)
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            progressDialog.max = 100

            progressDialog.window?.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            );
            progressDialog.show();
            if (!this.fullscreenWindow) {
                progressDialog.window?.decorView?.systemUiVisibility = 0
            } else {
                progressDialog.window?.decorView?.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE)
            }
            progressDialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

            switchToReplayMode()

            readOperatorTrack(file)

            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
            }

            this.logPlayer = LogPlayer(this)

            val context = this;

            this.logPlayer?.load(file, object : LogPlayer.DataReadyListener {
                override fun onUpdate(percent: Int) {
                    progressDialog.progress = percent
                }

                override fun onDataReady(size: Int) {
                    progressDialog.dismiss()
                    // The log is decoded, so the flight's first place is known
                    // before a packet of it is played. Begun here, the ground
                    // is loading by the time the autostart hold asks about it,
                    // and the replay opens over a finished world.
                    logPlayer?.firstPosition()?.let { first ->
                        // A standing world serves a log flown at this field.
                        // One from another country cannot be re-opened over:
                        // it reports itself ready for the wrong ground, so
                        // the hold lets playback start, and the first fix
                        // then re-anchors the world out from under it. The
                        // log's own field is known here and nowhere earlier.
                        val standing = terrain3D ?: parked3D
                        if (standing?.worldNear(first.lat, first.lon) == false) {
                            startFlightIn3D(keepWorld = false)
                        }
                        terrain3D?.beginAt(first.lat, first.lon)
                    }
                    seekBar.max = size
                    seekBar.visibility = View.VISIBLE
                    playButton.visibility = View.VISIBLE
                    // Autostart belongs to LogPlayer, which asks this
                    // screen's listener and so honours the ground hold. A
                    // second, direct start here beat the hold to the timer
                    // and played the opening seconds over an empty world.
                    var resumeAfterScrub = false
                    // One decode per hundred milliseconds while the thumb is
                    // down, to wherever it is by then. Every drag event used
                    // to seek at once — and a leftward seek decodes the log
                    // again from its first packet, so a fast drag was dozens
                    // of whole-log decodes back to back on this thread, and
                    // the screen locked up for the length of them.
                    var pendingScrub = -1
                    val scrubRunner = object : Runnable {
                        override fun run() {
                            val to = pendingScrub
                            pendingScrub = -1
                            if (to >= 0) {
                                logPlayer?.seek(to)
                                showOperator()
                                showTime()
                            }
                        }
                    }
                    seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekbar: SeekBar,
                            position: Int,
                            fromUser: Boolean
                        ) {
                            if (fromUser) {
                                val idle = pendingScrub < 0
                                pendingScrub = position
                                if (idle) seekbar.postDelayed(scrubRunner, 100)
                                return
                            }
                            // playback's own advance: forward and incremental
                            logPlayer?.seek(position)
                            showOperator()
                            // The clock keeps step with the log rather than
                            // with the wall: the replay moves twenty times a
                            // second, and sampling it once a second showed a
                            // time that lurched and lagged. Dragging the bar
                            // lands on it here too, so it is right the instant
                            // the flight is somewhere else.
                            showTime()
                        }

                        override fun onStartTrackingTouch(p0: SeekBar?) {
                            // A hand-controlled seek is a placement. Stop the
                            // replay clock while the thumb is moving so every
                            // progress callback takes the exact paused path.
                            resumeAfterScrub = logPlayer?.isPlaying() == true
                            if (resumeAfterScrub) logPlayer?.stop()
                        }

                        override fun onStopTrackingTouch(p0: SeekBar?) {
                            // wherever the thumb was left, exactly, now
                            p0?.removeCallbacks(scrubRunner)
                            scrubRunner.run()
                            if (resumeAfterScrub) {
                                resumeAfterScrub = false
                                // Not from the very end: startPlayback treats a
                                // finished replay as one to play again, so
                                // letting go there would wrap to the start.
                                if (p0 == null || p0.progress < p0.max) {
                                    logPlayer?.startPlayback()
                                }
                            }
                        }
                    })

                    // Open on the flight: one seek to its first fix, which
                    // places the model and takes the camera there, and then back
                    // to the beginning.
                    //
                    // This used to walk there a packet at a time, asking after
                    // each one whether there was a position, a marker and a
                    // heading yet. On a log whose heading arrives late it kept
                    // walking, and every step drew its packet: tens of thousands
                    // of single positions pushed through the line, the model and
                    // the camera before the rewind threw them away — the flight
                    // flown once, at speed, before the replay had begun.
                    lastGPS = Position(0.0, 0.0);
                    gotHeading = false;
                    modelHeadingKnown = false
                    logPlayer?.let { it.seek(it.firstFixPosition()) }

                    logPlayer?.seek(0);
                }

                override fun onPlaybackPositionChange(prevPosition: Int, nextPosition: Int) {
                    runOnUiThread {
                        if ( (logPlayer?.currentPosition ?:0) == prevPosition ) {
                            seekBar.progress = nextPosition
                        }
                    }
                }

                override fun onPlaybackStateChange( isPlaying : Boolean){
                    runOnUiThread {
                        if ( isPlaying) {
                            playButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_pause));
                        } else {
                            playButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_play));
                        }
                    }
                }

                override fun getTotalPlaybackDurationSec() : Int
                {
                    return Math.max(1L, Math.round(playbackSeconds(
                        realFlightSeconds(), preferenceManager.getPlaybackSpeed()
                    ))).toInt()
                }

                override fun getPlaybackAutostart() : Boolean
                {
                    if (!preferenceManager.getPlaybackAutostart()) return false
                    val view = terrain3D
                    // Held only for ground already on its way. A replay-bound
                    // view waits for the flight's first fix before loading any
                    // ground, and that fix comes from playback — held here,
                    // the two waited on each other and nothing ever started.
                    if (view != null && view.groundBegun() && !view.groundReady()) {
                        replayWaitingForGround = true
                        return false
                    }
                    return true
                }

                override fun onProtocolDetected(protocolName: String) {
                    runOnUiThread {
                        Toast.makeText(context, "Protocol: $protocolName", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    /**
     * Whether the model has said it is armed, and whether it has ever said.
     *
     * A link that never mentions arming must not have every height thrown
     * away, so nothing is filtered until one has been reported.
     */
    @Volatile private var isArmed = false
    @Volatile private var gotArmedState = false

    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) {
        isArmed = armed
        gotArmedState = true
        runOnUiThread {
            if (armed) {
                mode.text = "Armed"
            } else {
                mode.text = "Disarmed"
            }

            if (heading) {
                mode.text = mode.text.toString() + " | Heading"
            }

            decodeMode(firstFlightMode)
            decodeMode(secondFlightMode)
        }
    }

    private fun decodeMode(flyMode: DataDecoder.Companion.FlyMode?) {
        when (flyMode) {
            DataDecoder.Companion.FlyMode.ACRO -> {
                mode.text = mode.text.toString() + " | Acro"
            }
            DataDecoder.Companion.FlyMode.HORIZON -> {
                mode.text = mode.text.toString() + " | Horizon"
            }
            DataDecoder.Companion.FlyMode.ANGLE -> {
                mode.text = mode.text.toString() + " | Angle"
            }
            DataDecoder.Companion.FlyMode.FAILSAFE -> {
                mode.text = mode.text.toString() + " | Failsafe"
            }
            DataDecoder.Companion.FlyMode.RTH -> {
                mode.text = mode.text.toString() + " | RTH"
            }
            DataDecoder.Companion.FlyMode.WAYPOINT -> {
                mode.text = mode.text.toString() + " | Waypoint"
            }
            DataDecoder.Companion.FlyMode.MANUAL -> {
                mode.text = mode.text.toString() + " | Manual"
            }
            DataDecoder.Companion.FlyMode.CRUISE -> {
                mode.text = mode.text.toString() + " | Cruise"
            }
            DataDecoder.Companion.FlyMode.HOLD -> {
                mode.text = mode.text.toString() + " | Hold"
            }
            DataDecoder.Companion.FlyMode.HOME_RESET -> {
                mode.text = mode.text.toString() + " | Home reset"
            }
            DataDecoder.Companion.FlyMode.CRUISE3D -> {
                mode.text = mode.text.toString() + " | 3D Cruise"
            }
            DataDecoder.Companion.FlyMode.ALTHOLD -> {
                mode.text = mode.text.toString() + " | Alt hold"
            }
            DataDecoder.Companion.FlyMode.ERROR -> {
                mode.text = mode.text.toString() + " | !ERROR!"
            }
            DataDecoder.Companion.FlyMode.WAIT -> {
                mode.text = mode.text.toString() + " | GPS wait"
            }
            DataDecoder.Companion.FlyMode.CIRCLE -> {
                mode.text = mode.text.toString() + " | Circle"
            }
            DataDecoder.Companion.FlyMode.STABILIZE -> {
                mode.text = mode.text.toString() + " | Stabilize"
            }
            DataDecoder.Companion.FlyMode.TRAINING -> {
                mode.text = mode.text.toString() + " | Training"
            }
            DataDecoder.Companion.FlyMode.FBWA -> {
                mode.text = mode.text.toString() + " | FBWA"
            }
            DataDecoder.Companion.FlyMode.FBWB -> {
                mode.text = mode.text.toString() + " | FBWB"
            }
            DataDecoder.Companion.FlyMode.AUTOTUNE -> {
                mode.text = mode.text.toString() + " | Autotune"
            }
            DataDecoder.Companion.FlyMode.LOITER -> {
                mode.text = mode.text.toString() + " | Loiter"
            }
            DataDecoder.Companion.FlyMode.TAKEOFF -> {
                mode.text = mode.text.toString() + " | Takeoff"
            }
            DataDecoder.Companion.FlyMode.AVOID_ADSB -> {
                mode.text = mode.text.toString() + " | AVOID_ADSB"
            }
            DataDecoder.Companion.FlyMode.GUIDED -> {
                mode.text = mode.text.toString() + " | Guided"
            }
            DataDecoder.Companion.FlyMode.INITIALISING -> {
                mode.text = mode.text.toString() + " | Initializing"
            }
            DataDecoder.Companion.FlyMode.LANDING -> {
                mode.text = mode.text.toString() + " | Landing"
            }
            DataDecoder.Companion.FlyMode.MISSION -> {
                mode.text = mode.text.toString() + " | Mission"
            }
            DataDecoder.Companion.FlyMode.QSTABILIZE -> {
                mode.text = mode.text.toString() + " | QSTABILIZE"
            }
            DataDecoder.Companion.FlyMode.QHOVER -> {
                mode.text = mode.text.toString() + " | QHOVER"
            }
            DataDecoder.Companion.FlyMode.QLOITER -> {
                mode.text = mode.text.toString() + " | QLOITER"
            }
            DataDecoder.Companion.FlyMode.QLAND -> {
                mode.text = mode.text.toString() + " | QLAND"
            }
            DataDecoder.Companion.FlyMode.QRTL -> {
                mode.text = mode.text.toString() + " | QRTL"
            }
            DataDecoder.Companion.FlyMode.QAUTOTUNE -> {
                mode.text = mode.text.toString() + " | QAUTOTUNE"
            }
            DataDecoder.Companion.FlyMode.QACRO -> {
                mode.text = mode.text.toString() + " | QACRO"
            }
            DataDecoder.Companion.FlyMode.AUTONOMOUS -> {
                mode.text = mode.text.toString() + " | Autonomous"
            }
            DataDecoder.Companion.FlyMode.GEO -> {
                mode.text = mode.text.toString() + " | Geo"
            }
            DataDecoder.Companion.FlyMode.TURTLE -> {
                mode.text = mode.text.toString() + " | Turtle"
            }
            DataDecoder.Companion.FlyMode.RATE -> {
                mode.text = mode.text.toString() + " | Rate"
            }
            DataDecoder.Companion.FlyMode.ANGLE_HOLD -> {
                mode.text = mode.text.toString() + " | Angle Hold"
            }
            null -> {
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        map?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        map?.onSaveInstanceState(outState)
        outState?.putBoolean("follow_mode", followMode)
        // Turning the phone round builds this screen again from nothing, and an
        // answer already given should not be asked for a second time.
        outState?.putInt("cells", detectedCells)
        outState?.putBoolean("cells_answered", cellsAnswered)
        outState?.putBoolean("cells_asked", cellsAsked)
        outState?.putBoolean("chase_mode", chaseMode)
        outState?.putString("replay_file_name", replayFileString)
        preferenceManager.setFullscreenWindow(fullscreenWindow)
    }

    override fun onStart() {
        super.onStart()
        map?.onStart()
        if (preferenceManager.showArtificialHorizonView()) {
            horizonView.visibility = View.VISIBLE
        } else {
            horizonView.visibility = View.GONE
        }

        updateHorizonViewSize()

        updateSensorsPlacement()
    }

    private fun updateSensorsPlacement() {
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

    private fun connect() {
        lastConnectionType = CONNTYPE_NONE;
        val showcaseView = MaterialShowcaseView.Builder(this)
            .renderOverNavigationBar()
            .setTarget(replayButton)
            .setMaskColour(Color.argb(230, 0, 0, 0))
            .setDismissText("GOT IT")
            .setContentText("You can replay your logged flights by clicking this button")
            .setListener(
                object : IShowcaseListener {
                    override fun onShowcaseDismissed(showcaseView: MaterialShowcaseView?) {
                        connect();
                    }
                    override fun onShowcaseDisplayed(showcaseView: MaterialShowcaseView?) {
                    }
                })
            .singleUse("replay_guide").build()

        var items = arrayOf(
            "Bluetooth",
            "Bluetooth LE",
            "USB Serial",
            getString(R.string.network)
        )

        if (showcaseView.hasFired()) {
            this.showDialog(AlertDialog.Builder(this)
                .setAdapter(
                    ArrayAdapter(
                        this,
                        android.R.layout.simple_list_item_1,
                        items.map { i ->
                            if (i == lastSelectedDataPooler) {
                                val boldOption = SpannableString(i)
                                boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, i.length, 0)
                                boldOption
                            } else {
                                i
                            }
                        })
                ) { dialogInterface, i ->
                    lastSelectedDataPooler = items[i]
                    preferenceManager.setLastSelectedDataPooler(lastSelectedDataPooler)
                    when (i) {
                        0 -> connectBluetooth()
                        1 -> connectBluetoothLE()
                        2 -> connectUSB()
                        3 -> connectNetwork()
                    }
                }
                .setTitle("Choose connection method")
                .create())
        } else {
            showcaseView.show(this)
        }
    }

    private fun connectUSB() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull()
        if (driver == null) {
            // Three different problems used to share one message. Naming what is
            // actually attached says which one it is: nothing plugged in at all,
            // or a radio sitting in Joystick or Storage mode instead of serial.
            val attached = usbManager.deviceList.values
            val message = if (attached.isEmpty()) {
                "No USB device attached. Check the cable supports data, and that the " +
                    "phone is not also plugged into a computer."
            } else {
                val names = StringBuilder()
                for (device in attached) {
                    if (names.isNotEmpty()) names.append(", ")
                    names.append(String.format("%04x:%04x", device.vendorId, device.productId))
                }
                "Attached (" + names + ") but not a serial port. On EdgeTX choose " +
                    "USB Serial (VCP) rather than Joystick or Storage."
            }
            this.showDialog(
                AlertDialog.Builder(this)
                    .setTitle("No serial device")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .create()
            )
        } else {
            val connection = usbManager.openDevice(driver.device)
            if (connection != null) {
                val port = driver.ports.firstOrNull()
                if (port == null) {
                    Toast.makeText(this, "No valid usb port has been found", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    connectToUSBDevice(port, connection)
                }
            } else {
                val pendingIntent =
                    PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(ACTION_USB_DEVICE).setPackage(packageName),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (ACTION_USB_DEVICE == intent?.action) {
                            synchronized(this) {
                                val device: UsbDevice? =
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                                if (intent.getBooleanExtra(
                                        UsbManager.EXTRA_PERMISSION_GRANTED,
                                        false
                                    )
                                ) {
                                    device?.apply {
                                        connectUSB()
                                    }
                                } else {
                                    Toast.makeText(
                                        this@MapsActivity,
                                        "You need to allow permission in order to connect with a usb",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }

                        unregisterReceiver(this)
                    }
                }, IntentFilter(ACTION_USB_DEVICE))
                usbManager.requestPermission(driver.device, pendingIntent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map?.onResume()
        terrain3D?.let {
            it.onResume()
            applyTerrainSettings(it)
        }
        this.sensorTimeoutManager.resume();
        updateWindowFullscreenDecoration()
        updateScreenOrientation()
        // reapplies the colours and the heading line, which is how a change made
        // in the settings reaches the map; it draws the flight plans too
        map?.setArrowColours(
            preferenceManager.getLiveArrowColor(), preferenceManager.getLoggedArrowColor()
        )
        showTime()
        clock_text.removeCallbacks(clockTicker)
        clock_text.postDelayed(clockTicker, 1000)
        initHeadingLine()
        updateHomeLine()
        // The recorded-operator switch may have changed in the settings just
        // left. Applied here, the arrow, its ring and its line change
        // together — without this they waited for the next tick or scrub of
        // the replay to notice, which over a paused one was forever.
        showOperator()
        setPhoneWatch(true)
        startFr24()
    }

    override fun onPause() {
        super.onPause()
        clock_text.removeCallbacks(clockTicker)
        terrain3D?.onPause()
        map?.onPause()
        this.sensorTimeoutManager.pause();
        this.logPlayer?.stop();
        stopFr24(clear = false)
        updateFullscreenState()//check if user has brought system ui with swipe
        // The service keeps both location and compass for a connected flight;
        // this only removes callbacks to a screen that is no longer drawing.
        setPhoneWatch(false)
    }

    override fun onStop() {
        super.onStop()
        map?.onStop()
        this.logPlayer?.stop();
        this.sensorTimeoutManager.pause();
    }

    private fun connectBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            this.showDialog(
                AlertDialog.Builder(this)
                    .setMessage("It seems like your phone does not have bluetooth, or it does not supported")
                    .setPositiveButton("OK", null)
                    .create()
            )
            return
        }

        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }
        if (preferenceManager.isLoggingEnabled()) {
            if (!requestWritePermission(RequestWritePermissionSequenceType.CONNECT)) return;
        }

        val devices = ArrayList<BluetoothDevice>(adapter.bondedDevices)
        var deviceNames = ArrayList<String>(devices.map {
            var result = it.name
            if (result == null) {
                result = it.address
            }
            if (result == null) {
                result = "*noname*"
            }
            result
        })

        deviceNames = augmentNonUniqueDiviceNames(deviceNames, devices.map { i-> i.address })

        var deviceNames1 = deviceNames.mapIndexed { index, i ->
            if ( devices[index].address == lastSelectedBluetoothDeviceAddress ) {
                val boldOption = SpannableString(i)
                boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, i.length, 0)
                boldOption
            } else {
                i
            }
        }.toMutableList()

        val deviceAdapter = ArrayAdapter( this, android.R.layout.simple_list_item_1, deviceNames1)

        var dialog = AlertDialog.Builder(this).setOnDismissListener {
        } .setNeutralButton(R.string.pair_new_device) { dialog, which ->
            showPairDeviceDialog()
        }.setAdapter(deviceAdapter) { _, i ->
            lastSelectedBluetoothDeviceAddress = devices[i].address;
            preferenceManager.setLastSelectedBluetoothDeviceAddress(lastSelectedBluetoothDeviceAddress)
            runOnUiThread {
                connectToBluetoothDevice(devices[i], false)
            }
        }.create()

        dialog.setOnShowListener {
            val alertDialog = it as AlertDialog
            var index = devices.indexOfFirst {i -> i.address == lastSelectedBluetoothDeviceAddress}
            if ( index != -1) {
                val centerY = alertDialog.listView.height / 2 // Calculate the center position vertically
                alertDialog.listView.smoothScrollToPositionFromTop(index, centerY)
            }
        }

        this.showDialog(dialog)
    }

    private fun augmentNonUniqueDiviceNames(deviceNames : ArrayList<String>, deviceAddr : List<String>) : ArrayList<String>
    {
        return ArrayList(deviceNames.mapIndexed { index, i ->
            var i1 = deviceNames.indexOf(i)
            var i2 = deviceNames.lastIndexOf(i)
            if (i1 != i2) {
                "${deviceNames[index]} (${deviceAddr[index]})"
            } else {
                i
            }
        })
    }

    private fun showPairDeviceDialog() {
        val devices = ArrayList<BluetoothDevice>()
        val deviceNames = ArrayList<String>()
        val deviceAdapter =
            ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, deviceNames)
        AlertDialog.Builder(this)
            .setAdapter(deviceAdapter) { _, i ->
                BluetoothAdapter.getDefaultAdapter().cancelDiscovery()
                pairDevice(devices[i])
            }.show()
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        unregisterReceiver(this)
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        val device =
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)!!
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                            ?: device.address
                        if (!deviceNames.contains(name) && device.bondState == BluetoothDevice.BOND_NONE) {
                            devices.add(device)
                            deviceNames.add(name)
                            deviceAdapter.notifyDataSetChanged()
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {

                    }
                }
            }
        }
        registerReceiver(listener, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
        })
        BluetoothAdapter.getDefaultAdapter().startDiscovery()
    }

    private fun pairDevice(bluetoothDevice: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (!bluetoothDevice.createBond()) {
                Toast.makeText(this, "Failed to pair bluetooth device", Toast.LENGTH_LONG).show()
            } else {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            val newBondState: Int =
                                intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE,
                                    BluetoothDevice.BOND_NONE
                                )
                            if (newBondState == BluetoothDevice.BOND_BONDED) {
                                device?.let { connectToBluetoothDevice(it, false) }
                                unregisterReceiver(this)
                            } else if (newBondState == BluetoothDevice.BOND_NONE) {
                                Toast.makeText(
                                    this@MapsActivity,
                                    "Failed to pair new device",
                                    Toast.LENGTH_LONG
                                ).show()
                                unregisterReceiver(this)
                            }
                        }
                    }
                }

                registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            }
        } else {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.pair_not_supported_message))
                .show()
        }
    }

    private fun connectBluetoothLE() {
        if (!bleCheck()) {
            Toast.makeText(
                this,
                "Bluetooth LE is not supported or application does not have needed permissions",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            this.showDialog(
                AlertDialog.Builder(this)
                    .setMessage("It seems like your phone does not have bluetooth, or it does not supported")
                    .setPositiveButton("OK", null)
                    .create()
            )
            return
        }

        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }
        if (preferenceManager.isLoggingEnabled()) {
            if (!requestWritePermission(RequestWritePermissionSequenceType.CONNECT)) return;
        }

        val devices = ArrayList<BluetoothDevice>(adapter.bondedDevices)
        var deviceNames = ArrayList<String>(devices.map {
            var result = it.name
            if (result == null) {
                result = it.address
            }
            if (result == null) {
                result = "*noname*"
            }
            result
        })

        deviceNames = augmentNonUniqueDiviceNames(deviceNames, devices.map {i -> i.address})

        var deviceNames1 = deviceNames.mapIndexed { index, i ->
            if ( devices[index].address == lastSelectedBLEDeviceAddress ) {
                val boldOption = SpannableString(i)
                boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, i.length, 0)
                boldOption
            } else {
                i
            }
        }.toMutableList()

        val deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames1)

        var scrolled = false;
        var dialog: AlertDialog? = null;

        val callback = BluetoothAdapter.LeScanCallback { bluetoothDevice, i, bytes ->
            if (!devices.contains(bluetoothDevice) && bluetoothDevice.name != null) {
                devices.add(bluetoothDevice)
                var name1 = bluetoothDevice.name
                if ( deviceNames.indexOf( name1) >= 0 ) {
                    name1 = "${bluetoothDevice.name} (${bluetoothDevice.address})"
                }
                if ( lastSelectedBLEDeviceAddress == bluetoothDevice.address) {
                    val boldOption = SpannableString(name1)
                    boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, name1.length, 0)
                    deviceNames1.add(boldOption)

                    if ( dialog is AlertDialog && scrolled) {
                        runOnUiThread {
                            var index = devices.indexOfFirst {i -> i.address == lastSelectedBLEDeviceAddress}
                            if ( index != -1) {
                                val alertDialog = dialog as AlertDialog
                                if ( alertDialog != null ) {
                                    val centerY =
                                        alertDialog.listView.height / 2 // Calculate the center position vertically
                                    alertDialog.listView.smoothScrollToPositionFromTop(
                                        index,
                                        centerY
                                    )
                                }
                            }
                        }
                    }
                }
                else {
                    deviceNames1.add(name1)
                }
                deviceAdapter.notifyDataSetChanged()
            }
        }

        if (bleCheck()) {
            adapter.startLeScan(callback)
        }

        dialog = AlertDialog.Builder(this).setOnDismissListener {
            if (bleCheck()) {
                adapter.stopLeScan(callback)
            }
        }.setAdapter(deviceAdapter) { _, i ->
            lastSelectedBLEDeviceAddress = devices[i].address;
            preferenceManager.setLastSelectedBLEDeviceAddress(lastSelectedBLEDeviceAddress)
            if (bleCheck()) {
                adapter.stopLeScan(callback)
            }
            runOnUiThread {
                connectToBluetoothDevice(devices[i], true)
            }
        }.create()

        dialog.setOnShowListener {
            val alertDialog = it as AlertDialog
            var index = devices.indexOfFirst {i -> i.address == lastSelectedBLEDeviceAddress}
            if ( index != -1) {
                val centerY = alertDialog.listView.height / 2 // Calculate the center position vertically
                alertDialog.listView.smoothScrollToPositionFromTop(index, centerY)
            }
            scrolled = true;
        }

        this.showDialog(dialog)
    }

    private fun resetUI() {
        satellites.text = "0"
        rssi.text = "-"
        this.setRSSIIcon(100)
        voltage.text = "-"
        phoneBattery.text = "-"
        current.text = "-"
        fuel.text = "-"
        this.setFuelIcon(-1);
        altitude.text = "-"
        altitude_msl.text = "-"
        speed.text = "-"
        airspeed.text = "-"
        vspeed.text = "-"
        distance.text = "-"
        traveled_distance.text = "0 m"
        this.lastTraveledDistance = 0.0;
        mode.text = "Disconnected"
        statustext.text = "";
        dnSnr.text = "-"
        upSnr.text = "-"
        dnLq.text = "-"
        elrsRate.text = "-"
        this.setDNLQIcon(100)
        upLq.text = "-"
        this.setUPLQIcon(100)
        ant.text = "-"
        power.text = "-"
        rssiDbm1.text = "-"
        this.setRssiDbm1Icon(0)
        rssiDbm2.text = "-"
        this.setRssiDbm2Icon(0)
        rssiDbmd.text = "-"
        this.setRssiDbmdIcon(0)
        horizonView.snapLevel()
        cell_voltage.text = "-"
        this.lastCellVoltage = 0.0f;
        throttle.text = "-"
        tlmRate.text = "0 b/s"
    }

    private fun bleCheck() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED


    private fun connectToBluetoothDevice(device: BluetoothDevice, isBLE: Boolean) {
        clearCrsfSystem()
        if ( isBLE ) {
            lastConnectionType = CONNTYPE_BLE;
        }
        else {
            lastConnectionType = CONNTYPE_BT;
        }
        reconnectionStartTime = 0;
        reconnectOnFailure = false;

        startDataService()
        dataService?.let {
            connectButton.text = getString(R.string.connecting)
            connectButton.isEnabled = false
            lastBluetoothDevice = device;
            it.connect(device, isBLE)
        }
    }

    private fun reconnectToBluetoothDevice() {
        if (
            (lastBluetoothDevice != null) &&
            ( (lastConnectionType == CONNTYPE_BT) || (lastConnectionType == CONNTYPE_BLE))
        ) {
            if ( preferenceManager.getConnectionVoiceMessagesEnabled()) {
                soundPool!!.play(reconnectingSoundId, 1f, 1f, 0, 0, 1f)
            }

        startDataService()
        dataService?.let {
            connectButton.text = getString(R.string.reconnecting)
            connectButton.isEnabled = false
            if ( lastConnectionType == CONNTYPE_BLE) {
                it.connect(lastBluetoothDevice as BluetoothDevice, true)
                } else if (lastConnectionType == CONNTYPE_BT) {
                it.connect(lastBluetoothDevice as BluetoothDevice, false)
            }
        }
    }
    }

    private fun reconnectToNetwork() {
        if (lastConnectionType != CONNTYPE_NET || lastNetworkPort == 0) {
            return
        }
        // A retry is scheduled five seconds out, which is long enough for
        // someone to have got there first. Without this it would tear down the
        // link they just made and start again.
        if (dataService?.isConnected() == true) {
            return
        }
        if (preferenceManager.getConnectionVoiceMessagesEnabled()) {
            soundPool!!.play(reconnectingSoundId, 1f, 1f, 0, 0, 1f)
        }
        startDataService()
        dataService?.let {
            connectButton.text = getString(R.string.reconnecting)
            connectButton.isEnabled = false
            sensorTimeoutManager.setTimeoutWindow(
                if (lastNetworkHighLatency) SensorTimeoutManager.HIGH_LATENCY_TIMEOUT_MS
                else SensorTimeoutManager.DEFAULT_TIMEOUT_MS)
            it.connect(lastNetworkHost, lastNetworkPort, lastNetworkMode,
                lastNetworkHighLatency)
        }
    }

    private fun connectToUSBDevice(
        port: UsbSerialPort,
        connection: UsbDeviceConnection
    ) {
        clearCrsfSystem()
        lastConnectionType = CONNTYPE_USB;
        startDataService()
        dataService?.let {
            connectButton.text = getString(R.string.connecting)
            connectButton.isEnabled = false
            it.connect(port, connection)
        }
    }

    /**
     * Where the telemetry is coming from, for the presets.
     *
     * The two that matter are ExpressLRS, which broadcasts MAVLink to UDP 14550
     * so there is nothing to address, and TBS Crossfire, whose WiFi module is a
     * server on TCP 8888 and can be switched to UDP on the module itself. Both
     * ports are configurable at their end, so nothing here is fixed — a preset
     * only fills the fields in.
     */
    private class NetworkPreset(
        val label: String,
        /**
         * The stored identity: remembered ports, hosts and the last-used
         * preset are saved under this number, never under the list position —
         * so presets can be ordered for the eye without handing anyone's
         * settings to a neighbour. Keys match the positions of the releases
         * that stored them; a new preset takes the next unused number,
         * wherever it sits in the list.
         */
        val key: Int,
        val useTcp: Boolean,
        val port: Int,
        val useGateway: Boolean,
        /** a fixed address, where the preset knows it */
        val host: String? = null,
        /** transport, matching the order of the transport spinner */
        val mode: Int = if (useTcp) NetworkDataPoller.MODE_TCP_CLIENT else NetworkDataPoller.MODE_UDP,
        /** MAVLink High Latency: pin the protocol and send the enable command */
        val highLatency: Boolean = false
    )

    // The transport stays in the name because it is the thing that decides
    // whether an address is needed at all. The port does not: it lands in the
    // port field the moment the preset is picked.
    private val networkPresets = listOf(
        NetworkPreset("ExpressLRS backpack (UDP)", 0, false, 14550, false),
        NetworkPreset("TBS Crossfire / Tracer (TCP)", 1, true, 8888, true),
        NetworkPreset("TBS Crossfire / Tracer (UDP)", 2, false, 8888, false),
        NetworkPreset("MAVLink router / ground station (UDP)", 3, false, 14550, false),
        // A satellite- or LoRa-class link: one HIGH_LATENCY2 message per five
        // seconds. The autopilot boots with that stream off, so this preset
        // also sends the command that turns it on — to the typed address, and
        // to whoever speaks to us.
        NetworkPreset("MAVLink High Latency (UDP)", 7, false, 14550, false,
            highLatency = true),
        NetworkPreset("Serial to Wi-Fi bridge (TCP)", 4, true, 23, true),
        // The one path into a Crossfire WiFi module that every firmware
        // serves: its own phone app uses MQTT, which needs a broker in the app
        // and is broken on the newest firmware, while this carries plain CRSF.
        NetworkPreset(
            "TBS Crossfire WiFi (WebSocket)", 5, true, 80, true,
            mode = NetworkDataPoller.MODE_WEBSOCKET
        ),
        NetworkPreset("Custom", 6, false, 14550, false)
    )

    private fun connectNetwork() {
        // as the Bluetooth and BLE paths do: without it a network session
        // silently records nothing while both logging switches say "on"
        if (preferenceManager.isLoggingEnabled()) {
            if (!requestWritePermission(RequestWritePermissionSequenceType.CONNECT)) return
        }

        val binder = WifiNetworkBinder(this)
        val view = layoutInflater.inflate(R.layout.dialog_network, null)
        var dialogOpen = true

        val presetSpinner = view.findViewById<Spinner>(R.id.network_preset)
        val transportSpinner = view.findViewById<Spinner>(R.id.network_transport)
        val hostField = view.findViewById<EditText>(R.id.network_host)
        val hostLabel = view.findViewById<TextView>(R.id.network_host_label)
        val portField = view.findViewById<EditText>(R.id.network_port)
        val wifiStatus = view.findViewById<TextView>(R.id.network_wifi_status)
        val hint = view.findViewById<TextView>(R.id.network_hint)
        val interfaceSpinner = view.findViewById<Spinner>(R.id.network_interface)
        val findButton = view.findViewById<Button>(R.id.network_find)
        val portDefaultButton = view.findViewById<Button>(R.id.network_port_default)

        val transports = arrayOf(
            "UDP listen", "TCP client", "TCP server (wait)", "TBS WebSocket"
        )

        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            networkPresets.map { it.label })
        transportSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, transports)

        // Which network to work on. The phone can be on two at once — mobile
        // data plus a hotspot that the module has joined — and in that case the
        // module is a client of this phone, so the gateway is the phone itself
        // and tells us nothing about where the module is.
        val interfaces = LocalNetworks.list(binder.cellularInterfaceNames())
        val interfaceLabels = ArrayList<String>()
        interfaceLabels.add(getString(R.string.network_interface_auto))
        interfaces.forEach { interfaceLabels.add(it.label()) }
        interfaceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, interfaceLabels)

        // Whether we are on Wi-Fi and what it is called are two different
        // questions on a modern Android: the network is visible through
        // ConnectivityManager, but the SSID needs location permission and
        // location switched on. Treating an unreadable name as "no Wi-Fi"
        // would put a wrong warning in front of someone whose setup is fine.
        val ssid = binder.ssid()
        val hotspot = interfaces.firstOrNull { it.likelyHotspot }
        wifiStatus.text = when {
            // A hotspot is not a Network as far as ConnectivityManager is
            // concerned, so asking it whether we are "on Wi-Fi" says no even
            // though the module is happily connected to this phone.
            hotspot != null -> "Sharing a hotspot on " + hotspot.address
            !binder.hasWifi() -> getString(R.string.network_no_wifi)
            ssid != null -> getString(R.string.network_on_wifi, ssid)
            else -> getString(R.string.network_on_wifi_unknown)
        }

        // A UDP listen binds a local port and never needs the module's address,
        // which is the whole reason the UDP presets are offered first.
        fun updateHostEnabled() {
            // whatever dials out needs somewhere to dial: a TCP client and a
            // WebSocket both do, a UDP listen and a TCP server do not
            val chosen = transportSpinner.selectedItemPosition
            val tcp = chosen == NetworkDataPoller.MODE_TCP_CLIENT ||
                chosen == NetworkDataPoller.MODE_WEBSOCKET
            // The high-latency preset needs somewhere to send its enable
            // command even on a UDP listen: an autopilot with the stream off
            // sends nothing, so there is no sender to learn an address from.
            val highLatency = networkPresets.getOrNull(
                presetSpinner.selectedItemPosition)?.highLatency == true
            hostField.isEnabled = tcp || highLatency
            hostLabel.isEnabled = tcp || highLatency
            // Greying the field out on its own only raises the question "why
            // can I not type here" — so the label answers it.
            hostLabel.text = when {
                tcp -> getString(R.string.network_host)
                highLatency -> getString(R.string.network_host_hl)
                else -> getString(R.string.network_host_unused)
            }
            hostField.hint = when {
                tcp -> getString(R.string.network_host_hint)
                highLatency -> getString(R.string.network_host_hint_hl)
                else -> getString(R.string.network_host_hint_udp)
            }
            // nothing to find on loopback: it is a single address, this device
            findButton.isEnabled = tcp && !hostField.text.toString().trim().startsWith("127.")
            hint.text = when {
                tcp -> getString(R.string.network_hint_tcp)
                highLatency -> getString(R.string.network_hint_hl)
                else -> getString(R.string.network_hint_udp)
            }
        }

        fun applyPreset(index: Int) {
            val preset = networkPresets[index]
            transportSpinner.setSelection(preset.mode)
            // the port this preset was last used with, not the documented one:
            // modules do get moved off their default
            portField.setText(
                preferenceManager.getNetworkPortFor(preset.key, preset.port).toString())
            if (preset.host != null) {
                hostField.setText(preset.host)
            } else if (preset.useGateway) {
                val gateway = binder.gatewayAddress()
                if (gateway != null) hostField.setText(gateway)
            }
            updateHostEnabled()
        }

        // restore what was used last; the saved value is a preset key, which
        // by construction equals the list position it had when it was stored
        val savedPreset = preferenceManager.getNetworkPreset()
        transportSpinner.setSelection(preferenceManager.getNetworkMode())
        // Reopening is restoring the last session, so the fallback is the port
        // that session used — not the preset's documented default. Falling back
        // to the default threw away a port that had been typed and connected
        // with, which is exactly the one worth keeping. (Switching preset is a
        // different question, and applyPreset answers it differently.)
        portField.setText(
            preferenceManager.getNetworkPortFor(
                savedPreset, preferenceManager.getNetworkPort()
            ).toString())
        // The address is remembered per network, the same way the port is
        // remembered per preset: a module is 10.0.0.1 on its own access point
        // and something else on a home network, so a single remembered address
        // was wrong every time you moved between the two.
        val network = binder.ssid() ?: ""
        val savedHost = preferenceManager.getNetworkHostFor(
            network, savedPreset, preferenceManager.getNetworkHost()
        )
        hostField.setText(if (savedHost.isEmpty()) (binder.gatewayAddress() ?: "") else savedHost)
        val savedPosition = networkPresets.indexOfFirst { it.key == savedPreset }
        if (savedPosition >= 0) presetSpinner.setSelection(savedPosition)
        if (!preferenceManager.getNetworkPinWifi() && interfaces.isNotEmpty()) {
            // reopen on the interface the user picked last time, where it still exists
            val hotspotIndex = interfaces.indexOfFirst { it.likelyHotspot }
            if (hotspotIndex >= 0) interfaceSpinner.setSelection(hotspotIndex + 1)
        }
        updateHostEnabled()

        // A Spinner delivers its current selection to a newly attached listener,
        // and whether that happens at all depends on the layout pass — so a
        // one-shot "ignore the first callback" flag either swallows the user's
        // first real choice or lets the initial one through. Comparing against
        // what was set programmatically is not timing dependent: the echo has
        // the same position and does nothing, a real change does not.
        // Picking a network says where to look on it. On a hotspot the phone is
        // the gateway, so the module is a client somewhere in the subnet and the
        // useful thing to offer is the subnet itself, ready for the last octet
        // or for Find. On a joined network the gateway is usually the module.
        fun applyInterface(pos: Int) {
            val idx = pos - 1
            if (idx < 0 || idx >= interfaces.size) return
            val iface = interfaces[idx]
            val fill = when {
                // this device is a single address, not a subnet to search
                iface.loopback -> iface.address
                iface.likelyHotspot -> iface.subnet24()
                else -> binder.gatewayAddress() ?: iface.subnet24()
            }
            hostField.setText(fill)
            hostField.setSelection(hostField.text.length)
        }

        var appliedIface = interfaceSpinner.selectedItemPosition
        interfaceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == appliedIface) return
                appliedIface = pos
                applyInterface(pos)
            }
        }

        var appliedPreset = presetSpinner.selectedItemPosition
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == appliedPreset) return
                appliedPreset = pos
                applyPreset(pos)
            }
        }
        transportSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateHostEnabled()
            }
        }

        portDefaultButton.setOnClickListener {
            val index = presetSpinner.selectedItemPosition
            val preset = networkPresets.getOrNull(index)
            if (preset == null) return@setOnClickListener
            preferenceManager.clearNetworkPortFor(index)
            portField.setText(preset.port.toString())
            hint.text = getString(R.string.network_port_reset, preset.port)
        }

        // Finding a module that joined this phone's hotspot: there is no
        // gateway to ask, so ask every address on the subnet whether it is
        // serving telemetry on the chosen port.
        findButton.setOnClickListener {
            val chosen = interfaceSpinner.selectedItemPosition - 1
            val iface = if (chosen >= 0 && chosen < interfaces.size) {
                interfaces[chosen]
            } else {
                interfaces.firstOrNull { it.likelyHotspot }
                    ?: interfaces.firstOrNull { it.name.startsWith("wlan") }
                    ?: interfaces.firstOrNull()
            }
            val port = portField.text.toString().trim().toIntOrNull() ?: 0
            if (iface == null || port !in 1..65535) {
                Toast.makeText(this, "Pick a network and a valid port first", Toast.LENGTH_LONG)
                    .show()
                return@setOnClickListener
            }
            val findLabel = findButton.text
            findButton.isEnabled = false

            findButton.text = getString(R.string.network_searching_short)
            hint.text = getString(R.string.network_searching, iface.subnet24() + "x")

            AsyncTask.execute {
                LocalNetworks.scan(iface, port, 300, { done, total ->
                    runOnUiThread {
                        if (!dialogOpen || isFinishing) return@runOnUiThread
                        hint.text = getString(
                            R.string.network_searching_progress,
                            iface.subnet24() + "x", done, total
                        )
                    }
                }) { hits ->
                    runOnUiThread {
                        // the scan outlives the dialog, so a late result must
                        // not put a chooser up over the map
                        if (!dialogOpen || isFinishing) return@runOnUiThread
                        findButton.isEnabled = true
                        findButton.text = findLabel
                        when {
                            hits.isEmpty() -> hint.text = getString(R.string.network_found_none)
                            hits.size == 1 -> {
                                hostField.setText(hits[0])
                                hint.text = getString(R.string.network_found_one, hits[0])
                            }
                            else -> {
                                // more than one thing is listening on that port,
                                // so say so and let the user choose rather than
                                // silently picking one
                                hint.text = getString(R.string.network_found_many, hits.size)
                                showDialog(
                                    AlertDialog.Builder(this)
                                        .setTitle(R.string.network_found_title)
                                        .setItems(hits.toTypedArray()) { d, which ->
                                            hostField.setText(hits[which])
                                            hint.text =
                                                getString(R.string.network_found_one, hits[which])
                                            d.dismiss()
                                        }
                                        .create()
                                )
                            }
                        }
                    }
                }
            }
        }

        this.showDialog(
            AlertDialog.Builder(this)
                .setOnDismissListener { dialogOpen = false }
                .setTitle(R.string.network_title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.network_connect) { _, _ ->
                    val mode = transportSpinner.selectedItemPosition
                    val useTcp = mode == NetworkDataPoller.MODE_TCP_CLIENT ||
                        mode == NetworkDataPoller.MODE_WEBSOCKET
                    val port = portField.text.toString().trim().toIntOrNull() ?: 0
                    val host = hostField.text.toString().trim()

                    if (port !in 1..65535) {
                        Toast.makeText(this, "Port must be between 1 and 65535", Toast.LENGTH_LONG)
                            .show()
                        return@setPositiveButton
                    }
                    if (useTcp && host.isEmpty()) {
                        Toast.makeText(this, "TCP needs the module's address", Toast.LENGTH_LONG)
                            .show()
                        return@setPositiveButton
                    }

                    // "Automatic" is position 0; anything else is an explicit
                    // interface, which means do not force the socket onto Wi-Fi
                    // stored under the preset's key, not its list position:
                    // the list is ordered for the eye and may be reordered
                    val chosenPreset = networkPresets[presetSpinner.selectedItemPosition]
                    preferenceManager.setNetworkPinWifi(
                        interfaceSpinner.selectedItemPosition == 0)
                    preferenceManager.setNetworkPreset(chosenPreset.key)
                    preferenceManager.setNetworkUseTcp(useTcp)
                    preferenceManager.setNetworkMode(mode)
                    preferenceManager.setNetworkHost(host)
                    preferenceManager.setNetworkHostFor(
                        binder.ssid() ?: "", chosenPreset.key, host)
                    preferenceManager.setNetworkPort(port)
                    preferenceManager.setNetworkPortFor(chosenPreset.key, port)

                    // Only from the preset that means it, and only over UDP —
                    // switching the transport away from what the preset set is
                    // choosing a different thing.
                    val highLatency = chosenPreset.highLatency &&
                        mode == NetworkDataPoller.MODE_UDP
                    connectToNetwork(host, port, mode, highLatency)
                }
                .create())
    }

    /** Called when a link is started by hand: none of the previous one carries over. */
    /**
     * A flight, and everything drawn from it, forgotten together.
     *
     * For wherever one ends and another begins: a link coming up, a replay
     * starting, a replay jumping back to somewhere earlier, the decoder
     * restarting at the end of one. These had drifted apart — each cleared a
     * different handful of things, and whatever it missed was left drawn over
     * the flight that followed. They are one thing to forget, so they are
     * forgotten in one place.
     */
    private fun forgetFlight() {
        // and the heading the flight claimed: chase engaged after a replay
        // closed was rotating to the dead flight's last heading — "chases
        // somewhere" with no plane on the sky
        modelHeadingKnown = false
        juricabi.com.telemetry.gl.LiveFlightPath.clear()
        // and anything gathered towards drawing it, wherever the forgetting
        // came from: a rewind, a new link, or leaving the replay altogether
        gatheredPoints.clear()
        gatheredHeights.clear()
        gatheredHeight = Float.NaN
        polyLine?.clear()
        flightHeadLine?.clear()
        pendingVisualTrack.clear()
        recentVisualTrack.clear()
        publishedVisualTrack.clear()
        gatheredVisualBatch = null
        // The world behind the map is this flight's too. It used to be thrown
        // away wherever a flight ended, so it could not go stale; now that it
        // is kept, a parked world told nothing draws the dead flight again on
        // the next switch to 3D. At most one of the two exists — adopting
        // nulls the parked one, parking nulls the live one.
        (terrain3D ?: parked3D)?.onFlightReset()
        lastTraveledDistance = 0.0
        flightAltitude.clear()
        isArmed = false
        gotArmedState = false
        lastRememberedHeight = Float.NaN
        forgetModel()
    }

    /**
     * The model and the lines that start at it.
     *
     * Separate from the flight, because leaving a replay or losing a link ends
     * the model without ending the flight — what was flown is worth looking at
     * afterwards.
     */
    private fun forgetModel() {
        lastGPS = Position(0.0, 0.0)
        // Not whether there is a fix: that belongs to the link, not to the
        // model, and the replay's own handler will not take a single point
        // while it is false — so forgetting it here threw away the flight that
        // was being rewound to.
        shownLat = Double.NaN
        shownLon = Double.NaN
        shownMarkerHeading = Float.NaN
        presentedLat = Double.NaN
        presentedLon = Double.NaN
        presentedMarkerHeading = Float.NaN
        presentedMoment = 0L
        submittedLat = Double.NaN
        submittedLon = Double.NaN
        submittedMarkerHeading = Float.NaN
        seenFixes.clear()
        walkDelayMs = 80L
        headingPolyline?.clear()
        homeLine?.clear()
        // or its stale segment outlives the replay: with the model gone the
        // next update returns before it can clear
        operatorLine?.clear()
    }

    private fun clearCrsfSystem() {
        GhstProtocol.forgetLaunchAltitude()
        // A reconnect continues the same flight and does not come through
        // here; a connection asked for by hand starts a new altitude frame.
        juricabi.com.telemetry.gl.AltitudeFrame.forget()
        detectedCells = 0
        highestPackVoltage = 0f
        cellsAsked = false
        cellsAnswered = false
        // forgotten before the ground is started again, or it is started on the
        // flight that has just been thrown away
        forgetFlight()
        startFlightIn3D()
        crsfSystem = null
        // else the next link would redraw the old rate under its own table
        lastRfMode = null
    }

    private fun connectToNetwork(host: String, port: Int, mode: Int,
                                 highLatency: Boolean = false) {
        clearCrsfSystem()
        // connect() clears this before the chooser opens, so every transport
        // has to set it again or it becomes silently non-reconnectable
        lastConnectionType = CONNTYPE_NET;
        lastNetworkHost = host
        lastNetworkPort = port
        lastNetworkMode = mode
        lastNetworkHighLatency = highLatency
        reconnectionStartTime = 0;
        reconnectOnFailure = false;

        // One message per five seconds against a ten second sensor window is
        // one dropped frame from greying out — widen it; and set it every
        // connect, so no ordinary link inherits the slack.
        sensorTimeoutManager.setTimeoutWindow(
            if (highLatency) SensorTimeoutManager.HIGH_LATENCY_TIMEOUT_MS
            else SensorTimeoutManager.DEFAULT_TIMEOUT_MS)

        startDataService()
        dataService?.let {
            connectButton.text = getString(R.string.connecting)
            connectButton.isEnabled = false
            it.connect(host, port, mode, highLatency)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logPlayer?.dispose()
        logPlayer = null
        // A 3D scene owns terrain workers and the largest bitmaps in the app.
        // The normal 3D -> 2D switch releases it, but destroying the screen
        // while 3D was still open did not: the abandoned Activity then stayed
        // reachable until every tile and image had finished loading.
        terrain3D?.release()
        terrain3D = null
        parked3D?.release()
        parked3D = null
        tts?.shutdown()
        tts = null
        ttsReady = false
        headingPolyline = null;
        polyLine = null;
        flightPlanLines.clear()
        map?.onDestroy()
        if (!isChangingConfigurations) {
            dataService?.setDataListener(null)
            // A replay cannot outlive the screen playing it, and what it flew
            // is kept where the process can reach it rather than on this
            // screen — so that a second window onto the same flight, the
            // ground view, sees the points without being told.
            //
            // Nothing was throwing it away. Swiping the app off the recents
            // list ends this screen but not the process, which the data
            // service holds open, so the flight just replayed was still there
            // to be found: opening the app again drew the last recording
            // played as though it were flying now. A live flight is the
            // service's and outlives this screen on purpose; a replay is only
            // ever this screen's.
            if (isInReplayMode()) {
                juricabi.com.telemetry.gl.LiveFlightPath.clear()
                juricabi.com.telemetry.gl.AltitudeFrame.forget()
            }
        }
        map = null;
        this.unregisterReceiver(this.batInfoReceiver)
        if (dataServiceBound) {
            unbindService(serviceConnection)
            dataServiceBound = false
        }
    }

    private fun startDataService() {
        // Every connect and reconnect comes through here. Binding each time
        // leaked unmatched bindings, while starting both a foreground and an
        // ordinary service delivered onStartCommand twice. This Activity keeps
        // one bound, started service; the service promotes itself only when a
        // telemetry link is actually connected.
        if (dataServiceBound) return
        val intent = Intent(this, DataService::class.java)
        startService(intent)
        dataServiceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty()) {
            if (requestCode == REQUEST_LOCATION_PERMISSION) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    map?.isMyLocationEnabled = true
                } else {
                    this.showDialog(AlertDialog.Builder(this)
                        .setMessage("Location permission is needed in order to discover BLE devices and show your location on map")
                        .setPositiveButton("OK", null)
                        .create())
                }
            } else if (requestCode == REQUEST_WRITE_PERMISSION) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    when (requestWritePermissionSequence) {
                        RequestWritePermissionSequenceType.CONNECT -> connect()
                        RequestWritePermissionSequenceType.DELETE -> showDeleteLogDialog()
                        RequestWritePermissionSequenceType.LOG_PICKER -> replay()
                        RequestWritePermissionSequenceType.RENAME -> showRenameLogDialog()
                        RequestWritePermissionSequenceType.EXPORT_GPX -> showExportGPXDialog()
                        RequestWritePermissionSequenceType.EXPORT_KML -> showExportKMLDialog1()
                        // nothing was waiting on the permission
                        RequestWritePermissionSequenceType.NONE -> {}
                    }
                    requestWritePermissionSequence = RequestWritePermissionSequenceType.NONE;
                } else {
                    this.showDialog(
                        AlertDialog.Builder(this)
                            .setMessage("Write permission is required in order to log telemetry data. Disable logging or grant permission to continue")
                            .setPositiveButton("OK", null)
                            .create()
                    )
                }
            } else if (requestCode == REQUEST_READ_PERMISSION) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    replay()
                } else {
                    this.showDialog(
                        AlertDialog.Builder(this)
                            .setMessage("Read permission is required in order to read and replay telemetry data")
                            .setPositiveButton("OK", null)
                            .create()
                    )
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_OK) {
            connectBluetooth()
        }
    }

    override fun onVSpeedData(vspeed: Float) {
        this.sensorTimeoutManager.onVSpeedData(vspeed);
        runOnUiThread {
            this.vspeed.text = "${"%.1f".format(vspeed)} m/s"
        }
    }

    override fun onThrottleData(throttle: Int) {
        this.sensorTimeoutManager.onThrottleData(throttle);
        runOnUiThread {
            this.throttle.text = throttle.toString();
        }
    }

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

    override fun onAltitudeData(altitude: Float) {
        flightAltitude.onFallback(altitude)
        this.sensorTimeoutManager.onAltitudeData(altitude);
        showAltitude(altitude, false)
    }

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

    private fun showAltitude(metres: Float, msl: Boolean) {
        if (msl) altitudeMslShown = metres else altitudeShown = metres
        if (altitudePosted) return
        altitudePosted = true
        runOnUiThread {
            altitudePosted = false
            if (!altitudeShown.isNaN()) this.altitude.text = formatHeight(altitudeShown)
            if (!altitudeMslShown.isNaN()) this.altitude_msl.text = formatHeight(altitudeMslShown)
        }
    }

    override fun onGPSAltitudeData(altitude: Float) {
        this.sensorTimeoutManager.onGPSAltitudeData(altitude);
        flightAltitude.onGps(altitude)
        showAltitude(altitude, true)
    }

    override fun onDistanceData(distance: Int) {
        this.sensorTimeoutManager.onDistanceData(distance)
        runOnUiThread {
            this.distance.text = this.formatDistance(distance.toFloat());
        }
    }

    override fun onRollData(rollAngle: Float) {
        lastRoll = rollAngle
        runOnUiThread {
            horizonView.setRoll(rollAngle)
            terrain3D?.setModelAttitude(lastHeading, lastPitch, lastRoll)
        }
    }

    override fun onPitchData(pitchAngle: Float) {
        lastPitch = pitchAngle
        runOnUiThread {
            horizonView.setPitch(pitchAngle)
            terrain3D?.setModelAttitude(lastHeading, lastPitch, lastRoll)
        }
    }

    override fun onGSpeedData(speed: Float) {
        this.sensorTimeoutManager.onGSpeedData(speed)
        runOnUiThread {
            this.speed.text = "${speed.roundToInt()} km/h"
        }
    }

    override fun onAirSpeedData(speed: Float) {
        this.sensorTimeoutManager.onAirSpeedData(speed)
        runOnUiThread {
            this.airspeed.text = "${speed.roundToInt()} km/h"
        }
    }

    override fun onRCChannels(rcChannels: IntArray) {
        this.sensorTimeoutManager.onRCChannels(rcChannels)
        runOnUiThread {
            this.rc_widget.setChannels(rcChannels)
        }
    }

    override fun onStatusText(message: String) {
        this.sensorTimeoutManager.onStatusText(message)
        runOnUiThread {
            this.statustext.text = message;
        }
    }

    override fun onGPSState(satellites: Int, gpsFix: Boolean) {
        this.sensorTimeoutManager.onGPSState(satellites, gpsFix)
        runOnUiThread {
            // Nothing is feeding this screen, so this was decoded before the
            // link went and posted after: it belongs to a flight that has
            // ended. See onGPSData, which it would otherwise re-arm.
            if (isIdle()) return@runOnUiThread
            this.hasGPSFix = gpsFix
            this.tryCreateMarker()
            this.satellites.text = if (satellites == 99) "ES" else satellites.toString()
        }
    }

    /** The flight against the ground under it, which is what shows clearance. */
    private fun showAltitudeProfile() {
        // Only the fixes that carried a height: this draws a flight against the
        // ground under it, which a heightless fix has nothing to say about.
        val flown = flightPath.filter { !it.altitudeMsl.isNaN() }
        if (flown.size < 2) {
            Toast.makeText(this, "No flight with position and altitude yet", Toast.LENGTH_SHORT).show()
            return
        }
        juricabi.com.telemetry.utils.Elevation.init(this)
        val view = AltitudeProfileView(this)
        val points = ArrayList<AltitudeProfileView.Point>(flown.size)
        val altitudeEpoch = juricabi.com.telemetry.gl.AltitudeFrame.currentEpoch()
        // Betaflight reports height above the arming point once armed, so the
        // ground under the launch is what those heights are missing before they
        // can be drawn against terrain. Worked out the same way the 3D view
        // works it out: from the ground at the first fix.
        val lift = launchGroundLift(altitudeEpoch)
        for (p in flown) {
            points.add(AltitudeProfileView.Point(p.lat, p.lon, p.altitudeMsl + lift))
        }
        view.setTrack(points)
        view.minimumHeight = (resources.displayMetrics.density * 220).toInt()

        fetchTerrainFor(flown) {
            // The dialog may have outlived the flight whose terrain it asked
            // for. Its late answer must not adopt or publish the new flight's
            // altitude frame.
            if (juricabi.com.telemetry.gl.AltitudeFrame.currentEpoch() == altitudeEpoch) {
                val settled = launchGroundLift(altitudeEpoch)
                val updated = ArrayList<AltitudeProfileView.Point>(flown.size)
                for (p in flown) {
                    updated.add(AltitudeProfileView.Point(p.lat, p.lon, p.altitudeMsl + settled))
                }
                view.setTrack(updated)
            }
        }

        this.showDialog(
            AlertDialog.Builder(this)
                .setTitle("Altitude profile")
                .setView(view)
                .setPositiveButton("Close", null)
                .create()
        )
    }

    /**
     * What to add to reported altitudes so they are above sea level.
     *
     * Zero when they already are. Non zero when the first fix reads nothing
     * like the ground beneath it, which means they are measured from where the
     * model armed.
     */
    private fun launchGroundLift(altitudeEpoch: Long): Float {
        // The same answer the 3D ground works from, from the same code. This
        // used to ask whether the first fix read within sixty metres of the
        // terrain under it — a test the 3D view documents as unsound, and which
        // disagreed with it: a flight starting high above a valley was declared
        // to be measured from the launch and drawn a few hundred metres up.
        // The answer the ground view settled on, where it has settled one:
        // it samples the terrain far more finely than this does, and two
        // answers to one question is how the same flight came to be drawn at
        // two heights.
        juricabi.com.telemetry.gl.AltitudeFrame.lift(altitudeEpoch)?.let { return it }
        val proposed = juricabi.com.telemetry.gl.TerrainScene.referenceOf(
            flightPath, juricabi.com.telemetry.utils.Elevation.TILE_ZOOM) ?: return 0f
        return juricabi.com.telemetry.gl.AltitudeFrame
            .settle(proposed, altitudeEpoch)?.lift ?: 0f
    }

    /**
     * Where this phone is, from the map if it has a fix and from the system's
     * last known one otherwise. The map's own arrow comes from a provider that
     * has usually heard something long before the satellites are in; without
     * this the 3D view could sit there with no arrow at all, having been opened
     * a moment too early.
     */
    private fun myLastKnownPlace(): Position? {
        map?.getMyLocation()?.let { return it }
        val found = myLastKnownFix() ?: return null
        return Position(found.latitude, found.longitude)
    }

    /** The system's memory of where this phone last stood, with its accuracy. */
    private fun myLastKnownFix(): Location? {
        if (checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (provider in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val fix = try { lm.getLastKnownLocation(provider) } catch (e: Exception) { null }
            if (fix == null) continue
            val known = best
            best = when {
                known == null -> fix
                // A mast answers at once and puts a half kilometre circle round
                // you; the satellites are slower and sure. Take the better one
                // unless it is old enough to be somewhere else entirely.
                Math.abs(fix.time - known.time) < 5 * 60 * 1000L ->
                    if (fix.accuracy > 0f && fix.accuracy < known.accuracy) fix else known
                fix.time > known.time -> fix
                else -> known
            }
        }
        return best
    }

    private fun show3DView(quiet: Boolean = false) {
        val mine = myLastKnownPlace()
        // No flight yet is no reason to refuse: the ground is there either way.
        // Anywhere we know about will do — the model, where it was last seen,
        // or here.
        val where = when {
            lastGPS.lat != 0.0 || lastGPS.lon != 0.0 -> lastGPS
            lastKnownGPS != null -> lastKnownGPS
            else -> mine
        }
        if (where == null && juricabi.com.telemetry.gl.LiveFlightPath.size() < 2) {
            if (!quiet) {
                Toast.makeText(this, "No position yet, from the model or this phone",
                    Toast.LENGTH_SHORT).show()
            }
            return
        }

        hide3DView()
        // Let go of properly rather than merely dropped: the tile threads and
        // the tile cache belong to the view, and this happens every time the
        // ground is opened. Keeping the map alive behind the ground was tried
        // and rolled back — the phone does not owe a hidden map its memory.
        map?.onDestroy()
        mapHolder.removeAllViews()
        map = null
        // Taking the map view away takes its style with it, and every line
        // and marker held here draws out of that style — so they are now
        // hollow, and writing to one throws. They belong to the map, and the
        // map has gone.
        forgetMapOverlays()

        // A parked world is this same flight's, or it would have been
        // discarded where the flight changed — adopt it instead of spending
        // twenty seconds rebuilding what the heap still holds.
        val adopted = parked3D
        parked3D = null
        val view = adopted ?: Terrain3DView(this)
        terrain3D = view
        mapHolder.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        applyTerrainSettings(view)
        if (adopted != null) view.onResume()
        view.setTraffic(lastAirplanes)
        // the same answer a tap on the map's airplane gets, said the 3D way
        view.onTrafficTapped = { airplane ->
            runOnUiThread {
                showDialog(AlertDialog.Builder(this)
                    .setTitle(airplane.displayName)
                    .setMessage(airplaneSummary(airplane))
                    .setPositiveButton(android.R.string.ok, null)
                    .create())
            }
        }
        // Facing the way it was last seen facing. A view is built with a model
        // pointing north and level, and only an arriving attitude turns it — so
        // opening this view with nothing arriving showed the model facing north
        // wherever it had really been going, exactly as the map's marker did.
        if (gotHeading) view.setModelAttitude(lastHeading, lastPitch, lastRoll)
        view.onGroundReady = { releaseHeldReplay() }
        // Switching to the ground mid-replay waits the same way, rather than
        // playing on behind a screen with nothing on it. An adopted world is
        // already dressed — held, the replay would wait on a ready-signal
        // that fired long ago and never comes again.
        if (adopted == null && logPlayer?.isPlaying() == true) {
            logPlayer?.stop()
            replayWaitingForGround = true
        }
        view.onFollowingLost = { setFollowMode(false) }
        // The world belongs to whatever is alive: a link, or a replay, or —
        // when neither is — the person holding the phone. Standing outside a
        // world a finished flight left behind, locate is asking for that
        // handover, and the only button whose whole job is "show me where I
        // am" should be able to do it.
        view.onLocateBeyondWorld = {
            if (isIdle()) {
                askToEndTheFlight()
                true
            } else false
        }
        view.onBearingChanged = { updateCompassHeading(it) }
        // the grid under the heading, for the whole 3D session — unless
        // its switch says no
        view.onLoadingProgress = { done, total ->
            if (preferenceManager.isLoadingGridShown()) loadingGrid.show(done, total)
            else loadingGrid.hide()
        }
        // an adopted world's count sits still; say it again now it has ears
        view.republishProgress()
        // The buttons say what they were saying before the switch.
        //
        // Tracking was turned on here whatever the map had been left doing, so
        // panning away from the model in 2D and then looking at the ground
        // snapped straight back onto it. The two views are two ways of watching
        // one flight, not two decisions about how to watch it — and the state
        // is kept on this screen, which outlives both of them.
        //
        // Following first: riding behind the model is a way of keeping up with
        // it, so the view drops the chase when it is told to stop keeping up.
        view.setFollowing(keepingUp())
        if (chaseMode) view.setChasing(true)
        // where the operator was, if this is opening over a replay
        showOperator()
        updateCompassHeading(view.bearing())
        setFollowMode(followMode)
        // Whatever flight there is. This used to be withheld unless a link
        // was up or a replay running, to stop a finished flight reappearing on
        // a map built afterwards — but a flight is now thrown away where one
        // ends and another begins, so there is nothing stale left to withhold.
        // Meanwhile the map keeps showing a flight after the link drops, and
        // this view was coming up empty beside it.
        val flown = juricabi.com.telemetry.gl.LiveFlightPath.snapshot()
        // A replay's ground belongs to its flight. With the flight not decoded
        // yet there is nowhere honest to build, and offering where the phone
        // or the last link stood built the wrong field's world — thrown away,
        // all of it, the moment the flight arrived from the log. The view
        // waits instead: for beginAt with the log's own first fix, or for the
        // flight itself.
        val fallback = if (isInReplayMode() && flown.size < 2) null else where
        view.start(
            flown,
            fallback?.lat ?: Double.NaN, fallback?.lon ?: Double.NaN,
            if (showLiveArrow()) mine?.lat ?: Double.NaN else Double.NaN,
            if (showLiveArrow()) mine?.lon ?: Double.NaN else Double.NaN,
            if (showLiveArrow()) phoneAccuracy else Float.NaN
        )
        // start() seeds the view from the last-known place with whatever
        // accuracy was lying around — a second location path. The unified
        // answer, ring and all, goes over the top of it, and only after
        // start: fed before it, the seed just overwrote the answer.
        tellViewsWhereIAm()
    }

    /**
     * Begin the 3D ground again for a new flight.
     *
     * Whether reported heights mean sea level or the launch is settled once and
     * kept, deliberately — it cannot be allowed to flip mid-flight. But a new
     * flight is a new question, and one begun at another field without leaving
     * this screen was being drawn against the last field's answer, hundreds of
     * metres out. The origin it is all measured from is fixed for the same
     * reason and wants the same fresh start.
     */
    private fun startFlightIn3D(keepWorld: Boolean = true) {
        // A new flight is a new question — but not always a new world: at
        // the same field, origin and ground datum answer the same, and the
        // rebuild threw away the very tiles it was about to reload. The
        // per-flight questions re-open over the standing ground; a far
        // flight still re-anchors down the road built for that, and a
        // moved datum still rebuilds. Leaving a replay of somewhere far
        // still rebuilds — the world hands back from the flight's country
        // to the phone's, which no re-opened question covers — and
        // closeReplay measures which case it is.
        if (keepWorld) {
            val replay = isInReplayMode()
            terrain3D?.beginNewFlight(replay)
            parked3D?.beginNewFlight(replay)
            return
        }
        parked3D?.release()
        parked3D = null
        if (terrain3D == null) return
        hide3DView()
        show3DView(quiet = true)
    }

    private fun forgetMapOverlays() {
        polyLine = null
        homeLine = null
        operatorLine = null
        headingPolyline = null
        marker = null
        flightPlanLines.clear()
    }

    /**
     * Everything the 3D view takes from the settings.
     *
     * Applied when it is built and again whenever this screen resumes, since
     * that is what returning from the settings looks like — without it a change
     * of model or colour did not appear until the view was rebuilt.
     */
    private fun applyTerrainSettings(view: Terrain3DView) {
        view.setModelShape(preferenceManager.getModelType())
        view.setArrowColours(
            preferenceManager.getLiveArrowColor(), preferenceManager.getLoggedArrowColor()
        )
        // a replay's ground belongs to the flight being replayed, wherever this
        // phone happens to be sitting
        view.groundFollowsPhone = !isInReplayMode()
        view.setTrackColor(preferenceManager.getRouteColor())
        view.setModelColor(preferenceManager.getPlaneColor())
        view.setOverlaySettings(
            preferenceManager.isHomeLineEnabled(), preferenceManager.getHomeLineColor(),
            preferenceManager.isHeadingLineEnabled(), preferenceManager.getHeadLineColor(),
            preferenceManager.isOperatorLineEnabled(), preferenceManager.getOperatorLineColor()
        )
        val plans = if (preferenceManager.isFlightPlansEnabled()) {
            FlightPlanManager(this).getPlans()
                .filter { it.visible && it.waypoints.size >= 2 }
                .map { Pair(it.waypoints, it.color) }
        } else {
            emptyList()
        }
        view.setFlightPlans(plans)
        // the ring's switch may have been flipped while the count sat still
        view.republishProgress()
    }

    private fun hide3DView() {
        // leaving the view it was waiting for: the map needs no ground
        releaseHeldReplay()
        loadingGrid.hide()
        terrain3D?.let {
            it.onPause()
            it.release()
            mapHolder.removeView(it)
        }
        terrain3D = null
    }

    /**
     * The switch to the map: the world goes to the garage, not the grave.
     * Paused, so nothing loads behind the map; detached, so its textures go
     * with the GL context and a parked world costs meshes, not pictures.
     * The Activity's closures stay here — show3DView rewires them on adopt.
     */
    private fun park3DView() {
        releaseHeldReplay()
        loadingGrid.hide()
        terrain3D?.let {
            it.onPause()
            it.onGroundReady = null
            it.onFollowingLost = null
            it.onBearingChanged = null
            it.onTrafficTapped = null
            it.onLoadingProgress = null
            it.onLocateBeyondWorld = null
            mapHolder.removeView(it)
            parked3D = it
        }
        terrain3D = null
    }

    /** Terrain over the flight's area, off the UI thread; [onReady] on it. */
    private fun fetchTerrainFor(
        path: List<juricabi.com.telemetry.gl.TerrainScene.TrackPoint>,
        onReady: () -> Unit
    ) {
        var minLat = path[0].lat; var maxLat = path[0].lat
        var minLon = path[0].lon; var maxLon = path[0].lon
        for (p in path) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        AsyncTask.execute {
            juricabi.com.telemetry.utils.Elevation.prefetch(
                minLat, minLon, maxLat, maxLon,
                juricabi.com.telemetry.utils.Elevation.TILE_ZOOM,
                { _, _ -> runOnUiThread { if (!isFinishing) onReady() } },
                { _, _ -> runOnUiThread { if (!isFinishing) onReady() } }
            )
        }
    }

    private fun rememberForProfile(latitude: Double, longitude: Double) {
        rememberForProfile(latitude, longitude, heightNow())
    }

    /**
     * The height to record with a fix, or NaN where it would be a lie.
     *
     * Betaflight reports height above the sea while disarmed and height above
     * the arming point once armed, so a flight recorded from power-up carries
     * both meanings. What the whole flight means is decided from the lowest
     * readings of it, which the armed ones win — and the handful from before
     * arming were then lifted as though they were above-launch too, and drawn
     * one ground-elevation into the sky.
     *
     * Left out rather than corrected: which of the two a disarmed reading is
     * cannot be known from the reading. The fix itself is still recorded, and
     * is drawn where it was, on the ground.
     */
    private fun heightNow(fixCount: Int = 1): Float {
        val reported = flightAltitude.forFix(fixCount)
        if (gotArmedState && !isArmed && preferenceManager.isDisarmedHeightIgnored()) {
            return Float.NaN
        }
        return reported
    }

    /**
     * A fix, kept for everything that draws the flight.
     *
     * The ones that came with no height as well: a link with no barometer and a
     * GPS that reports position only still flew somewhere, and dropping those
     * left nothing to draw at all. They are kept as heightless, and drawn
     * along the ground.
     */
    private fun rememberForProfile(latitude: Double, longitude: Double, height: Float) {
        if (latitude == 0.0 && longitude == 0.0) return
        juricabi.com.telemetry.gl.LiveFlightPath.add(latitude, longitude, height)
        if (!height.isNaN()) lastRememberedHeight = height
    }

    /** The height the last remembered point was given, to climb from. */
    private var lastRememberedHeight = Float.NaN

    /** The marker for whatever is being flown: quad, fixed wing or heli. */
    private fun modelIcon(): Int {
        return when (preferenceManager.getModelType()) {
            "plane" -> R.drawable.ic_fixedwing
            "heli" -> R.drawable.ic_heli
            else -> R.drawable.ic_plane
        }
    }

    //should be called on ui thread
    fun tryCreateMarker() {
        if (this.hasGPSFix && marker == null && (map?.initialized() ?: false) && lastGPS.lat != 0.0 && lastGPS.lon != 0.0) {
            if (headingPolyline == null && preferenceManager.isHeadingLineEnabled()) {
                headingPolyline = createHeadingPolyline()
                updateHeading()
            }
            marker =
                map?.addMarker(modelIcon(), preferenceManager.getPlaneColor(), lastGPS)
            marker?.place(lastGPS, lastHeading)
            map?.moveCamera(lastGPS, LOCATE_ZOOM)
        }
    }

    private fun createHeadingPolyline(): MapLine? {
        return map?.addPolyline(LineWeights.HEADING, preferenceManager.getHeadLineColor(), lastGPS, lastGPS)
    }

    private fun setRSSIIcon(rssi: Int) {
        when (rssi) {
            in 81..100 -> R.drawable.ic_rssi_5
            in 61..80 -> R.drawable.ic_rssi_4
            in 41..69 -> R.drawable.ic_rssi_3
            in 21..40 -> R.drawable.ic_rssi_2
            in 1..20 -> R.drawable.ic_rssi_1
            0 -> R.drawable.ic_rssi_0
            else -> R.drawable.ic_rssi_5
        }.let {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.rssi.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.rssi.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun setUPLQIcon(lq: Int) {
        when (lq) {
            in 81..100 -> R.drawable.ic_up_lq_5
            in 61..80 -> R.drawable.ic_up_lq_4
            in 41..69 -> R.drawable.ic_up_lq_3
            in 21..40 -> R.drawable.ic_up_lq_2
            in 1..20 -> R.drawable.ic_up_lq_1
            0 -> R.drawable.ic_up_lq_0
            else -> R.drawable.ic_up_lq_5
        }.let {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.upLq.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.upLq.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun setDNLQIcon(lq: Int) {
        when (lq) {
            in 81..100 -> R.drawable.ic_dn_lq_5
            in 61..80 -> R.drawable.ic_dn_lq_4
            in 41..69 -> R.drawable.ic_dn_lq_3
            in 21..40 -> R.drawable.ic_dn_lq_2
            in 1..20 -> R.drawable.ic_dn_lq_1
            0 -> R.drawable.ic_dn_lq_0
            else -> R.drawable.ic_dn_lq_5
        }.let {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.dnLq.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.dnLq.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun setRssiDbm1Icon(rssi: Int) {
        when (rssi) {
            in -31..0 -> R.drawable.ic_rssi_dbm1_5
            in -51..-30 -> R.drawable.ic_rssi_dbm1_4
            in -71..-59 -> R.drawable.ic_rssi_dbm1_3
            in -91..-70 -> R.drawable.ic_rssi_dbm1_2
            in -120..-90 -> R.drawable.ic_rssi_dbm1_1
            0 -> R.drawable.ic_rssi_dbm1_0
            else -> R.drawable.ic_rssi_dbm1_5
        }.let {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.rssiDbm1.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.rssiDbm1.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun setRssiDbm2Icon(rssi: Int) {
        when (rssi) {
            in -31..0 -> R.drawable.ic_rssi_dbm2_5
            in -51..-30 -> R.drawable.ic_rssi_dbm2_4
            in -71..-50 -> R.drawable.ic_rssi_dbm2_3
            in -91..-70 -> R.drawable.ic_rssi_dbm2_2
            in -121..-90 -> R.drawable.ic_rssi_dbm2_1
            0 -> R.drawable.ic_rssi_dbm2_0
            else -> R.drawable.ic_rssi_dbm2_5
        }.let {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.rssiDbm2.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.rssiDbm2.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun setRssiDbmdIcon(rssi: Int) {
        when (rssi) {
            in -31..0 -> R.drawable.ic_rssi_dbmd_5
            in -51..-30 -> R.drawable.ic_rssi_dbmd_4
            in -71..50 -> R.drawable.ic_rssi_dbmd_3
            in -91..-70 -> R.drawable.ic_rssi_dbmd_2
            in -120..-90 -> R.drawable.ic_rssi_dbmd_1
            0 -> R.drawable.ic_rssi_dbmd_0
            else -> R.drawable.ic_rssi_dbmd_5
        }.let {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.rssiDbmd.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.rssiDbmd.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    override fun onRSSIData(rssi: Int) {
        this.sensorTimeoutManager.onRSSIData(rssi);

        runOnUiThread {
            this.rssi.text = if (rssi == -1) "-" else rssi.toString()
            this.setRSSIIcon(rssi);
        }
    }

    override fun onUpLqData(lq: Int) {
        this.sensorTimeoutManager.onUpLqData(lq);

        runOnUiThread {
            this.upLq.text = if (lq == -1) "-" else lq.toString()
            this.setUPLQIcon(lq);
        }
    }

    override fun onDnLqData(lq: Int) {
        this.sensorTimeoutManager.onDnLqData(lq);

        runOnUiThread {
            this.dnLq.text = if (lq == -1) "-" else lq.toString()
            this.setDNLQIcon(lq);
        }
    }

    override fun onRssiDbm1Data(rssi: Int) {
        this.sensorTimeoutManager.onRssiDbm1Data(rssi);

        runOnUiThread {
            this.rssiDbm1.text = if (rssi == 0) "-" else rssi.toString()
            this.setRssiDbm1Icon(rssi);
        }
    }

    override fun onRssiDbm2Data(rssi: Int) {
        this.sensorTimeoutManager.onRssiDbm2Data(rssi);

        runOnUiThread {
            this.rssiDbm2.text = if (rssi == 0) "-" else rssi.toString()
            this.setRssiDbm2Icon(rssi);
        }
    }

    override fun onRssiDbmdData(rssi: Int) {
        this.sensorTimeoutManager.onRssiDbmdData(rssi);

        runOnUiThread {
            this.rssiDbmd.text = if (rssi == 0) "-" else rssi.toString()
            this.setRssiDbmdIcon(rssi);
        }
    }

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
            runOnUiThread {
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

    override fun onElrsModeModeData(mode: Int) {
        this.sensorTimeoutManager.onElrsModeModeData(mode);
        lastRfMode = mode
        runOnUiThread { renderRate(mode) }
    }

    /** The last RF mode seen, so the reading can be redrawn if the system changes. */
    private var lastRfMode: Int? = null

    private fun renderRate(mode: Int) {
        if (detectedProtocol == "GHST") {
            this.elrsRate.text = GHST_RF_PROFILES.getOrNull(mode) ?: mode.toString()
            return
        }
        // A Crossfire or Tracer numbers these differently, so use its own table.
        // The icon beside it is what says which system it is, so the number does
        // not repeat it — there is little enough room on the bar as it is.
        val system = crsfSystem
        if (system == "XF" || system == "TRACER") {
            this.elrsRate.text = crossfireRate(mode)
            return
        }
        run {
            when (mode) {
                13 -> this.elrsRate.text = "F1000"
                12 -> this.elrsRate.text = "F500"
                11 -> this.elrsRate.text = "D500"
                10 -> this.elrsRate.text = "D250"
                9 -> this.elrsRate.text = "L500"
                8 -> this.elrsRate.text = "L333c" //8ch
                7 -> this.elrsRate.text = "L250"
                6 -> this.elrsRate.text = "L200"
                5 -> this.elrsRate.text = "L150"
                4 -> this.elrsRate.text = "L100"
                3 -> this.elrsRate.text = "L100c"  //8ch
                2 -> this.elrsRate.text = "L50"
                1 -> this.elrsRate.text = "L25"
                0 -> this.elrsRate.text = "L4"
                else -> this.elrsRate.text = mode.toString();
            }
        }
    }


    private fun showMapTypeSelectorDialog() {
        val fDialogTitle = "Select Map Type"
        val builder = AlertDialog.Builder(this)
        builder.setTitle(fDialogTitle)

        val checkItem = if (terrain3D != null) {
            ITEM_3D
        } else {
            mapType - MapLibreStyles.MAP_TYPE_DEFAULT
        }

        builder.setSingleChoiceItems(
            MAP_TYPE_ITEMS,
            checkItem
        ) { dialog, item ->
            dialog.dismiss()
            // The last two are not map types but the same ground in three
            // dimensions, so they open that screen and leave the map as it was.
            if (item == ITEM_3D) {
                preferenceManager.set3DMapChosen(true)
                show3DView()
                return@setSingleChoiceItems
            }
            preferenceManager.set3DMapChosen(false)
            park3DView()
            map?.onDestroy()
            mapHolder.removeAllViews()
            map = null
            forgetMapOverlays()
            mapType = item + MapLibreStyles.MAP_TYPE_DEFAULT
            preferenceManager.setMapType(mapType)
            initMap(true)
        }

        val fMapTypeDialog = builder.create()
        fMapTypeDialog.setCanceledOnTouchOutside(true)
        this.showDialog(fMapTypeDialog);
    }

    override fun onVBATData(voltage: Float) {
        this.sensorTimeoutManager.onVBATData(voltage);
        runOnUiThread {
            this.voltage.text = "${"%.2f".format(voltage)} V"
        }
    }

    override fun onCellVoltageData(voltage: Float) {
        this.lastCellVoltage = voltage;
        runOnUiThread {
            this.cell_voltage.text = "${"%.2f".format(voltage)} V"
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
                if (!cellsAsked && logPlayer == null) {
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
        this.showDialog(
            AlertDialog.Builder(this)
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
        runOnUiThread {
            if (preferenceManager.getReportVoltage() == "Battery") {
                // reported value is the pack
                this.sensorTimeoutManager.onVBATData(voltage)
                this.voltage.text = "${"%.2f".format(voltage)} V"

                val cells = cellCount(voltage)
                if (cells > 0) {
                    val perCell = voltage / cells
                    this.sensorTimeoutManager.onCellVoltageData(perCell)
                    this.cell_voltage.text = "${"%.2f".format(perCell)} V"
                    this.lastCellVoltage = perCell
                }
            } else {
                // reported value is one cell
                this.sensorTimeoutManager.onCellVoltageData(voltage)
                this.cell_voltage.text = "${"%.2f".format(voltage)} V"
                this.lastCellVoltage = voltage

                // The pack figure is only shown when the user has said how many
                // cells there are, because multiplying by a guessed count would
                // be inventing a number. Without this the voltage widget — the
                // one on screen by default — simply stayed blank for anyone
                // whose flight controller reports per cell.
                val setting = preferenceManager.getBatteryCells()
                val cells = if (setting == "auto") 0 else (setting.toIntOrNull() ?: 0)
                if (cells > 0) {
                    val pack = voltage * cells
                    this.sensorTimeoutManager.onVBATData(pack)
                    this.voltage.text = "${"%.2f".format(pack)} V"
                }
            }
        }
    }

    override fun onCurrentData(current: Float) {
        this.sensorTimeoutManager.onCurrentData(current)
        runOnUiThread {
            this.current.text = "${"%.2f".format(current)} A"
        }
    }

    override fun onDNSNRData(snr: Int) {
        this.sensorTimeoutManager.onDNSNRData(snr);
        runOnUiThread {
            this.dnSnr.text = snr.toString();
        }
    }

    override fun onUPSNRData(snr: Int) {
        this.sensorTimeoutManager.onUPSNRData(snr);
        runOnUiThread {
            this.upSnr.text = snr.toString();
        }
    }

    override fun onAntData(activeAntena: Int) {
        this.sensorTimeoutManager.onAntData(activeAntena);
        runOnUiThread {
            this.ant.text = (activeAntena + 1).toString();
        }
    }

    override fun onPowerData(power: Int) {
        this.sensorTimeoutManager.onPowerData(power);
        if (detectedProtocol == "GHST") {
            // Ghost reports the power in mW and uses levels the CRSF table does not have
            runOnUiThread {
                this.power.text =
                    if (power >= 1000 && power % 1000 == 0) "${power / 1000}W" else "${power}mW"
            }
            return
        }
        runOnUiThread {
            when (power) {
                1 -> this.power.text = "10mW"
                2 -> this.power.text = "25mW"
                3 -> this.power.text = "100mW"
                4 -> this.power.text = "500mW"
                5 -> this.power.text = "1W"
                6 -> this.power.text = "2W"
                7 -> this.power.text = "250mW"
                8 -> this.power.text = "50mW"
                else -> this.power.text = power.toString();
            }
        }
    }

    override fun onHeadingData(heading: Float) {
        gotHeading = true;
        modelHeadingKnown = true
        lastHeading = heading
        runOnUiThread {
            // Turned towards, not turned to. Setting it here put the marker
            // straight onto each heading the model sent, undoing the easing
            // that was meant to carry it round smoothly.
            keepSmoothing()
            // And the model in three dimensions, which is pointed along this
            // heading but was only ever told of it when a pitch or a roll came
            // along afterwards. In flight one always does, within a moment. A
            // replay paused after a jump has nothing follow, so the model was
            // left pointing whichever way it had been pointing before.
            terrain3D?.setModelAttitude(lastHeading, lastPitch, lastRoll)
        }
    }

    private fun updateHeading(
        turnMap: Boolean = true,
        displayedDrone: Position? = null,
        displayedHeading: Float = Float.NaN
    ) {
        if (turnMap) applyHeadingUp()
        if (lastGPS.lat != 0.0 && lastGPS.lon != 0.0) {
            val from = displayedDrone ?: presentedPosition()
            // and pointing the way the marker is pointing: drawn to the last
            // heading while the marker eased towards it, the line swung ahead
            // and waited for it
            val towards = when {
                !displayedHeading.isNaN() -> displayedHeading
                !submittedMarkerHeading.isNaN() ->
                    submittedMarkerHeading
                shownMarkerHeading.isNaN() -> lastHeading
                else -> shownMarkerHeading
            }
            headingPolyline?.let { headingLine ->
                val (offsetLat, offsetLon) = GeoUtils.computeOffset(from.lat, from.lon, 1000.0, towards.toDouble())
                val ahead = Position(offsetLat, offsetLon)
                // built when it is not there rather than written into: it is
                // emptied when a replay resets, and setting a point of an empty
                // line throws — which the home line beside it has always
                // guarded against and this one never did.
                headingLine.setPoints(listOf(from, ahead))
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
            val asked = disconnectAsked
            disconnectAsked = false
            switchToIdleState()
            // Asked for, so the flight goes with the link: what was flown is
            // in the recording, and the replay is where it is looked at. A
            // link that drops keeps everything, because that is the one the
            // model may be lying in a field after. And with nothing written
            // there is no replay to see it in — the screen is the only copy
            // there is, so it stays. Asked of the service, which knows
            // whether a file was opened: the setting only says one was
            // wanted, and a full card or a link brought up without the
            // storage permission leaves it on with nothing behind it.
            if (asked && dataService?.isRecording() == true) endTheFlight()

            if (preferenceManager.getConnectionVoiceMessagesEnabled()) {
                soundPool!!.play(disconnectedSoundId, 1f, 1f, 0, 0, 1f)
            }

            reconnectOnFailure = true;
            tryReconnect()
        }
    }

    fun tryReconnect() {
        runOnUiThread {
            val isBluetooth =
                (lastConnectionType == CONNTYPE_BT) || (lastConnectionType == CONNTYPE_BLE)
            val isNetwork = lastConnectionType == CONNTYPE_NET

            // Each transport has its own switch. They are not the same event:
            // someone may want the radio link retried but not a Wi-Fi one that
            // would keep chasing an access point they have walked away from.
            val enabled = when {
                isBluetooth -> preferenceManager.getReconnectionEnabled()
                isNetwork -> preferenceManager.getNetworkReconnectionEnabled()
                else -> false
            }

            if (enabled) {
                if (reconnectionStartTime == 0L) {
                    reconnectionStartTime = System.currentTimeMillis()
                }

                // A dropped network link takes longer to come back than a
                // Bluetooth one: the transmitter may be rebooting and the phone
                // has to re-associate with its access point before anything can
                // be reached at all.
                val window = if (isNetwork) 60000 else 21000

                if ((System.currentTimeMillis() - reconnectionStartTime) < window) {
                    AsyncTask.execute {
                        Thread.sleep(5000)
                        runOnUiThread {
                            // reconnecting from a dead Activity re-binds the
                            // service to it, leaving the live screen deaf
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (isNetwork) {
                                reconnectToNetwork()
                            } else {
                                reconnectToBluetoothDevice()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun switchToReplayMode() {
        stopFr24(clear = true)
        setFollowMode(true);
        seekBar.setOnSeekBarChangeListener(null)
        seekBar.progress = 0
        menuButton.show()
        // Still taking up its room: gone, the bar shrank to whatever was
        // left in it, and the whole screen jumped every time a replay opened.
        connectButton.visibility = View.INVISIBLE
        replayButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_close))
        replayButton.setOnClickListener {
            lastConnectionType = CONNTYPE_NONE; //reset last connection type to skip reconnection
            // closeReplay first, by its own doctrine: everything the idle
            // switch asks — is this still a replay? — must be answered
            // truthfully by the time it is asked. The other way round,
            // startFr24 was told yes and declined, and the sky stayed
            // empty until the next pause.
            closeReplay()
            switchToIdleState()
        }
        this.sensorTimeoutManager.disableTimeouts()
        this.tlmRate.setAlpha(0.5f);
        lastGPS = Position(0.0, 0.0);
        hasGPSFix = false;
    }

    private fun switchToIdleState() {
        this.logPlayer?.dispose();
        this.logPlayer = null;
        // out of the replay, so the sky is worth watching again
        startFr24()
        showMyLocation()
        resetUI()
        menuButton.hide()
        seekBar.visibility = View.GONE
        playButton.visibility = View.GONE
        connectButton.visibility = View.VISIBLE
        connectButton.text = getString(R.string.connect)
        replayButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_replay))
        replayButton.visibility = View.VISIBLE
        replayButton.setOnClickListener {
            replay()
        }
        connectButton.isEnabled = true
        connectButton.setOnClickListener {
            connect()
        }
        this.sensorTimeoutManager.enableTimeouts()
        // a high-latency link widened this; whatever connects next starts
        // from the ordinary window
        this.sensorTimeoutManager.setTimeoutWindow(
            SensorTimeoutManager.DEFAULT_TIMEOUT_MS)
        this.tlmRate.setAlpha(1.0f);
        // The model and the flight both stay. A link that drops leaves the last
        // place the model was seen on the screen, which is the one thing worth
        // having at that moment. Closing a replay is the other case, and that
        // takes the model away — see closeReplay.
    }

    /**
     * A replay is over: the model goes, the flight it made stays.
     *
     * Unlike a link dropping, there is no last known position worth keeping —
     * the model in a replay is a recording being played, and when it stops
     * there is nothing there.
     */
    /** Let a held replay run, now that there is something to run it over. */
    private fun releaseHeldReplay() {
        if (!replayWaitingForGround) return
        replayWaitingForGround = false
        logPlayer?.startPlayback()
    }

    private fun closeReplay() {
        // first, so that everything asking whether this is a replay is answered
        // truthfully by the time it is asked — the live arrow is switched on by
        // one of those answers, and was being switched on while the replay was
        // still officially open, which left it off
        replayFileString = null
        // A replay held back for ground it never got is owed nothing once it
        // has been closed. Left set, the hold outlived the replay that asked
        // for it: the next log to be opened was started — or, having played to
        // its end, started again — by ground arriving for something else.
        replayWaitingForGround = false
        forgetOperator()
        juricabi.com.telemetry.gl.AltitudeFrame.forget()
        // The whole of it: a recording that has been closed leaves nothing
        // behind, neither the model nor the flight it was playing back, and in
        // the 3D view that includes the surface hanging under the flight.
        forgetFlight()
        // Back in the live view, ground and altitude start at the phone. A
        // replay of this very field ends over ground worth keeping — the
        // same fifty kilometres that lets a connect keep it — and only a
        // replay of somewhere else hands the world back by rebuilding at
        // home. Measured against the live phone, not the arrow: during a
        // replay the arrow wears the recorded position.
        // A phone that has never had a fix is not a far one, and it is
        // nowhere to rebuild at either: the rebuild takes the ground down
        // and then finds no place to stand a new one, leaving the holder
        // empty. Replay works without the location permission, so this is a
        // road someone really walks.
        val fix = bestPhoneFix ?: myLastKnownFix()
        startFlightIn3D(keepWorld = fix == null ||
            (terrain3D ?: parked3D)?.worldNear(fix.latitude, fix.longitude) == true)
        marker?.remove()
        marker = null
        headingPolyline?.remove()
        headingPolyline = null
    }

    /**
     * The end of a flight that was ended on purpose.
     *
     * Everything the flight put on the screen goes, in both views, and both
     * come back to where the person is standing. The ground is the one thing
     * kept when it can still serve: near home the same tiles cover the phone,
     * and only a flight that had carried the world away rebuilds it.
     */
    private fun endTheFlight() {
        forgetFlight()
        // The model's own marker and the line off its nose: forgetFlight
        // empties the lines, but the marker belongs to the map, which is why
        // closing a replay — the other deliberate ending — takes it by hand.
        marker?.remove()
        marker = null
        headingPolyline?.remove()
        headingPolyline = null
        // and where the model was last seen. That memory is for walking to a
        // model that went down, which is the other kind of disconnection.
        lastKnownGPS = null
        lastKnownGPSAt = 0L
        // the height question belonged to the flight that raised it
        juricabi.com.telemetry.gl.AltitudeFrame.forget()
        // There is no link, so there is no fix. Left standing, a frame the
        // decoder was already holding when the button was pressed lands
        // afterwards and draws the first point of a flight that has ended.
        hasGPSFix = false
        // and the sky stops warning about traffic near where the model was
        fr24Manager?.forgetDronePosition()
        // and how far the map had been dragged aside to look at something:
        // measured against the flight that has gone, it would shove the next
        // one that far off centre from its very first fix
        centreOnModel()
        // Following and chase are deliberately left alone. They are the
        // person's, not the flight's, and the next flight is owed them:
        // switched off here, someone who lands, swaps a pack and connects
        // again finds the camera no longer keeping up and nothing to say why.
        //
        // The phone as the rest of the screen knows it — the service's own
        // fix first, the system's memory second, which is what closing a
        // replay asks and what put the arrow where it is standing. Asking
        // only the system answered null while the arrow was drawn.
        val fix = bestPhoneFix ?: myLastKnownFix()
        // Nowhere to come home to: end the flight and leave the ground alone
        // rather than tear down a working world for a place we do not know.
        if (fix == null) {
            startFlightIn3D()
            return
        }
        val mine = Position(fix.latitude, fix.longitude)
        val standing = terrain3D ?: parked3D
        startFlightIn3D(keepWorld = standing?.worldNear(mine.lat, mine.lon) == true)
        // A rebuilt world opens at the phone already; a kept one is walked
        // there — the one behind the map as much as the one on screen, or
        // switching to 3D afterwards looked at the far end of the valley
        // where the model had stopped. lookAt rather than the locate
        // button's own road, which means "leave the model behind" and gives
        // up following to say so: there is no model to leave, and the next
        // flight is still owed it.
        (terrain3D ?: parked3D)?.lookAt(mine.lat, mine.lon, null)
        map?.flyTo(mine, LOCATE_ZOOM)
    }

    /**
     * Offered rather than done: the same ending Disconnect performs, but
     * reached through a button that means something else, so it is asked.
     */
    private fun askToEndTheFlight() {
        // The same question the disconnect road asks, and a different answer
        // to it. There, ending is a side effect of ending the link, so an
        // unrecorded flight is spared; here somebody is answering "End
        // flight" on purpose, and is owed what it costs rather than a veto.
        val recorded = dataService?.isRecording() == true
        showDialog(AlertDialog.Builder(this)
            .setTitle("Beyond this flight's ground")
            .setMessage("End the flight and build the world where you are? " +
                if (recorded) "It stays in your recordings."
                else "Nothing was recorded - the screen is the only copy of it.")
            .setPositiveButton("End flight") { _, _ ->
                // Asked while nothing was connected, answered whenever: a
                // reconnect lands without anyone tapping, and this would
                // then throw away a live flight to build the world it is
                // already re-anchoring.
                if (!isIdle()) return@setPositiveButton
                endTheFlight()
            }
            .setNegativeButton("Cancel", null)
            .create())
    }

    private fun switchToConnectedState() {
        // A link and a replay are two different flights, and this screen can
        // only be showing one of them.
        //
        // Nothing used to end the replay here, and this is reached two ways:
        // a link coming up, and the screen binding to a service that already
        // had one. The second is what happens when the app is killed and
        // started again while a link is up — the screen restores the replay it
        // had open and the service reports it is connected — and it was then
        // live and replaying at once: the recorded operator's arrow drawn over
        // live telemetry, the clock reading a time from last week, and nearby
        // aircraft left switched off.
        if (isInReplayMode()) {
            logPlayer?.dispose()
            logPlayer = null
            seekBar.visibility = View.GONE
            playButton.visibility = View.GONE
            closeReplay()
        }
        replayButton.visibility = View.GONE
        // A link is up, so nothing that has been asked for is still pending:
        // left set by a disconnection that never reported back, the next
        // link to drop on its own would have been treated as one asked for.
        disconnectAsked = false
        // The menu is for replay: log rename, delete, export, playback length.
        // While connected it only ever offered "copy UAV location" and "show
        // route to UAV", and both of those are in the Find my quad button now,
        // so showing it here was two round buttons for one job.
        menuButton.hide()
        connectButton.text = getString(R.string.disconnect)
        connectButton.isEnabled = true
        mode.text = "Connected"
        connectButton.setOnClickListener {
            connectButton.isEnabled = false
            connectButton.text = getString(R.string.disconnecting)
            lastConnectionType = CONNTYPE_NONE; //reset last connection type to skip reconnection
            // asked for, so the flight ends with the link — noted here rather
            // than read off the connection type, which a transport that forgot
            // to set it would answer wrongly
            disconnectAsked = true
            dataService?.disconnect()
        }
    }

    override fun onConnectionFailed() {
        runOnUiThread {
            Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
            connectButton.text = getString(R.string.connect)
            mode.text = "Disconnected"
            connectButton.isEnabled = true
            connectButton.setOnClickListener {
                connect()
            }
            if (preferenceManager.getConnectionVoiceMessagesEnabled()) {
                soundPool!!.play(connectionFailedSoundId, 1f, 1f, 0, 0, 1f)
            }

            if ( reconnectOnFailure ) {
                tryReconnect()
            }
        }
    }

    private fun setFuelIcon(percentage: Int) {
        when (percentage) {
            in 91..100 -> R.drawable.ic_battery_full
            in 81..90 -> R.drawable.ic_battery_90
            in 61..80 -> R.drawable.ic_battery_80
            in 51..60 -> R.drawable.ic_battery_60
            in 31..50 -> R.drawable.ic_battery_50
            in 21..30 -> R.drawable.ic_battery_30
            in 0..20 -> R.drawable.ic_battery_alert
            else -> R.drawable.ic_battery_unknown
        }.let {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.fuel.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(this, it),
                    null,
                    null,
                    null
                )
            } else {
                this.fuel.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
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

    override fun onFuelData(fuel: Int) {
        this.sensorTimeoutManager.onFuelData(fuel)
        runOnUiThread {
            val batteryUnits = preferenceManager.getBatteryUnits()
            var percentage = fuel

            when (batteryUnits) {
                "mAh", "mWh" -> {
                    this.fuel.text = this.formatPower(fuel, batteryUnits)
                    //for icon, calculate percentage from cell voltage if available
                    if ((lastCellVoltage > 0) && (lastCellVoltage <= 4.4)) {
                        percentage = ((1 - (4.2f - lastCellVoltage)).coerceIn(0f, 1f) * 100).toInt()
                    } else {
                        percentage = -1;  //unknnow icon
                    }
                }
                "Percentage" -> {
                    this.fuel.text = "$fuel%"
                }
            }

            this.setFuelIcon(percentage);
        }
    }


    override fun onTelemetryByte() {
        this.sensorTimeoutManager.onTelemetryByte()
    }

    override fun onSuccessDecode() {
        this.sensorTimeoutManager.onSuccessDecode()
    }

    override fun onDecoderRestart() {
        // where a replay run to the end and started again comes through. The
        // decoder will say again whether it has a fix.
        runOnUiThread {
            hasGPSFix = false
            forgetFlight()
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
        val system = crsfSystem
        return when {
            detectedProtocol == "GHST" -> R.drawable.ic_ghst_rate
            system == "XF" || system == "TRACER" -> R.drawable.ic_xf_rate
            else -> R.drawable.ic_elrs_rate
        }
    }

    private fun applyRateIcon() {
        val icon = androidx.core.content.ContextCompat.getDrawable(this, rateIconRes())
        if (this.elrsRate.compoundDrawablesRelative[1] != null) {
            this.elrsRate.setCompoundDrawablesRelativeWithIntrinsicBounds(null, icon, null, null)
        } else {
            this.elrsRate.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
        }
    }

    override fun onProtocolDetected( protocolName: String) {
        detectedProtocol = protocolName
        runOnUiThread {
            run {
                // keep the icon on the side the current layout puts it on
                val icon = androidx.core.content.ContextCompat.getDrawable(this, rateIconRes())
                if (this.elrsRate.compoundDrawablesRelative[1] != null) {
                    this.elrsRate.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        null, icon, null, null
                    )
                } else {
                    this.elrsRate.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        icon, null, null, null
                    )
                }
            }
            Toast.makeText(this, "Protocol: $protocolName", Toast.LENGTH_SHORT).show()
        }
    }

    override fun commit() {
        runOnUiThread {
            drawGathered()
            commitRouteLinePoints()
        }
    }

    /**
     * What a seek has handed over so far, waiting for the end of it.
     *
     * A seek walks the log and gives up its positions in pieces, one piece per
     * reading that arrived between them — and heights arrive with almost every
     * position, so the pieces are a point long. Drawing each one as it came
     * meant the line, the model, the camera and the ground view were moved once
     * per point: eighty-four thousand times to open a log, which is the flight
     * being flown across the screen before it starts, and a smaller one of the
     * same every time the bar is dragged backwards.
     *
     * So they are gathered here and drawn together when the seek finishes. The
     * history comes out in one pass and the model moves once, to where the seek
     * left it.
     */
    private val gatheredPoints = ArrayList<Position>()
    private val gatheredHeights = ArrayList<Float>()

    /** The height the last gathered piece ended at, to climb from. */
    private var gatheredHeight = Float.NaN

    private fun drawGathered() {
        // A paused seek publishes its decoded route immediately. A playback
        // tick may still be waiting behind the presentation clock when pause
        // is pressed; put those real points down first, or clearing that queue
        // below would either lose them or append them after the seek target.
        val timeVisualBatch = logPlayer == null || logPlayer?.isPlaying() == true
        if (!timeVisualBatch) publishAllPendingVisualTrack()

        if (gatheredPoints.isEmpty()) {
            if (!timeVisualBatch && (lastGPS.lat != 0.0 || lastGPS.lon != 0.0)) {
                settlePausedReplaySeek(lastGPS)
            }
            return
        }
        val points = ArrayList(gatheredPoints)
        val heights = ArrayList(gatheredHeights)
        gatheredPoints.clear()
        gatheredHeights.clear()
        gatheredHeight = Float.NaN

        // A running replay may decode several GPS fixes in one display tick.
        // They still belong in the recorded flight, but revealing all of them
        // before the delayed model has reached them is the purple lead. The
        // trace candidate timestamps the whole batch at its arrival instead.
        if (points.size >= 2) {
            //all but the last one, which goes through the single-fix path below
            if (!timeVisualBatch) {
                val history = points.dropLast(1)
                polyLine?.submitPoints(history)
                publishedVisualTrack.addAll(history)
            }
            for (i in 0..points.size - 2) {
                rememberForProfile(points[i].lat, points[i].lon, heights[i])
                if (lastGPS.lat != 0.0 && lastGPS.lon != 0.0) {
                    lastTraveledDistance += GeoUtils.computeDistanceBetween(
                        lastGPS.lat, lastGPS.lon, points[i].lat, points[i].lon
                    )
                }
                lastGPS = Position(points[i].lat, points[i].lon)
            }
        }

        val last = points[points.size - 1]
        if (timeVisualBatch) gatheredVisualBatch = points
        try {
            onGPSData(last.lat, last.lon)
        } finally {
            gatheredVisualBatch = null
        }
        if (!timeVisualBatch) settlePausedReplaySeek(last)
    }

    override fun onGPSData(list: List<Position>, addToEnd: Boolean) {
        this.sensorTimeoutManager.onGPSData(list, addToEnd);
        runOnUiThread {
            // A link and a replay are the only two things that produce these.
            // With neither running, this one was decoded before the link went
            // and posted after — and it drew the first point of a flight that
            // had just been ended, put the dead model back in lastKnownGPS,
            // and left the sky warning about traffic near it.
            if (isIdle()) return@runOnUiThread
            if (!addToEnd) {
                // rewound: the path is about to be replayed, so drop what it
                // held — and with it whatever was gathered towards drawing it
                forgetFlight()
            }
            // Only with a fix, and asked here rather than when the seek ends:
            // whether there was one changes as the log is walked, and these are
            // the positions from the part of it that had one.
            if (hasGPSFix && list.isNotEmpty()) {
                // A piece carries one height: the log's altitude is decoded
                // between pieces, not within them. Giving every position that
                // one height turns a climb into a staircase, so it is walked
                // across the piece from the height the last one ended at. That
                // is what a climb between two readings actually looked like.
                val to = heightNow(list.size)
                val from = when {
                    !gatheredHeight.isNaN() -> gatheredHeight
                    !lastRememberedHeight.isNaN() -> lastRememberedHeight
                    else -> to
                }
                for (i in list.indices) {
                    val part = (i + 1).toFloat() / list.size
                    // nothing to walk across where no height was ever read:
                    // the fixes are kept as they came, without one
                    gatheredPoints.add(list[i])
                    gatheredHeights.add(if (to.isNaN()) Float.NaN else from + (to - from) * part)
                }
                if (!to.isNaN()) gatheredHeight = to
            }
        }
    }

    override fun onGPSData(latitude: Double, longitude: Double) {
        this.sensorTimeoutManager.onGPSData(latitude, longitude);
        runOnUiThread {
            // on every fix, not only a moved one: a quad on the ground repeats
            // its position, and the retry below would never come round again
            tryCreateMarker()

            var motionAt = android.os.SystemClock.uptimeMillis()
            if (hasGPSFix && (latitude != 0.0 || longitude != 0.0)) {
                motionAt = rememberMotionFix(latitude, longitude)
            }
            val timeVisualTrack = logPlayer == null || logPlayer?.isPlaying() == true
            if (timeVisualTrack && hasGPSFix &&
                (latitude != 0.0 || longitude != 0.0)) {
                val batch = gatheredVisualBatch ?: listOf(Position(latitude, longitude))
                pendingVisualTrack.addLast(TimedTrackBatch(motionAt, ArrayList(batch)))
                keepSmoothing()
            }

            if (Position(latitude, longitude) != lastGPS) {
                var d = 0.0;
                if (this.lastGPS.lat != 0.0 && this.lastGPS.lon != 0.0) {
                    d = GeoUtils.computeDistanceBetween(
                        this.lastGPS.lat, this.lastGPS.lon, latitude, longitude
                    )
                }
                lastGPS = Position(latitude, longitude)
                // live link only: a replayed log comes through here too, and
                // would overwrite where the model was actually last seen
                if ((latitude != 0.0 || longitude != 0.0) && logPlayer == null) {
                    lastKnownGPS = lastGPS
                    lastKnownGPSAt = System.currentTimeMillis()
                }
                // Only with a fix, as the map's own line has always been. A
                // receiver reports its last position while it is still looking
                // for the satellites, and those positions were kept: a model
                // and a flight drawn in the ground view where the map showed
                // nothing — and, worse, the ground was then fetched around
                // wherever that stale fix said, for the rest of the flight.
                if (hasGPSFix) {
                    // Which way it is going, where nothing says which way it is
                    // pointing. The ground view has always fallen back to this;
                    // the map pointed its marker and its heading line due north
                    // for the whole of a flight on a link with no attitude.
                    if (!gotHeading && d > 1.0) {
                        lastHeading = courseOverGround(
                            lastCourseFrom.lat, lastCourseFrom.lon, latitude, longitude
                        )
                        modelHeadingKnown = true
                        lastCourseFrom = Position(latitude, longitude)
                    }
                    rememberForProfile(latitude, longitude)
                    terrain3D?.onNewPoint()
                }
                // the marker walks to it over the next few frames, and takes the
                // lines and the camera with it
                keepSmoothing()
                if (hasGPSFix) {
                    if (!timeVisualTrack) {
                        polyLine?.submitPoints(listOf(lastGPS))
                        publishedVisualTrack.add(lastGPS)
                    }
                    this.lastTraveledDistance += d
                    this.traveled_distance.text =
                        this.formatDistance(this.lastTraveledDistance.toFloat());
                }

                this.tryCreateMarker()
                fr24Manager?.updateDronePosition(latitude, longitude)
            }
        }
    }

    override fun onConnected() {
        runOnUiThread {
            reconnectionStartTime = 0L;
            Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show()
            switchToConnectedState()
            this.lastTraveledDistance = 0.0;
            this.traveled_distance.text = "-"
            this.lastGPS = Position(0.0, 0.0);
            this.hasGPSFix = false;

            if (preferenceManager.getConnectionVoiceMessagesEnabled()) {
                soundPool!!.play(connectedSoundId, 1f, 1f, 0, 0, 1f)
            }
        }
    }

    private fun updateHorizonViewSize() {
        var size = 96.0f;
        var sizeInt = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            size,
            getResources().getDisplayMetrics()
        )
            .toInt();

        var lp = horizonView.getLayoutParams()
        lp.width = sizeInt;
        lp.height = sizeInt;
        horizonView.setLayoutParams(lp);
    }

    private val batInfoReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context?, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            lastPhoneBattery = level
            runOnUiThread {
                updatePhoneBattery()
            }
        }
    }

    private fun updatePhoneBattery() {
        this.phoneBattery.text = "$lastPhoneBattery%"
    }

    /**
     * Everything a replay is asked for, in one dialog.
     *
     * How fast it runs and whether this phone is drawn beside where it stood
     * used to be lines in a list of actions, each opening another list, and the
     * duration was five fixed choices in the settings screen — three taps and a
     * different screen away from the replay they change.
     *
     * They are answered by looking at the replay, so they are shown on top of
     * it, and each says what it will do: "4× faster" means little without
     * seeing that it plays eighteen minutes of flight in four and a half.
     */
    private fun showPlaybackActions() {
        val view = layoutInflater.inflate(R.layout.dialog_playback, null)
        val liveArrow = view.findViewById<SwitchCompat>(R.id.playback_live_arrow)
        val bar = view.findViewById<SeekBar>(R.id.playback_speed)
        val speedShown = view.findViewById<TextView>(R.id.playback_speed_value)
        val note = view.findViewById<TextView>(R.id.playback_note)

        fun speedAt(step: Int) = SPEED_STOPS[step.coerceIn(0, SPEED_STOPS.size - 1)]

        // From the step, not the stored float: the slow stops are divisions,
        // and printing 1/1.5 back as "1.5" through a float is how a label
        // comes out "1.5000001× slower".
        fun times(v: Float): String =
            if (v == Math.round(v).toFloat()) "${Math.round(v)}" else String.format("%.1f", v)

        fun labelAt(step: Int): String = when {
            step < SPEED_STOP_AS_FLOWN -> "${times(3f - 0.5f * step)}× slower"
            step == SPEED_STOP_AS_FLOWN -> "1× — as flown"
            else -> "${times(1f + 0.5f * (step - SPEED_STOP_AS_FLOWN))}× faster"
        }

        fun say() {
            speedShown.text = labelAt(bar.progress)
            // Read on every change, not captured: the CSV clock can arrive
            // while this dialog is open, and the preview must say what the
            // playback will actually do.
            val flightMs = operatorTrack?.lengthMillis ?: 0L
            val real = realFlightSeconds()
            val speed = speedAt(bar.progress)
            val seconds = playbackSeconds(real, speed)
            val plays = spanOf(Math.round(seconds * 1000.0))
            // Sped past the floor, the slider still moves but the time cannot.
            // Said outright, rather than a control that changes nothing.
            val quickest = if (speed > 1f && seconds > real / speed + 0.5) {
                " — its quickest"
            } else {
                ""
            }
            note.text = if (flightMs > 0L) {
                "${spanOf(flightMs)} of flight — plays in $plays$quickest"
            } else {
                "No clock recorded — pace estimated; plays in about $plays$quickest"
            }
        }

        liveArrow.isChecked = preferenceManager.isLiveShownInReplay()
        liveArrow.setOnCheckedChangeListener { _, on ->
            preferenceManager.setLiveShownInReplay(on)
            showMyLocation()
            if (!showLiveArrow()) terrain3D?.hideMyLocation()
            tellViewsWhereIAm()
        }

        bar.max = SPEED_STOPS.size - 1
        var nearest = SPEED_STOP_AS_FLOWN
        for (i in SPEED_STOPS.indices) {
            if (Math.abs(SPEED_STOPS[i] - preferenceManager.getPlaybackSpeed()) <
                Math.abs(SPEED_STOPS[nearest] - preferenceManager.getPlaybackSpeed())) {
                nearest = i
            }
        }
        bar.progress = nearest
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            // as it moves, not once it is let go: a number that changes only
            // afterwards is a number chosen blind
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                say()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                preferenceManager.setPlaybackSpeed(speedAt(bar.progress))
                restartPlayback()
            }
        })

        say()

        // No title of its own: the two headings inside say what this is, and a
        // third word above them said it again.
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Done") { it: DialogInterface, _: Int -> it.dismiss() }
            .create()
        // The slider is kept when it is let go, which is every way it is
        // moved by hand. A value changed some other way — a key, a tap that
        // never became a drag — is kept here instead of being lost.
        dialog.setOnDismissListener {
            if (preferenceManager.getPlaybackSpeed() != speedAt(bar.progress)) {
                preferenceManager.setPlaybackSpeed(speedAt(bar.progress))
                restartPlayback()
            }
        }
        view.findViewById<View>(R.id.playback_profile).setOnClickListener {
            dialog.dismiss()
            showAltitudeProfile()
        }
        view.findViewById<View>(R.id.playback_gpx).setOnClickListener {
            dialog.dismiss()
            showExportGPXDialog()
        }
        view.findViewById<View>(R.id.playback_kml).setOnClickListener {
            dialog.dismiss()
            showExportKMLDialog1()
        }
        showDialog(dialog)
    }

    /**
     * The flight's real length in seconds: measured, or estimated.
     *
     * The CSV beside the recording measured it. Without one the length is
     * estimated from how many packets the recording holds and the pace a link
     * broadly runs at — so a log with no clock still plays at roughly the
     * speed it happened, rather than to a fixed length that flattened every
     * flight to the same minute.
     */
    private fun realFlightSeconds(): Double {
        val ran = operatorTrack?.lengthMillis ?: 0L
        if (ran > 0L) return ran / 1000.0
        return (logPlayer?.packetCount() ?: 0).toDouble() / TELEMETRY_PACKETS_PER_SECOND
    }

    /**
     * How long the replay takes at this speed, floor and all. The dialog's
     * preview and the playback itself both ask here, so they cannot disagree.
     */
    private fun playbackSeconds(realSeconds: Double, speed: Float): Double {
        var seconds = realSeconds / speed
        if (speed > 1f) {
            seconds = Math.max(seconds, Math.min(realSeconds, REPLAY_FLOOR_SECONDS.toDouble()))
        }
        return Math.max(1.0, seconds)
    }

    /** A stretch of time as a clock reads it. */
    private fun spanOf(millis: Long): String {
        val all = millis / 1000L
        val minutes = all / 60L
        return if (minutes >= 60L) {
            String.format("%d:%02d:%02d", minutes / 60L, minutes % 60L, all % 60L)
        } else {
            String.format("%d:%02d", minutes, all % 60L)
        }
    }

    /** A change of speed reaches a replay that is already running. */
    private fun restartPlayback() {
        if (logPlayer?.isPlaying() == true) {
            logPlayer?.stop()
            logPlayer?.startPlayback()
        }
    }

    private fun showDialog(dialog: AlertDialog) {
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        );
        dialog.show();
        if (!this.fullscreenWindow) {
            dialog.window?.decorView?.systemUiVisibility = 0
        } else {
            dialog.window?.decorView?.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE)
        }
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    protected fun updateScreenOrientation() {
        val screenRotation: String = preferenceManager.getScreenOrientationLock()
        try {
            requestedOrientation = when (screenRotation) {
                "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "Reverse Landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } catch (e: Exception) {
        }
    }

    //SensorTimeoutListener
    private fun updateSetSensorGrayed(sensorId: Int) {
        var alpha = 1f;
        if (this.sensorTimeoutManager.getSensorTimeout(sensorId)) alpha = 0.5f;
        when (sensorId) {
            SensorTimeoutManager.SENSOR_GPS -> {
                this.satellites.setAlpha(alpha);
                this.traveled_distance.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_DISTANCE -> {
                this.distance.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_ALTITUDE -> {
                this.altitude.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_GPS_ALTITUDE -> {
                this.altitude_msl.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_RSSI -> {
                this.rssi.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_UP_LQ -> {
                this.upLq.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_DN_LQ -> {
                this.dnLq.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_ELRS_MODE -> {
                this.elrsRate.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_VOLTAGE -> {
                this.voltage.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_CELL_VOLTAGE -> {
                this.cell_voltage.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_CURRENT -> {
                this.current.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_SPEED -> {
                this.speed.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_AIRSPEED -> {
                this.airspeed.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_VSPEED -> {
                this.vspeed.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_THROTTLE -> {
                this.throttle.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_FUEL -> {
                this.fuel.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_RC_CHANNELS -> {
                this.rc_widget.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_STATUSTEXT -> {
                if (this.sensorTimeoutManager.getSensorTimeout(sensorId)) {
                    this.statustext.text = "";
                }
            }
            SensorTimeoutManager.SENSOR_DN_SNR -> {
                this.dnSnr.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_UP_SNR -> {
                this.upSnr.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_ANT -> {
                this.ant.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_POWER -> {
                this.power.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_RSSI_DBM_1 -> {
                this.rssiDbm1.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_RSSI_DBM_2 -> {
                this.rssiDbm2.setAlpha(alpha);
            }
            SensorTimeoutManager.SENSOR_RSSI_DBM_D -> {
                this.rssiDbmd.setAlpha(alpha);
            }
        }
    }

    //SensorTimeoutListener
    override fun onSensorTimeout(sensorId: Int) {
        runOnUiThread {
            this.updateSetSensorGrayed(sensorId);
        }
    }

    //SensorTimeoutListener
    override fun onSensorData(sensorId: Int) {
        runOnUiThread {
            this.updateSetSensorGrayed(sensorId);
        }
    }

    override fun onTelemetryRate(rate: Int) {
        runOnUiThread {
            if (rate < 1000) {
                this.tlmRate.text = "${rate} b/s"
            } else {
                this.tlmRate.text = "${"%.1f".format(rate / 1000f)} kb/s"
            }
        }
    }

    /**
     * Whether the camera keeps up with the model at all.
     *
     * Either button does that; they differ in where the camera is put, not in
     * whether it follows. Riding behind the model is keeping up with it, so
     * everything that asks "should the camera move to the flight" asks this
     * rather than asking for plain tracking and getting no for an answer while
     * the chase is on.
     */
    private fun keepingUp(): Boolean = followMode || chaseMode

    fun setFollowMode(mode: Boolean) {
        followMode = mode
        // One or the other, never both. Asking for plain tracking is asking to
        // stop riding behind it.
        if (mode && chaseMode) setChaseMode(false)
        this.followButton.imageAlpha = if (followMode) 255 else 128
    }

    /**
     * Riding behind the model, looking the way it is going: over its shoulder
     * in 3D, and the map turned to its heading in 2D.
     *
     * It keeps up with the model itself, so plain tracking gives way to it.
     */
    private fun setChaseMode(on: Boolean) {
        if (chaseMode == on) return
        chaseMode = on
        chaseButton.imageAlpha = if (on) 255 else 128
        if (on && !modelHeadingKnown) {
            // armed, not engaged: the mode stands and takes hold the moment
            // a flight gives it a heading to ride behind
            Toast.makeText(this,
                "Chase rides behind the model - it engages when a flight is up",
                Toast.LENGTH_SHORT).show()
        }
        if (on) {
            // One or the other. The chase used to borrow tracking and give it
            // back on the way out, so whether turning the chase off left the
            // model being tracked depended on what had been on before it — and
            // asking to stop riding behind the model turned plain tracking on
            // instead of stopping. Two buttons, each answering for itself, and
            // never both lit.
            followMode = false
            followButton.imageAlpha = 128
        }
        // Following first: the ground view drops the chase when it is told to
        // stop keeping up, so it has to be told to keep up before it is told to
        // ride behind.
        terrain3D?.setFollowing(keepingUp())
        terrain3D?.setChasing(on)
        if (on) applyHeadingUp()
        // The angle is left where the chase left it, in both views: the
        // north-up button is the way back to north and it is one tap, and
        // swinging the map round unasked, at the moment somebody has asked for
        // something else, is a movement nobody wanted.
    }

    /** How far the map has been dragged and turned away from the model. */
    private var mapLeanLat = 0.0
    private var mapLeanLon = 0.0
    private var mapLeanTurn = 0f

    /** When the leaned-away camera was last retargeted; it glides between. */
    private var leanCameraAt = 0L

    /**
     * Take up whatever the hand has just done as the new offset.
     *
     * Read from where the map actually is rather than accumulated from the
     * gesture, so it cannot drift: after any touch the offset is exactly the
     * gap between the model and what is on screen.
     */
    /**
     * Where the model is drawn, as against where it was last heard from.
     *
     * A fix lands five or ten times a second and the screen draws a hundred and
     * twenty, so a marker put straight onto each one teleports — it does not
     * move. The latest few fixes are kept with their arrival times, and the
     * model walks between the two surrounding the frame being drawn. That
     * gives the camera a steady velocity instead of the hurry/coast pulse of
     * easing separately towards every newly arrived fix.
     *
     * The recorded track still gets every fix as it arrives, unsmoothed. This
     * is about what the eye sees, not about what is kept.
     */
    private var shownLat = Double.NaN
    private var shownLon = Double.NaN
    private var shownMarkerHeading = Float.NaN

    /**
     * The model and camera properties that belong to the geometry MapLibre is
     * displaying now. A GeoJSON source update reaches the rendered line one
     * frame after the location and camera properties submitted beside it. If
     * all three are handed the new point together, the two lines visibly pull
     * from the model's previous point at high replay speed. Holding only the
     * model and camera for that same submitted frame keeps one presentation
     * time across the renderer; [shownLat]/[shownLon] remain the one easing
     * clock used to calculate everything.
     */
    private var presentedLat = Double.NaN
    private var presentedLon = Double.NaN
    private var presentedMarkerHeading = Float.NaN
    /** The actual model/camera properties most recently handed to MapLibre. */
    private var submittedLat = Double.NaN
    private var submittedLon = Double.NaN
    private var submittedMarkerHeading = Float.NaN
    private var smoothingMarker = false

    /** A position fix and the monotonic time at which this screen received it. */
    private class SeenFix(val at: Long, val lat: Double, val lon: Double)

    /** Raw fixes withheld until the aircraft's presentation clock reaches them. */
    private class TimedTrackBatch(val at: Long, val points: List<Position>)

    private val seenFixes = ArrayList<SeenFix>()
    private val pendingVisualTrack = java.util.ArrayDeque<TimedTrackBatch>()
    private val recentVisualTrack = java.util.ArrayDeque<Position>()
    private val publishedVisualTrack = ArrayList<Position>()
    private var gatheredVisualBatch: List<Position>? = null
    private var presentedMoment = 0L

    /**
     * A little more than one measured fix interval. Looking this far back gives
     * each drawn frame a real fix on either side, so no velocity prediction is
     * needed and the ground moves evenly beneath the camera.
     */
    private var walkDelayMs = 80L

    private fun rememberMotionFix(lat: Double, lon: Double): Long {
        val now = android.os.SystemClock.uptimeMillis()
        seenFixes.lastOrNull()?.let { previous ->
            val gap = now - previous.at
            if (gap > 1000L) {
                // A resumed link is a new placement, not a slow journey across
                // all the ground between its old and new positions.
                seenFixes.clear()
                walkDelayMs = 80L
            } else if (gap > 0L) {
                val wanted = Math.min(250L, Math.max(24L, gap * 3L / 2L))
                walkDelayMs = (walkDelayMs * 3L + wanted) / 4L
            }
        }
        seenFixes.add(SeenFix(now, lat, lon))
        while (seenFixes.size > 24) seenFixes.removeAt(0)
        return now
    }

    /** Draw the delayed point on the recorded motion at each display frame. */
    private val markerStep = object : Runnable {
        override fun run() {
            smoothingMarker = false
            val map = map ?: return
            if (lastGPS.lat == 0.0 && lastGPS.lon == 0.0) return
            var moving = false
            val moment = android.os.SystemClock.uptimeMillis() - walkDelayMs
            var before: SeenFix? = null
            var after: SeenFix? = null
            for (fix in seenFixes) {
                if (fix.at <= moment) before = fix
                else {
                    after = fix
                    break
                }
            }
            when {
                before != null && after != null && after.at > before.at -> {
                    val part = (moment - before.at).toDouble() /
                        (after.at - before.at).toDouble()
                    val on = Math.max(0.0, Math.min(1.0, part))
                    shownLat = before.lat + (after.lat - before.lat) * on
                    shownLon = before.lon + (after.lon - before.lon) * on
                    moving = true
                }
                before != null -> {
                    shownLat = before.lat
                    shownLon = before.lon
                }
                seenFixes.isNotEmpty() -> {
                    shownLat = seenFixes[0].lat
                    shownLon = seenFixes[0].lon
                    // Keep drawing until the delayed clock reaches this first
                    // sample; a second fix may arrive in the meantime.
                    moving = true
                }
                else -> {
                    shownLat = lastGPS.lat
                    shownLon = lastGPS.lon
                }
            }

            if (shownMarkerHeading.isNaN()) {
                shownMarkerHeading = lastHeading
            } else {
                val turn = ((lastHeading - shownMarkerHeading) % 360f + 540f) % 360f - 180f
                if (Math.abs(turn) > 0.05f) {
                    shownMarkerHeading =
                        ((shownMarkerHeading + turn * HEADING_EASE) % 360f + 360f) % 360f
                    moving = true
                }
            }

            val firstPresentation = presentedLat.isNaN() || presentedLon.isNaN()
            val where = if (firstPresentation) {
                Position(shownLat, shownLon)
            } else {
                Position(presentedLat, presentedLon)
            }
            val presentedHeading = if (presentedMarkerHeading.isNaN()) {
                shownMarkerHeading
            } else {
                presentedMarkerHeading
            }
            val presentationMustCatchUp = firstPresentation ||
                presentedLat != shownLat || presentedLon != shownLon ||
                presentedMarkerHeading != shownMarkerHeading

            val visualMoment = if (presentedMoment == 0L) {
                moment
            } else {
                presentedMoment
            }
            updateTimedFlightTrack(visualMoment, where)

            marker?.place(where, presentedHeading)
            submittedLat = where.lat
            submittedLon = where.lon
            submittedMarkerHeading = presentedHeading
            updateHeading(false, where, presentedHeading)
            updateHomeLine(where)
            // Stage every attachment first. moveCameraNow then publishes that
            // complete scene before exposing its matching camera transform.
            if (keepingUp() && map.initialized()) {
                val orientation = if (chaseMode && modelHeadingKnown && terrain3D == null) {
                    -presentedHeading + mapLeanTurn
                } else {
                    Float.NaN
                }
                val leaned = mapLeanLat != 0.0 || mapLeanLon != 0.0
                if (!leaned) {
                    map.moveCameraNow(
                        Position(where.lat, where.lon),
                        orientation
                    )
                } else {
                    // Leaned away to study other ground, the camera still
                    // follows — but seldom and eased, not sixty instant
                    // jumps a second. Each jump cancels the tiles in
                    // flight, so the fresh ground under a lean completed
                    // its fetches and had them discarded within the same
                    // frame, forever: the map starved exactly where the
                    // hand had asked to look. The model is off with the
                    // flight; nothing here needs per-frame butter.
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (nowMs - leanCameraAt >= 500L) {
                        leanCameraAt = nowMs
                        map.moveCamera(
                            Position(where.lat + mapLeanLat, where.lon + mapLeanLon)
                        )
                    }
                }
            }
            // Model, flight head, home and heading become visible as one
            // immutable renderer snapshot. Without this boundary the GL
            // thread can sample between their four otherwise-correct writes.
            map.commitVisualFrame()
            presentedLat = shownLat
            presentedLon = shownLon
            presentedMarkerHeading = shownMarkerHeading
            presentedMoment = moment
            if (presentationMustCatchUp) moving = true
            if (moving) keepSmoothing()
        }
    }

    /** Reveal history only as far as the model, with an overlapping exact head. */
    private fun updateTimedFlightTrack(moment: Long, where: Position) {
        var released = 0
        while (pendingVisualTrack.isNotEmpty() && pendingVisualTrack.first.at <= moment) {
            val batch = pendingVisualTrack.removeFirst()
            publishVisualTrack(batch.points)
            released += batch.points.size
        }
        if (released > 0) {
            commitRouteLinePoints()
        }

        val head = ArrayList<Position>(recentVisualTrack.size + 1)
        head.addAll(recentVisualTrack)
        if (head.isEmpty() || head.last() != where) head.add(where)
        if (head.size == 1) head.add(where)
        flightHeadLine?.setPoints(head)
    }

    /** Put decoded points onto both halves of the overlapping 2D flight line. */
    private fun publishVisualTrack(points: List<Position>) {
        if (points.isEmpty()) return
        polyLine?.submitPoints(points)
        publishedVisualTrack.addAll(points)
        for (point in points) {
            recentVisualTrack.addLast(point)
            while (recentVisualTrack.size > REPLAY_TRACK_HEAD_POINTS) {
                recentVisualTrack.removeFirst()
            }
        }
    }

    /** Publish every real fix already decoded before a paused seek starts. */
    private fun publishAllPendingVisualTrack() {
        while (pendingVisualTrack.isNotEmpty()) {
            publishVisualTrack(pendingVisualTrack.removeFirst().points)
        }
    }

    /**
     * A seek is a placement, not motion between the old and new bar positions.
     *
     * The long GeoJSON line already received every decoded point through the
     * seek. Its immediate overlapping head used to retain the pre-seek tail and
     * append the new model position to it, drawing a straight purple shortcut
     * over ground that was never flown. Rebuild that small head from the route
     * itself and put the presentation clock exactly at its endpoint.
     */
    private fun settlePausedReplaySeek(target: Position) {
        pendingVisualTrack.clear()

        val tail = replayTrackTail(publishedVisualTrack, target, REPLAY_TRACK_HEAD_POINTS)
        recentVisualTrack.clear()
        for (point in tail) recentVisualTrack.addLast(point)

        val now = android.os.SystemClock.uptimeMillis()
        seenFixes.clear()
        seenFixes.add(SeenFix(now, target.lat, target.lon))
        shownLat = target.lat
        shownLon = target.lon
        presentedLat = target.lat
        presentedLon = target.lon
        submittedLat = target.lat
        submittedLon = target.lon
        presentedMoment = now - walkDelayMs

        val head = ArrayList(tail)
        if (head.size == 1) head.add(target)
        flightHeadLine?.setPoints(head)
        keepSmoothing()
    }

    private fun keepSmoothing() {
        if (smoothingMarker) return
        smoothingMarker = true
        mapHolder.postOnAnimation(markerStep)
    }

    /** Where the model is being drawn, for anything that has to sit with it. */
    private fun shownPosition(): Position =
        if (shownLat.isNaN()) lastGPS else Position(shownLat, shownLon)

    /** Where the renderer-owned model and the chase camera are being shown. */
    private fun presentedPosition(): Position =
        if (submittedLat.isNaN()) shownPosition() else Position(submittedLat, submittedLon)

    private fun leanOutOfFollowing() {
        val centre = map?.getCentre() ?: return
        if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) {
            val model = presentedPosition()
            mapLeanLat = centre.lat - model.lat
            mapLeanLon = centre.lon - model.lon
        }
        if (chaseMode && modelHeadingKnown) {
            val heading =
                if (presentedMarkerHeading.isNaN()) {
                    if (shownMarkerHeading.isNaN()) lastHeading else shownMarkerHeading
                } else {
                    presentedMarkerHeading
                }
            val wanted = -heading
            mapLeanTurn = ((map!!.getMapOrientation() - wanted) % 360f + 540f) % 360f - 180f
        }
    }

    private fun centreOnModel() {
        mapLeanLat = 0.0
        mapLeanLon = 0.0
        mapLeanTurn = 0f
    }


    /**
     * The map turned so the model's heading is up. The 3D view does its own.
     *
     * Eased, and only when the angle has really moved: a heading arrives many
     * times a second and wanders by a degree or two on each, and redrawing the
     * whole map for that is both a shake and a waste.
     */
    private fun applyHeadingUp() {
        if (!chaseMode || !modelHeadingKnown || terrain3D != null) return
        // The angle it should end at, plus however far it has been turned away
        // from that by hand. The map eases towards it on its own clock, which
        // is the screen's rather than the telemetry's.
        map?.setMapOrientation(-lastHeading + mapLeanTurn)
    }

    fun commitRouteLinePoints() {
        // Nothing new, nothing to do. This is called for every batch the link
        // delivers — tens of times a second on a busy one — while points only
        // arrive as fast as the fixes do.
        if (polyLine?.spoints?.isEmpty() != false) return
        polyLine?.commitPoints()
    }

    fun showRenameLogDialog(fileName: String? = null) {
        if (!requestWritePermission(RequestWritePermissionSequenceType.RENAME)) return;

        val currentFileName = fileName ?: replayFileString ?: "";
        val dot = currentFileName.lastIndexOf('.')
        val extension = if (dot > 0) currentFileName.substring(dot) else ""
        val editText = EditText(this)
        editText.setText(if (dot > 0) currentFileName.substring(0, dot) else currentFileName)
        editText.setSelection(editText.text.length)

        this.showDialog( AlertDialog.Builder(this)
        .setTitle("Rename Log")
        .setView(editText)
        .setPositiveButton("Rename") { dialog: DialogInterface, which: Int ->
            val typed = editText.text.toString().trim()
            if (typed.isEmpty()) {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
            } else {
                renameLog(currentFileName, typed + extension)
            }
            dialog.dismiss()
        }
        .setNegativeButton("Cancel") { dialog: DialogInterface, which: Int ->
            dialog.dismiss()
        }.create())
    }

    private fun renameLog(currentFileName: String, newFileName: String) {
        val currentFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), currentFileName)
        val newFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), newFileName)

        if (currentFile.renameTo(newFile)) {
            Toast.makeText(this, "Log renamed successfully.", Toast.LENGTH_SHORT).show()

            val csvCurrentFileName = replaceExtension( currentFileName, ".csv")
            val csvNewFileName = replaceExtension( newFileName, ".csv")
            val csvCurrentFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), csvCurrentFileName)
            val csvNewFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), csvNewFileName)
            csvCurrentFile.renameTo(csvNewFile)

            if (currentFileName == replayFileString) {
                replayFileString = newFileName;
            }
        } else {
            Toast.makeText(this, "Failed to rename log.", Toast.LENGTH_SHORT).show()
        }
    }


    /** Long press a log in the picker: what can be done to that one log. */
    private fun showLogActionsDialog(file: File) {
        val actions = arrayOf("Rename", "Delete")
        this.showDialog(
            AlertDialog.Builder(this)
                .setTitle(file.nameWithoutExtension)
                .setItems(actions) { d, which ->
                    d.dismiss()
                    when (which) {
                        0 -> showRenameLogDialog(file.name)
                        1 -> showDeleteLogDialog(file.name)
                    }
                }
                .create()
        )
    }

    /**
     * Clear out the recordings. Named with the count and the space they take,
     * because "delete all" on its own is not enough to decide by, and this
     * cannot be undone.
     */
    private fun showDeleteAllLogsDialog(files: List<File>) {
        if (files.isEmpty()) {
            Toast.makeText(this, "No logs to delete", Toast.LENGTH_SHORT).show()
            return
        }
        if (!requestWritePermission(RequestWritePermissionSequenceType.LOG_PICKER)) return

        val megabytes = files.sumByDouble { it.length().toDouble() } / (1024 * 1024)
        this.showDialog(
            AlertDialog.Builder(this)
                .setTitle("Delete all logs")
                .setMessage(
                    "Delete all " + files.size + " logs (" + "%.1f".format(megabytes) +
                        " MB)?" + 10.toChar() + 10.toChar() +
                        "This cannot be undone. Any CSV files recorded alongside them go too."
                )
                .setPositiveButton("Delete all") { d, _ ->
                    deleteAllLogs(files)
                    d.dismiss()
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .create()
        )
    }

    private fun deleteAllLogs(files: List<File>) {
        var deleted = 0
        for (file in files) {
            if (file.delete()) {
                deleted++
                // the CSV recorded alongside it, as deleting one log does
                File(file.parentFile, replaceExtension(file.name, ".csv")).delete()
            }
        }
        val failed = files.size - deleted
        val message = if (failed == 0) {
            "Deleted " + deleted + " logs"
        } else {
            "Deleted " + deleted + " logs, " + failed + " could not be removed"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        lastFileDialogSelection = ""
        lastFileDialogSelectionIndex = -1
        if (replayFileString != null) {
            switchToIdleState()
            closeReplay()
        }
    }

    fun showDeleteLogDialog(fileName: String? = null) {
        if (!requestWritePermission(RequestWritePermissionSequenceType.DELETE)) return;

        val target = fileName ?: replayFileString ?: ""
        this.showDialog( AlertDialog.Builder(this)
        .setTitle("Delete Log")
        .setMessage("Delete " + target + "?")
        .setPositiveButton("Delete") { dialog: DialogInterface, which: Int ->
            deleteLog(target)
            dialog.dismiss()
        }
        .setNegativeButton("Cancel") { dialog: DialogInterface, which: Int ->
            dialog.dismiss()
        }.create())
    }

    fun deleteLog(fileName: String)
    {
        val currentFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), fileName)

        if (currentFile.delete()) {
            Toast.makeText(this, "Log deleted successfully.", Toast.LENGTH_SHORT).show()

            val csvFileName = replaceExtension( fileName, ".csv")
            val currentFileCSV = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), csvFileName)
            currentFileCSV.delete();

            if (fileName == replayFileString) {
                // the log being replayed has just been deleted, so the replay
                // ends the same way as closing it
                switchToIdleState()
                closeReplay()
            }
        } else {
            Toast.makeText(this, "Failed to delete log.", Toast.LENGTH_SHORT).show()
        }
    }

    fun replaceExtension(fileName: String, newExtension : String): String {
        val extensionSeparatorIndex = fileName.lastIndexOf(".")
        if (extensionSeparatorIndex != -1) {
            val nameWithoutExtension = fileName.substring(0, extensionSeparatorIndex)
            return nameWithoutExtension + newExtension
        }
        return fileName
    }

    fun showExportGPXDialog() {
        this.logPlayer?.stop();
        if (!requestWritePermission(RequestWritePermissionSequenceType.EXPORT_GPX)) return;

        val editText = EditText(this)
        editText.setText(this.logPlayer?.launchPointMSLAltitude.toString())
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        editText.filters = arrayOf(InputFilter.LengthFilter(10)) // Set maximum input length, if needed
        editText.setSelection(editText.text.length)

        this.showDialog(AlertDialog.Builder(this)
        .setTitle("Enter launch point MSL altitude, m:")
        .setView(editText)
        .setPositiveButton("OK") { dialog: DialogInterface, which: Int ->
            val enteredNumber = editText.text.toString().toFloatOrNull()
            if (enteredNumber != null) {
                val fileName = replaceExtension( replayFileString?:"", ".gpx")
                this.logPlayer?.exportGPX(fileName, enteredNumber)
                Toast.makeText(this, fileName + " saved", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        .setNegativeButton("Cancel") { dialog: DialogInterface, which: Int ->
            dialog.dismiss()
        }.create())
    }

    fun showExportKMLDialog1() {
        this.logPlayer?.stop();
        if (!requestWritePermission(RequestWritePermissionSequenceType.EXPORT_KML)) return;

        val option1 = "Clamp to ground";
        val option2 = "Relative to ground";
        val option3 = "MSL";
        val options = arrayOf(option1, option2, option3)

        val fileName = replaceExtension( replayFileString?:"", ".kml")

        this.showDialog( AlertDialog.Builder(this)
        .setTitle("Select altitude mode:")
        .setItems(options) { dialog: DialogInterface, which: Int ->
            val selectedOption = options[which]
            when (selectedOption) {
                option1 -> {
                    this.logPlayer?.exportKML(fileName, 0.0f, "clampToGround" )
                    Toast.makeText(this, fileName + " saved", Toast.LENGTH_SHORT).show()
                }
                option2 -> {
                    this.showExportKMLDialog2(fileName, "relativeToGround","Adjust track altitude, m:", 0)
                }
                option3 -> {
                    this.showExportKMLDialog2(fileName, "absolute","Enter launch point MSL altitude, m:", this.logPlayer?.launchPointMSLAltitude?:0)
                }
            }
            dialog.dismiss()
        }.create())
    }

    fun showExportKMLDialog2(fileName: String, altitudeMode: String, requestText: String, defaultValue: Int) {
        val editText = EditText(this)
        editText.setText(defaultValue.toString())
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        editText.filters = arrayOf(InputFilter.LengthFilter(10)) // Set maximum input length, if needed
        editText.setSelection(editText.text.length)

        this.showDialog( AlertDialog.Builder(this)
        .setTitle(requestText)
        .setView(editText)
        .setPositiveButton("OK") { dialog: DialogInterface, which: Int ->
            val enteredNumber = editText.text.toString().toFloatOrNull()
            if (enteredNumber != null) {
                this.logPlayer?.exportKML(fileName, enteredNumber,altitudeMode)
                Toast.makeText(this, fileName + " saved", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        .setNegativeButton("Cancel") { dialog: DialogInterface, which: Int ->
            dialog.dismiss()
        }.create())
    }


    fun requestWritePermission(seq: RequestWritePermissionSequenceType): Boolean {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_DENIED
        ) {
            requestWritePermissionSequence = seq;
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_PERMISSION
            )
            return false;
        }
        return true;
    }

    // --- FlightRadar24 nearby aircraft ---

    private fun startFr24() {
        // Aircraft are where they are now, around where this phone is now. Over
        // a replay of last week's flight they are neither the right aircraft
        // nor in the right place, so a replay does without them.
        if (isInReplayMode() || !preferenceManager.isFr24Enabled()) return
        // onResume and leaving replay can meet in the same foreground lifetime.
        // Keep the manager already polling instead of orphaning it and its
        // executor behind a new reference.
        if (fr24Manager != null) return
        val manager = Fr24Manager(preferenceManager, this)
        fr24Manager = manager
        manager.start { myLastKnownPlace() }
    }

    /**
     * [clear] scorches the sky as well: right for entering a replay, where
     * this afternoon's airliners have no business standing over last
     * month's flight — and wrong for a trip to the home screen, which used
     * to come back to an empty sky for a whole poll interval. Paused, the
     * last snapshot stands; the immediate fetch on resume replaces it in
     * seconds.
     */
    private fun stopFr24(clear: Boolean) {
        fr24Manager?.stop()
        fr24Manager = null
        if (!clear) return
        airplaneMarkers.values.forEach { it.remove() }
        airplaneMarkers.clear()
        // The ground view's posts and the list they are rebuilt from. Without
        // this a replay opened from the 3D view kept this afternoon's airliners
        // standing over last month's flight — the map had cleared its own, and
        // a view built later put them back from the list.
        lastAirplanes = emptyList()
        terrain3D?.setTraffic(emptyList())
    }

    private var lastAirplanes: List<Fr24Manager.AirplaneInfo> = emptyList()

    override fun onAirplanesUpdated(airplanes: List<Fr24Manager.AirplaneInfo>) {
        lastAirplanes = airplanes
        runOnUiThread { terrain3D?.setTraffic(airplanes) }
        juricabi.com.telemetry.utils.DebugLog.note("Fr24",
            "update: ${airplanes.size} aircraft, markers=${airplaneMarkers.size}, " +
            "map=${if (map == null) "null" else "up"}")
        val currentIds = airplanes.map { it.flightId }.toSet()

        // Remove stale markers
        val staleIds = airplaneMarkers.keys.filter { it !in currentIds }
        staleIds.forEach { id ->
            airplaneMarkers.remove(id)?.remove()
        }

        // Update or create markers
        for (airplane in airplanes) {
            val title = airplane.displayName
            val snippet = airplaneSummary(airplane)

            val existing = airplaneMarkers[airplane.flightId]
            if (existing != null) {
                existing.position = Position(airplane.lat.toDouble(), airplane.lon.toDouble())
                existing.rotation = airplane.track.toFloat()
                existing.title = title
                existing.snippet = snippet
            } else {
                val m = map?.addMarker(
                    R.drawable.ic_airplane_fr24,
                    Position(airplane.lat.toDouble(), airplane.lon.toDouble())
                )
                if (m != null) {
                    m.rotation = airplane.track.toFloat()
                    m.title = title
                    m.snippet = snippet
                    airplaneMarkers[airplane.flightId] = m
                }
            }
        }
    }

    /** Who an aircraft is, one text for the map's bubble and the 3D tap alike. */
    private fun airplaneSummary(airplane: Fr24Manager.AirplaneInfo): String = buildString {
        if (airplane.aircraftType.isNotEmpty()) append(airplane.aircraftType)
        if (airplane.registration.isNotEmpty()) {
            if (isNotEmpty()) append(" | ")
            append(airplane.registration)
        }
        append("\nAlt: ${airplane.altMeters}m | Spd: ${airplane.speedKmh}km/h")
        val route = airplane.route
        if (route.isNotEmpty()) append("\n$route")
    }

    override fun onProximityWarning(
        airplane: Fr24Manager.AirplaneInfo,
        distanceMeters: Double,
        directionDeg: Double
    ) {
        val cardinal = bearingToCardinal(directionDeg)
        val distKm = distanceMeters / 1000.0
        val msg = "TRAFFIC: ${airplane.displayName} ${"%.1f".format(distKm)}km $cardinal, alt ${airplane.altMeters}m"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

        if (ttsReady) {
            val spokenDir = bearingToSpoken(directionDeg)
            val speech = "Traffic, ${spokenDir}, ${"%.1f".format(distKm)} kilometers, altitude ${airplane.altMeters} meters"
            tts?.speak(speech, TextToSpeech.QUEUE_ADD, null, "fr24_warning_${airplane.flightId}")
        }
    }

    private fun bearingToCardinal(deg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((deg + 22.5) / 45.0).toInt() % 8
        return dirs[index]
    }

    private fun bearingToSpoken(deg: Double): String {
        val dirs = arrayOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
        val index = ((deg + 22.5) / 45.0).toInt() % 8
        return dirs[index]
    }

}
