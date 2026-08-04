package crazydude.com.telemetry.ui

import android.app.Activity
import android.app.PendingIntent
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.ActivityInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.nex3z.flowlayout.FlowLayout
import crazydude.com.telemetry.R
import crazydude.com.telemetry.converter.Converter
import crazydude.com.telemetry.manager.FlightPlanManager
import crazydude.com.telemetry.protocol.GhstProtocol
import crazydude.com.telemetry.manager.Fr24Manager
import crazydude.com.telemetry.manager.PreferenceManager
import crazydude.com.telemetry.manager.SensorTimeoutManager
import crazydude.com.telemetry.maps.MapLine
import crazydude.com.telemetry.maps.MapMarker
import crazydude.com.telemetry.maps.MapWrapper
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.maps.osm.OsmMapWrapper
import crazydude.com.telemetry.utils.GeoUtils
import crazydude.com.telemetry.utils.PlusCode
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.protocol.pollers.NetworkDataPoller
import crazydude.com.telemetry.protocol.pollers.LogPlayer
import crazydude.com.telemetry.utils.LocalNetworks
import crazydude.com.telemetry.utils.WifiNetworkBinder
import crazydude.com.telemetry.service.DataService
import kotlinx.android.synthetic.main.top_layout.*
import kotlinx.android.synthetic.main.view_map.*
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import uk.co.deanwild.materialshowcaseview.IShowcaseListener
import uk.co.deanwild.materialshowcaseview.MaterialShowcaseView
import crazydude.com.telemetry.logger.OperatorTrack
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
    private val flightPath: List<crazydude.com.telemetry.gl.TerrainScene.TrackPoint>
        get() = crazydude.com.telemetry.gl.LiveFlightPath.snapshot()
    private var lastGpsAltitudeMsl = Float.NaN
    private var lastGpsAltitudeAt = 0L

    @Volatile private var detectedCells = 0
    @Volatile private var highestPackVoltage = 0f
    private var cellsAsked = false
    private var cellsAnswered = false

    companion object {

        // Ghost RF profiles, matching EdgeTX ghstRfProfileValue
        private val GHST_RF_PROFILES = arrayOf(
            "Auto", "Norm", "Race", "Pure", "Long", "Unused", "Race2", "Pure2"
        )
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

        private val ESRI_SATELLITE_TILE_SOURCE = object : OnlineTileSourceBase(
            "ESRISatellite", 0, 18, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
                    "/" + MapTileIndex.getY(pMapTileIndex) +
                    "/" + MapTileIndex.getX(pMapTileIndex)
            }
        }

        private val ESRI_TRANSPORTATION_OVERLAY_TILE_SOURCE = object : OnlineTileSourceBase(
            "ESRITransportation", 0, 18, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
                    "/" + MapTileIndex.getY(pMapTileIndex) +
                    "/" + MapTileIndex.getX(pMapTileIndex)
            }
        }

        private val ESRI_BOUNDARIES_PLACES_OVERLAY_TILE_SOURCE = object : OnlineTileSourceBase(
            "ESRIBoundariesPlaces", 0, 18, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
                    "/" + MapTileIndex.getY(pMapTileIndex) +
                    "/" + MapTileIndex.getX(pMapTileIndex)
            }
        }

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
    /** The fix being believed, so a worse one cannot take its place. */
    @Volatile private var bestPhoneFix: Location? = null

    /**
     * Both providers are listened to, because indoors the satellites never
     * answer — but a mast puts you hundreds of metres from where they say, and
     * one arriving between two good fixes threw the arrow across the field and
     * back again.
     *
     * So: anything when nothing is known, anything once what is known is stale,
     * and otherwise only a fix at least as accurate as the one in hand.
     */
    private fun worthBelieving(fix: Location): Boolean {
        val held = bestPhoneFix ?: return true
        // A provider with a skewed clock can stamp a fix in the future, and
        // every honest one after it looks old by comparison. Age is measured
        // against now as well, so nothing can lock this shut.
        if (System.currentTimeMillis() - held.time > 20000L) return true
        val newer = fix.time - held.time
        if (newer > 20000L) return true
        if (newer < -20000L) return false
        if (!fix.hasAccuracy()) return !held.hasAccuracy()
        if (!held.hasAccuracy()) return true
        return fix.accuracy <= held.accuracy || fix.provider == held.provider
    }

    /**
     * Which way this phone is facing.
     *
     * Read here rather than borrowed from whichever view is open: the 3D view
     * has a reader for its arrow and the map has another for its own, and
     * neither exists while the other is on screen — but the recording wants it
     * in every mode, and a replay is worth nothing without it.
     */
    @Volatile private var phoneHeading = Float.NaN
    private val phoneGravity = FloatArray(3)
    private val phoneGeomagnetic = FloatArray(3)
    private var hasPhoneGravity = false
    private var hasPhoneGeomagnetic = false

    private val phoneCompass = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    settle(phoneGravity, event.values, hasPhoneGravity)
                    hasPhoneGravity = true
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    settle(phoneGeomagnetic, event.values, hasPhoneGeomagnetic)
                    hasPhoneGeomagnetic = true
                }
            }
            if (!hasPhoneGravity || !hasPhoneGeomagnetic) return
            val r = FloatArray(9)
            if (!SensorManager.getRotationMatrix(r, null, phoneGravity, phoneGeomagnetic)) return
            val orientation = FloatArray(3)
            SensorManager.getOrientation(r, orientation)
            var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (degrees < 0) degrees += 360f
            phoneHeading = degrees
            recordWhereIAm()
            // and to everything that draws with it. Not over a replay, which is
            // saying which way the phone was facing then.
            if (showLiveArrow()) {
                map?.setPhoneBearing(degrees)
                terrain3D?.setMyHeading(degrees)
            }
        }
    }

    /** A fifth of the way to each reading: a compass on its own jitters. */
    private fun settle(held: FloatArray, fresh: FloatArray, had: Boolean) {
        for (i in held.indices) {
            held[i] = if (had) held[i] + (fresh[i] - held[i]) * 0.2f else fresh[i]
        }
    }

    /**
     * Hand the recording where this phone is, so a replay can put it back.
     *
     * Only where it is being recorded at all — it lands in the CSV, which
     * travels with the log wherever the log goes.
     */
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
        tellMapWhereIAm()
        val fix = bestPhoneFix ?: return
        if (!showLiveArrow()) return
        terrain3D?.setMyPosition(fix.latitude, fix.longitude, phoneAccuracy)
        if (!phoneHeading.isNaN()) terrain3D?.setMyHeading(phoneHeading)
    }

    /** What the screen knows about this phone, onto the map that draws it. */
    private fun tellMapWhereIAm() {
        // The system's last known place until this screen has heard one of its
        // own: the map used to ask for that itself, and without it a map built
        // before the first fix has no arrow on it at all.
        val fix = bestPhoneFix
        val where = if (fix != null) {
            Position(fix.latitude, fix.longitude)
        } else {
            myLastKnownPlace() ?: return
        }
        map?.setPhoneLocation(
            where,
            if (fix != null && fix.hasAccuracy()) fix.accuracy else Float.NaN
        )
        if (!phoneHeading.isNaN()) map?.setPhoneBearing(phoneHeading)
    }

    private fun recordWhereIAm() {
        if (!preferenceManager.isMyPositionLoggingEnabled()) {
            // turned off mid-flight: the rows that follow say nothing, rather
            // than repeating the last place it was told about for ever
            dataService?.setPhonePosition(
                Double.NaN, Double.NaN, Float.NaN, Float.NaN
            )
            return
        }
        val fix = bestPhoneFix ?: return
        dataService?.setPhonePosition(
            fix.latitude, fix.longitude,
            if (fix.hasAccuracy()) fix.accuracy else Float.NaN,
            phoneHeading
        )
    }

    private val phoneLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!worthBelieving(location)) return
            bestPhoneFix = location
            recordWhereIAm()
            // kept for the 3D view, which draws the same accuracy circle the map does
            phoneAccuracy = if (location.hasAccuracy()) location.accuracy else 0f
            runOnUiThread {
                updateHomeLine()
                // not over a replay, which is drawing where the phone was then
                if (showLiveArrow()) {
                    terrain3D?.setMyPosition(location.latitude, location.longitude, phoneAccuracy)
                    // and the map's arrow, which used to listen to the
                    // satellites itself and answer slightly differently
                    tellMapWhereIAm()
                } else {
                    terrain3D?.hideMyLocation()
                }
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @Volatile private var phoneAccuracy = 0f
    private var terrain3D: Terrain3DView? = null
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

    private var mapType = OsmMapWrapper.MAP_TYPE_DEFAULT

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

    /**
     * How much of the way to the last fix the marker moves each frame.
     *
     * The same share the 3D view moves its model by, so the two are drawn at
     * one pace: switching between them should not feel like changing gear.
     */
    private val MARKER_EASE = 0.18f
    private var followMode = true
    private var chaseMode = false
    private var hasGPSFix = false
    private var replayFileString: String? = null

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
    private var lastPhoneBattery = 0
    private var lastTraveledDistance = 0.0
    private var lastCellVoltage = 0.0f

    private var fullscreenWindow = false

    private var gotHeading = false;

    private var logPlayer : LogPlayer? = null;

    private var requestWritePermissionSequence = RequestWritePermissionSequenceType.NONE;

    private var lastFileDialogSelectionIndex = -1;
    private var lastFileDialogSelection = "";

    private var lastSelectedDataPooler = "";
    private var lastSelectedBluetoothDeviceAddress = "";
    private var lastSelectedBLEDeviceAddress = "";

    private var reconnectionStartTime = 0L;
    private var lastConnectionType = CONNTYPE_NONE;
    private var lastBluetoothDevice: BluetoothDevice? = null;
    private var reconnectOnFailure = false;

    // what a network reconnect needs to repeat; there is no device object to
    // hold on to as there is for Bluetooth
    private var lastNetworkHost = ""
    private var lastNetworkPort = 0
    private var lastNetworkMode = 0

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(p0: ComponentName?) {
            onDisconnected()
        }

        override fun onServiceConnected(p0: ComponentName?, p1: IBinder?) {
            dataService = (p1 as DataService.DataBinder).getService()
            dataService?.setDataListener(this@MapsActivity)
            dataService?.let {
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
        if (mapType < OsmMapWrapper.MAP_TYPE_DEFAULT ||
            mapType > OsmMapWrapper.MAP_TYPE_SATELLITE_HYBRID) {
            mapType = OsmMapWrapper.MAP_TYPE_DEFAULT
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
        if (savedInstanceState?.getBoolean("chase_mode", false) == true) setChaseMode(true)
        mapTypeButton = findViewById(R.id.map_type_button)
        northUpButton = findViewById(R.id.north_up_button)
        compassHeading = findViewById(R.id.compass_heading)
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
            // from behind the model, this is a step back to plain tracking
            // rather than a step to nothing
            centreOnModel()
            if (chaseMode) {
                setChaseMode(false)
                setFollowMode(true)
            } else {
                setFollowMode(!followMode)
            }
            terrain3D?.setFollowing(followMode)
            if (followMode) {
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
                setFollowMode(false)
                it.setFollowing(false)
                if (!it.goToMyLocation()) {
                    Toast.makeText(this, "Phone location not available", Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            val pos = map?.getMyLocation()
            if (pos != null) {
                setFollowMode(false)
                map?.moveCamera(pos, LOCATE_ZOOM)
            } else {
                Toast.makeText(this, "Phone location not available", Toast.LENGTH_SHORT).show()
            }
        }

        findQuadButton.setOnClickListener {
            terrain3D?.let { view ->
                val live = crazydude.com.telemetry.gl.LiveFlightPath.latest()
                if (live != null) {
                    setFollowMode(false)
                    view.lookAt(live.lat, live.lon, live.altitudeMsl)
                } else {
                    lastKnownGPS?.let { view.lookAt(it.lat, it.lon, null) }
                }
            }
            showFindMyQuad()
        }

        // What is left is what needs a log already open. Renaming, deleting and
        // copying the model location all moved to where they belong: the first
        // two to the log picker, which is the only place that lists logs, and
        // the last to the Find my quad button.
        menuButton.setOnClickListener {
            val option4 = "Export GPX file...";
            val option5 = "Export KML file...";
            val option6 = "Set playback duration..."
            val option7 = "Altitude profile...";
            val option8 = if (preferenceManager.isLiveShownInReplay()) {
                "Hide where I am now"
            } else {
                "Show where I am now"
            }

            val options = arrayOf(option7, option4, option5, option6, option8)

            this.showDialog( AlertDialog.Builder(this)
            .setTitle("Select an action")
            .setItems(options) { dialog: DialogInterface, which: Int ->
                val selectedOption = options[which]
                when (selectedOption) {
                    option4 -> {
                        showExportGPXDialog()
                    }
                    option5 -> {
                        showExportKMLDialog1()
                    }
                    option6 -> {
                        showSetPlaybackDurationDialog()
                    }
                    option7 -> {
                        showAltitudeProfile()
                    }
                    option8 -> {
                        preferenceManager.setLiveShownInReplay(
                            !preferenceManager.isLiveShownInReplay()
                        )
                        showMyLocation()
                        if (!showLiveArrow()) terrain3D?.hideMyLocation()
                        tellViewsWhereIAm()
                    }
                }
                dialog.dismiss()
            }.create())
        }

        playButton.setOnClickListener {
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
        flightPlanLines.forEach { it.remove() }
        flightPlanLines.clear()
        homeLine?.remove()
        homeLine = null
        marker?.remove();
        marker = null;
        airplaneMarkers.values.forEach { it.remove() }
        airplaneMarkers.clear()

        if (mapType == OsmMapWrapper.MAP_TYPE_DEFAULT) {
            initOSMMap(TileSourceFactory.DEFAULT_TILE_SOURCE)
        } else if (mapType == OsmMapWrapper.MAP_TYPE_SATELLITE) {
            initOSMMap(ESRI_SATELLITE_TILE_SOURCE)
        } else if (mapType == OsmMapWrapper.MAP_TYPE_SATELLITE_HYBRID) {
            initOSMMap(ESRI_SATELLITE_TILE_SOURCE, listOf(ESRI_TRANSPORTATION_OVERLAY_TILE_SOURCE, ESRI_BOUNDARIES_PLACES_OVERLAY_TILE_SOURCE))
        } else {
            initOSMMap(TileSourceFactory.OpenTopo)
        }
    }

    private fun initOSMMap(tileSource: OnlineTileSourceBase, overlayTileSources: List<OnlineTileSourceBase> = emptyList()) {
        val mapView = org.osmdroid.views.MapView(this)
        mapHolder.addView(mapView)
        val osmMap = OsmMapWrapper(applicationContext, mapView, tileSource, { initHeadingLine() }, overlayTileSources)
        map = osmMap
        map?.setOnCameraMoveStartedListener {
            // No gesture gives up following or the chase, here as in three
            // dimensions. What the hand does is kept — the map goes on keeping
            // up with the model from wherever it has been put, and goes on
            // turning with its heading from whatever angle it has been left at.
            // The buttons put it back to the middle.
            leanOutOfFollowing()
        }
        osmMap.setOnOrientationChangedListener { orientation ->
            updateCompassHeading(orientation)
        }
        polyLine = map?.addPolyline(preferenceManager.getRouteColor())
        // Only a flight that is still going, or one being replayed. The service
        // outlives this screen and keeps the points of whatever it last heard,
        // so an unconnected map opened afterwards drew the last flight as
        // though it were happening — which the 3D ground never did.
        redrawFlightLine()
        homeLine = map?.addPolyline(2f, preferenceManager.getHomeLineColor())
        drawFlightPlans()
        showMyLocation()
        // A map is built looking at the whole world, and it is a fix arriving
        // that puts the model on screen. Where the model already is — coming
        // back from the 3D view, or a replay standing paused — there may be no
        // fix for a while, and there was nothing to see until there was one.
        if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) {
            tryCreateMarker()
            marker?.let {
                it.position = shownPosition()
                // and pointing the way it was pointing. A marker is made facing
                // north and only ever turned by the frame loop, which needs new
                // data — so a map built after the link dropped showed the model
                // facing north wherever it had really been going.
                it.rotation = if (shownMarkerHeading.isNaN()) lastHeading else shownMarkerHeading
            }
            // Straight at it, and not still leaning wherever the last map was
            // dragged to. The lean outlives the map it was made on, so a map
            // built after a change of view put the model off to one side — and
            // turning the map to a heading swung it round the empty middle
            // instead of round the model.
            centreOnModel()
            // a map that has just been built knows nothing until it is told
            tellMapWhereIAm()
            map?.moveCamera(shownPosition(), LOCATE_ZOOM)
            updateHeading()
            updateHomeLine()
            showOperator()
        } else {
            // Nothing flown yet: open on where this phone is, at the same
            // height the locate button uses. A map is built looking at the
            // whole world from zoom four, which is no use to anybody.
            myLastKnownPlace()?.let { map?.moveCamera(it, LOCATE_ZOOM) }
        }
        // and the traffic, which is otherwise gone until the next poll comes
        // round — half a minute of empty sky after every switch of view
        if (lastAirplanes.isNotEmpty()) onAirplanesUpdated(lastAirplanes)
    }

    /**
     * The whole flight onto a line that has just been made.
     *
     * Committing matters as much as handing over: handing points to a line only
     * stages them, and until something else committed, a map built during a
     * paused replay had the flight staged and invisible.
     */
    private fun redrawFlightLine() {
        val flown = crazydude.com.telemetry.gl.LiveFlightPath.snapshot()
        if (flown.isEmpty()) return
        polyLine?.submitPoints(flown.map { Position(it.lat, it.lon) })
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
        val total = seekbar.max
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
        if (!isInReplayMode()) return
        val track = operatorTrack
        val now = replayTimeNow()
        if (track == null || now == null) {
            // nothing recorded of where anybody stood, so nothing orange drawn
            map?.showRecordedLocation(null, 0f, 0f)
            terrain3D?.hideLoggedLocation()
            updateHomeLine()
            return
        }
        val where = track.at(now.time)
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
        // the orange arrow belongs to a replay and goes with it
        map?.showRecordedLocation(null, 0f, 0f)
        terrain3D?.hideLoggedLocation()
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
            // toggle in the replay's own menu rather than simply off.
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
     * Where the phone is, for the line home and anything else drawn from it: as
     * recorded while replaying, and as it is now otherwise.
     */
    private fun wherePhoneIs(): Position? =
        if (isInReplayMode()) recordedMe else myLastKnownPlace()

    private fun updateHomeLine() {
        val line = homeLine ?: return
        line.color = preferenceManager.getHomeLineColor()
        if (!preferenceManager.isHomeLineEnabled()) {
            line.clear()
            map?.invalidate()
            return
        }
        // from where the model is drawn, not where the fix was, so the line
        // stays joined to it as it moves
        val drone = if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) shownPosition() else return
        // where this phone is, from the system if the map's own overlay has
        // not found it yet: a newly built map takes a while to get its first
        // fix, and the line home waited all of it. Replaying, it is where the
        // phone was then — there is no line to draw without that.
        val phone = wherePhoneIs() ?: run {
            line.clear()
            map?.invalidate()
            return
        }
        if (line.size == 2) {
            line.setPoint(0, drone)
            line.setPoint(1, phone)
        } else {
            line.clear()
            line.addPoints(listOf(drone, phone))
        }
        map?.invalidate()
    }

    private fun drawFlightPlans() {
        flightPlanLines.forEach { it.remove() }
        flightPlanLines.clear()
        if (!preferenceManager.isFlightPlansEnabled()) return
        val plans = FlightPlanManager(this).getPlans()
        for (plan in plans) {
            if (!plan.visible || plan.waypoints.size < 2) continue
            val line = map?.addPolyline(4f, plan.color, *plan.waypoints.toTypedArray())
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
                clipboardManager.primaryClip =
                    ClipData.newPlainText("Location", plusCode + " (" + coords + ")")
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
        GhstProtocol.forgetLaunchAltitude()
        detectedCells = 0
        highestPackVoltage = 0f
        cellsAsked = false
        cellsAnswered = false
        forgetFlight()
        startFlightIn3D()
        file?.also {
            val progressDialog = ProgressDialog(this)
            progressDialog.setCancelable(false)
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            progressDialog.max = 100

            progressDialog.getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            );
            progressDialog.show();
            if (!this.fullscreenWindow) {
                progressDialog.getWindow().decorView.systemUiVisibility = 0
            } else {
                progressDialog.getWindow().decorView.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE)
            }
            progressDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

            switchToReplayMode()

            replayFileString = it.name
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
                    seekBar.max = size
                    seekBar.visibility = View.VISIBLE
                    playButton.visibility = View.VISIBLE
                    seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekbar: SeekBar,
                            position: Int,
                            fromUser: Boolean
                        ) {
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
                        }

                        override fun onStopTrackingTouch(p0: SeekBar?) {

                        }
                    })

                    //rewind to first gps data to zoom on plane
                    lastGPS = Position(0.0, 0.0);
                    gotHeading = false;
                    for (i in 0..seekBar.max - 1) {
                        logPlayer?.seek(i)
                        if (lastGPS.lat != 0.0 && lastGPS.lon != 0.0 && marker != null && gotHeading) {
                            break;
                        }
                    }

                    logPlayer?.seek(0);
                }

                override fun onPlaybackPositionChange(prevPosition: Int, nextPosition: Int) {
                    runOnUiThread {
                        if ( (logPlayer?.currentPosition ?:0) == prevPosition ) {
                            seekbar.progress = nextPosition
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
                    return preferenceManager.getPlaybackDuration()
                }

                override fun getPlaybackAutostart() : Boolean
                {
                    return preferenceManager.getPlaybackAutostart()
                }

                override fun onProtocolDetected(protocolName: String) {
                    runOnUiThread {
                        Toast.makeText(context, "Protocol: $protocolName", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) {
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
                    PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_DEVICE), 0)
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
        showTime()
        clock_text.removeCallbacks(clockTicker)
        clock_text.postDelayed(clockTicker, 1000)
        initHeadingLine()
        updateHomeLine()
        if (checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            // both, because indoors the satellites never come in and the 3D
            // view would be left without the arrow the map is showing
            for (provider in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    // no distance filter: a phone lying still never moves the
                    // metre that was being asked for, so after the first fix it
                    // heard nothing more and the accuracy ring stayed the size
                    // of whatever it started with
                    lm.requestLocationUpdates(provider, 1000L, 0f, phoneLocationListener)
                } catch (e: Exception) {
                    // a phone without that provider; the other one still runs
                }
            }
        }
        val sensors = getSystemService(SENSOR_SERVICE) as SensorManager?
        sensors?.let {
            it.registerListener(
                phoneCompass, it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_UI
            )
            it.registerListener(
                phoneCompass, it.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                SensorManager.SENSOR_DELAY_UI
            )
        }
        startFr24()
    }

    override fun onPause() {
        super.onPause()
        clock_text.removeCallbacks(clockTicker)
        terrain3D?.onPause()
        map?.onPause()
        this.sensorTimeoutManager.pause();
        this.logPlayer?.stop();
        stopFr24()
        updateFullscreenState()//check if user has brought system ui with swipe
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(phoneLocationListener)
        (getSystemService(SENSOR_SERVICE) as SensorManager?)?.unregisterListener(phoneCompass)
        // and the recording stops being told where the phone is, because from
        // here nobody knows. The link keeps recording in the background, and
        // every row of it would otherwise carry the last place this screen saw
        // — a phone in a pocket, walking about, written down as standing still.
        dataService?.setPhonePosition(Double.NaN, Double.NaN, Float.NaN, Float.NaN)
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
        horizonView.setPitch(0f)
        horizonView.setRoll(0f)
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
            it.connect(lastNetworkHost, lastNetworkPort, lastNetworkMode)
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
        val useTcp: Boolean,
        val port: Int,
        val useGateway: Boolean,
        /** a fixed address, where the preset knows it */
        val host: String? = null,
        /** transport, matching the order of the transport spinner */
        val mode: Int = if (useTcp) NetworkDataPoller.MODE_TCP_CLIENT else NetworkDataPoller.MODE_UDP
    )

    // The transport stays in the name because it is the thing that decides
    // whether an address is needed at all. The port does not: it lands in the
    // port field the moment the preset is picked.
    private val networkPresets = listOf(
        NetworkPreset("ExpressLRS backpack (UDP)", false, 14550, false),
        NetworkPreset("TBS Crossfire / Tracer (TCP)", true, 8888, true),
        NetworkPreset("TBS Crossfire / Tracer (UDP)", false, 8888, false),
        NetworkPreset("MAVLink router / ground station (UDP)", false, 14550, false),
        NetworkPreset("Serial to Wi-Fi bridge (TCP)", true, 23, true),
        // The one path into a Crossfire WiFi module that every firmware
        // serves: its own phone app uses MQTT, which needs a broker in the app
        // and is broken on the newest firmware, while this carries plain CRSF.
        NetworkPreset(
            "TBS Crossfire WiFi (WebSocket)", true, 80, true,
            mode = NetworkDataPoller.MODE_WEBSOCKET
        ),
        NetworkPreset("Custom", false, 14550, false)
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
            hostField.isEnabled = tcp
            hostLabel.isEnabled = tcp
            // Greying the field out on its own only raises the question "why
            // can I not type here" — so the label answers it.
            hostLabel.text = if (tcp) {
                getString(R.string.network_host)
            } else {
                getString(R.string.network_host_unused)
            }
            hostField.hint = if (tcp) {
                getString(R.string.network_host_hint)
            } else {
                getString(R.string.network_host_hint_udp)
            }
            // nothing to find on loopback: it is a single address, this device
            findButton.isEnabled = tcp && !hostField.text.toString().trim().startsWith("127.")
            hint.text = if (tcp) {
                getString(R.string.network_hint_tcp)
            } else {
                getString(R.string.network_hint_udp)
            }
        }

        fun applyPreset(index: Int) {
            val preset = networkPresets[index]
            transportSpinner.setSelection(preset.mode)
            // the port this preset was last used with, not the documented one:
            // modules do get moved off their default
            portField.setText(
                preferenceManager.getNetworkPortFor(index, preset.port).toString())
            if (preset.host != null) {
                hostField.setText(preset.host)
            } else if (preset.useGateway) {
                val gateway = binder.gatewayAddress()
                if (gateway != null) hostField.setText(gateway)
            }
            updateHostEnabled()
        }

        // restore what was used last
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
        if (savedPreset in networkPresets.indices) presetSpinner.setSelection(savedPreset)
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
                    preferenceManager.setNetworkPinWifi(
                        interfaceSpinner.selectedItemPosition == 0)
                    preferenceManager.setNetworkPreset(presetSpinner.selectedItemPosition)
                    preferenceManager.setNetworkUseTcp(useTcp)
                    preferenceManager.setNetworkMode(mode)
                    preferenceManager.setNetworkHost(host)
                    preferenceManager.setNetworkHostFor(
                        binder.ssid() ?: "", presetSpinner.selectedItemPosition, host)
                    preferenceManager.setNetworkPort(port)
                    preferenceManager.setNetworkPortFor(
                        presetSpinner.selectedItemPosition, port)

                    connectToNetwork(host, port, mode)
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
        crazydude.com.telemetry.gl.LiveFlightPath.clear()
        polyLine?.clear()
        terrain3D?.onFlightReset()
        lastTraveledDistance = 0.0
        lastGpsAltitudeMsl = Float.NaN
        lastAnyAltitude = Float.NaN
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
        headingPolyline?.clear()
        homeLine?.clear()
    }

    private fun clearCrsfSystem() {
        GhstProtocol.forgetLaunchAltitude()
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

    private fun connectToNetwork(host: String, port: Int, mode: Int) {
        clearCrsfSystem()
        // connect() clears this before the chooser opens, so every transport
        // has to set it again or it becomes silently non-reconnectable
        lastConnectionType = CONNTYPE_NET;
        lastNetworkHost = host
        lastNetworkPort = port
        lastNetworkMode = mode
        reconnectionStartTime = 0;
        reconnectOnFailure = false;

        startDataService()
        dataService?.let {
            connectButton.text = getString(R.string.connecting)
            connectButton.isEnabled = false
            it.connect(host, port, mode)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        tts = null
        ttsReady = false
        headingPolyline = null;
        polyLine = null;
        flightPlanLines.clear()
        map?.onDestroy()
        if (!isChangingConfigurations) {
            dataService?.setDataListener(null)
        }
        map = null;
        this.unregisterReceiver(this.batInfoReceiver)
        unbindService(serviceConnection)
    }

    private fun startDataService() {
        val intent = Intent(this, DataService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        startService(intent)
        bindService(intent, serviceConnection, 0)
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

    /**
     * The last height of any kind, for links that send no GPS altitude.
     *
     * What it is measured from does not matter here: whether it is sea level or
     * the launch point is worked out later, from the ground under the first
     * fix. What matters is that a flight is recorded at all, which without this
     * did not happen on a link that only reports a barometric height.
     */
    private var lastAnyAltitude = Float.NaN

    override fun onAltitudeData(altitude: Float) {
        // A GPS altitude that has stopped arriving is not an altitude. It used
        // to be believed for the rest of the flight, so a receiver that lost
        // the altitude sensor mid-air left the flight recorded as dead flat at
        // whatever height it had reached, while the model went on climbing.
        if (System.currentTimeMillis() - lastGpsAltitudeAt > 10000L) {
            lastGpsAltitudeMsl = Float.NaN
        }
        if (lastGpsAltitudeMsl.isNaN()) lastAnyAltitude = altitude
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
        lastGpsAltitudeMsl = altitude
        lastGpsAltitudeAt = System.currentTimeMillis()
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
        crazydude.com.telemetry.utils.Elevation.init(this)
        val view = AltitudeProfileView(this)
        val points = ArrayList<AltitudeProfileView.Point>(flown.size)
        // Betaflight reports height above the arming point once armed, so the
        // ground under the launch is what those heights are missing before they
        // can be drawn against terrain. Worked out the same way the 3D view
        // works it out: from the ground at the first fix.
        val lift = launchGroundLift()
        for (p in flown) {
            points.add(AltitudeProfileView.Point(p.lat, p.lon, p.altitudeMsl + lift))
        }
        view.setTrack(points)
        view.minimumHeight = (resources.displayMetrics.density * 220).toInt()

        fetchTerrainFor(flown) {
            val settled = launchGroundLift()
            val updated = ArrayList<AltitudeProfileView.Point>(flown.size)
            for (p in flown) {
                updated.add(AltitudeProfileView.Point(p.lat, p.lon, p.altitudeMsl + settled))
            }
            view.setTrack(updated)
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
    private fun launchGroundLift(): Float {
        // The same answer the 3D ground works from, from the same code. This
        // used to ask whether the first fix read within sixty metres of the
        // terrain under it — a test the 3D view documents as unsound, and which
        // disagreed with it: a flight starting high above a valley was declared
        // to be measured from the launch and drawn a few hundred metres up.
        return crazydude.com.telemetry.gl.TerrainScene.referenceOf(
            flightPath, crazydude.com.telemetry.utils.Elevation.TILE_ZOOM)?.lift ?: 0f
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
        val found = best ?: return null
        return Position(found.latitude, found.longitude)
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
        if (where == null && crazydude.com.telemetry.gl.LiveFlightPath.size() < 2) {
            if (!quiet) {
                Toast.makeText(this, "No position yet, from the model or this phone",
                    Toast.LENGTH_SHORT).show()
            }
            return
        }

        hide3DView()
        // Let go of properly rather than merely dropped: the tile threads and
        // the tile cache belong to the view, and this happens every time the
        // ground is opened.
        map?.onDestroy()
        mapHolder.removeAllViews()
        map = null
        // Taking the map view away detaches every overlay on it, and osmdroid
        // empties them as it goes — so the lines and the marker held here are
        // now hollow, and touching one throws. They belong to the map, and the
        // map has gone.
        forgetMapOverlays()

        val view = Terrain3DView(this)
        terrain3D = view
        mapHolder.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        applyTerrainSettings(view)
        view.setTraffic(lastAirplanes)
        // Facing the way it was last seen facing. A view is built with a model
        // pointing north and level, and only an arriving attitude turns it — so
        // opening this view with nothing arriving showed the model facing north
        // wherever it had really been going, exactly as the map's marker did.
        if (gotHeading) view.setModelAttitude(lastHeading, lastPitch, lastRoll)
        view.onFollowingLost = { setFollowMode(false) }
        view.onBearingChanged = { updateCompassHeading(it) }
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
        view.setFollowing(followMode)
        if (chaseMode) view.setChasing(true)
        // standing where the phone is standing and pointing where it points,
        // since the readers that know both are on this screen and have been
        // running all along
        tellViewsWhereIAm()
        // and where the operator was, if this is opening over a replay
        showOperator()
        updateCompassHeading(view.bearing())
        setFollowMode(followMode)
        // Whatever flight there is. This used to be withheld unless a link
        // was up or a replay running, to stop a finished flight reappearing on
        // a map built afterwards — but a flight is now thrown away where one
        // ends and another begins, so there is nothing stale left to withhold.
        // Meanwhile the map keeps showing a flight after the link drops, and
        // this view was coming up empty beside it.
        val flown = crazydude.com.telemetry.gl.LiveFlightPath.snapshot()
        view.start(
            flown,
            where?.lat ?: Double.NaN, where?.lon ?: Double.NaN,
            if (showLiveArrow()) mine?.lat ?: Double.NaN else Double.NaN,
            if (showLiveArrow()) mine?.lon ?: Double.NaN else Double.NaN,
            if (showLiveArrow()) phoneAccuracy else Float.NaN
        )
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
    private fun startFlightIn3D() {
        if (terrain3D == null) return
        hide3DView()
        show3DView(quiet = true)
    }

    private fun forgetMapOverlays() {
        polyLine = null
        homeLine = null
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
        view.setTrackColor(preferenceManager.getRouteColor())
        view.setModelColor(preferenceManager.getPlaneColor())
        view.setOverlaySettings(
            preferenceManager.isHomeLineEnabled(), preferenceManager.getHomeLineColor(),
            preferenceManager.isHeadingLineEnabled(), preferenceManager.getHeadLineColor()
        )
        val plans = if (preferenceManager.isFlightPlansEnabled()) {
            FlightPlanManager(this).getPlans()
                .filter { it.visible && it.waypoints.size >= 2 }
                .map { Pair(it.waypoints, it.color) }
        } else {
            emptyList()
        }
        view.setFlightPlans(plans)
    }

    private fun hide3DView() {
        terrain3D?.let {
            it.onPause()
            it.release()
            mapHolder.removeView(it)
        }
        terrain3D = null
    }

    /** Terrain over the flight's area, off the UI thread; [onReady] on it. */
    private fun fetchTerrainFor(
        path: List<crazydude.com.telemetry.gl.TerrainScene.TrackPoint>,
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
            crazydude.com.telemetry.utils.Elevation.prefetch(
                minLat, minLon, maxLat, maxLon,
                crazydude.com.telemetry.utils.Elevation.TILE_ZOOM,
                { _, _ -> runOnUiThread { if (!isFinishing) onReady() } },
                { _, _ -> runOnUiThread { if (!isFinishing) onReady() } }
            )
        }
    }

    private fun rememberForProfile(latitude: Double, longitude: Double) {
        rememberForProfile(latitude, longitude, heightNow())
    }

    private fun heightNow(): Float =
        if (!lastGpsAltitudeMsl.isNaN()) lastGpsAltitudeMsl else lastAnyAltitude

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
        crazydude.com.telemetry.gl.LiveFlightPath.add(latitude, longitude, height)
        if (!height.isNaN()) lastRememberedHeight = height
    }

    /** The height the last remembered point was given, to climb from. */
    private var lastRememberedHeight = Float.NaN

    /** The marker for whatever is being flown, quad or fixed wing. */
    private fun modelIcon(): Int {
        return if (preferenceManager.getModelType() == "plane") {
            R.drawable.ic_fixedwing
        } else {
            R.drawable.ic_plane
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
            marker?.rotation = lastHeading;
            map?.moveCamera(lastGPS, LOCATE_ZOOM)
        }
    }

    private fun createHeadingPolyline(): MapLine? {
        return map?.addPolyline(3f, preferenceManager.getHeadLineColor(), lastGPS, lastGPS)
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
        val lower = name.toLowerCase(java.util.Locale.US)
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
            mapType - OsmMapWrapper.MAP_TYPE_DEFAULT
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
            hide3DView()
            mapHolder.removeAllViews()
            map = null
            forgetMapOverlays()
            mapType = item + OsmMapWrapper.MAP_TYPE_DEFAULT
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

    private fun updateHeading() {
        applyHeadingUp()
        if (lastGPS.lat != 0.0 && lastGPS.lon != 0.0) {
            val from = shownPosition()
            // and pointing the way the marker is pointing: drawn to the last
            // heading while the marker eased towards it, the line swung ahead
            // and waited for it
            val towards = if (shownMarkerHeading.isNaN()) lastHeading else shownMarkerHeading
            headingPolyline?.let { headingLine ->
                val (offsetLat, offsetLon) = GeoUtils.computeOffset(from.lat, from.lon, 1000.0, towards.toDouble())
                val ahead = Position(offsetLat, offsetLon)
                // built when it is not there rather than written into: it is
                // emptied when a replay resets, and setting a point of an empty
                // line throws — which the home line beside it has always
                // guarded against and this one never did.
                if (headingLine.size == 2) {
                    headingLine.setPoint(0, from)
                    headingLine.setPoint(1, ahead)
                } else {
                    headingLine.clear()
                    headingLine.addPoints(listOf(from, ahead))
                }
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
            switchToIdleState()

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
        stopFr24()
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
            switchToIdleState()
            closeReplay()
        }
        this.sensorTimeoutManager.disableTimeouts()
        this.tlmRate.setAlpha(0.5f);
        lastGPS = Position(0.0, 0.0);
        hasGPSFix = false;
    }

    private fun switchToIdleState() {
        this.logPlayer?.stop();
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
    private fun closeReplay() {
        // first, so that everything asking whether this is a replay is answered
        // truthfully by the time it is asked — the live arrow is switched on by
        // one of those answers, and was being switched on while the replay was
        // still officially open, which left it off
        replayFileString = null
        forgetOperator()
        // The whole of it: a recording that has been closed leaves nothing
        // behind, neither the model nor the flight it was playing back, and in
        // the 3D view that includes the surface hanging under the flight.
        forgetFlight()
        marker?.remove()
        marker = null
        headingPolyline?.remove()
        headingPolyline = null
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
            logPlayer?.stop()
            logPlayer = null
            seekBar.visibility = View.GONE
            playButton.visibility = View.GONE
            closeReplay()
        }
        replayButton.visibility = View.GONE
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
            commitRouteLinePoints()
        }
    }

    override fun onGPSData(list: List<Position>, addToEnd: Boolean) {
        this.sensorTimeoutManager.onGPSData(list, addToEnd);
        runOnUiThread {
            if (!addToEnd) {
                // rewound: the path is about to be replayed, so drop what it held
                forgetFlight()
            }
            if (hasGPSFix && list.isNotEmpty()) {
                //add all points except last one
                //last one will be fired in onGPSData()
                if ( list.size>=2) {
                    polyLine?.submitPoints(list.dropLast(1))
                    commitRouteLinePoints()
                    // The 3D path too, or it is left with one point per batch
                    // — and a replay hands over whole batches at a time, so it
                    // came out as a few straight legs across the flight.
                    //
                    // A batch carries one height: the log's altitude is decoded
                    // between batches, not within them. Giving every point of a
                    // batch that one height turns a climb into a staircase, so
                    // the height is walked across the batch from the last one
                    // remembered to this one. That is what a climb between two
                    // readings actually looked like.
                    val to = heightNow()
                    val from = if (lastRememberedHeight.isNaN()) to else lastRememberedHeight
                    for (i in 0..list.size - 2) {
                        val part = (i + 1).toFloat() / list.size
                        // nothing to walk across where no height was ever read:
                        // the fixes are kept as they came, without one
                        val walked = if (to.isNaN()) Float.NaN else from + (to - from) * part
                        rememberForProfile(list[i].lat, list[i].lon, walked)
                    }
                }

                for (i in 0..list.size - 2) {
                    if (this.lastGPS.lat != 0.0 && this.lastGPS.lon != 0.0) {
                        this.lastTraveledDistance += GeoUtils.computeDistanceBetween(
                            this.lastGPS.lat, this.lastGPS.lon, list[i].lat, list[i].lon
                        )
                    }
                    lastGPS = Position(list[i].lat, list[i].lon)
                }

                onGPSData(list[list.size - 1].lat, list[list.size - 1].lon)
            }
        }
    }

    override fun onGPSData(latitude: Double, longitude: Double) {
        this.sensorTimeoutManager.onGPSData(latitude, longitude);
        runOnUiThread {
            // on every fix, not only a moved one: a quad on the ground repeats
            // its position, and the retry below would never come round again
            tryCreateMarker()

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
                        lastCourseFrom = Position(latitude, longitude)
                    }
                    rememberForProfile(latitude, longitude)
                    terrain3D?.onNewPoint()
                }
                // the marker walks to it over the next few frames, and takes the
                // lines and the camera with it
                keepSmoothing()
                if (hasGPSFix) {
                    polyLine?.submitPoints(listOf(lastGPS))
                    this.lastTraveledDistance += d
                    this.traveled_distance.text =
                        this.formatDistance(this.lastTraveledDistance.toFloat());
                }

                if (!followMode) {
                    this.map?.invalidate()
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

    private fun showDialog(dialog: AlertDialog) {
        dialog.getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        );
        dialog.show();
        if (!this.fullscreenWindow) {
            dialog.getWindow().decorView.systemUiVisibility = 0
        } else {
            dialog.getWindow().decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE)
        }
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
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
                this.tlm_rate.text = "${rate} b/s"
            } else {
                this.tlm_rate.text = "${"%.1f".format(rate / 1000f)} kb/s"
            }
        }
    }

    fun setFollowMode(mode: Boolean) {
        followMode = mode;
        // Lit for plain tracking only. Riding behind the model tracks it too,
        // but that is the other button's business: one of the two is on.
        this.followButton.imageAlpha = if (mode && !chaseMode) 255 else 128
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
        terrain3D?.setChasing(on)
        if (on) {
            setFollowMode(true)
            terrain3D?.setFollowing(true)
            applyHeadingUp()
        } else {
            // Left where the chase left it, in both views. The north-up
            // button is the way back to north, and it is one tap; snapping the
            // map round on its own was a second, unasked-for movement at the
            // exact moment the user had asked for something else.
            setFollowMode(followMode)
        }
    }

    /** How far the map has been dragged and turned away from the model. */
    private var mapLeanLat = 0.0
    private var mapLeanLon = 0.0
    private var mapLeanTurn = 0f

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
     * move. The 3D view has eased the model between fixes for a while; this is
     * the same thing for the map, and the line home and the line ahead are
     * drawn from the eased position so they stay attached to it.
     *
     * The recorded track still gets every fix as it arrives, unsmoothed. This
     * is about what the eye sees, not about what is kept.
     */
    private var shownLat = Double.NaN
    private var shownLon = Double.NaN
    private var shownMarkerHeading = Float.NaN
    private var smoothingMarker = false

    /** A step towards the last fix, at each frame the screen draws. */
    private val markerStep = object : Runnable {
        override fun run() {
            smoothingMarker = false
            val map = map ?: return
            if (lastGPS.lat == 0.0 && lastGPS.lon == 0.0) return
            var moving = false

            if (shownLat.isNaN()) {
                shownLat = lastGPS.lat
                shownLon = lastGPS.lon
            } else {
                val dLat = lastGPS.lat - shownLat
                val dLon = lastGPS.lon - shownLon
                if (Math.abs(dLat) > 1e-8 || Math.abs(dLon) > 1e-8) {
                    shownLat += dLat * MARKER_EASE
                    shownLon += dLon * MARKER_EASE
                    moving = true
                }
            }

            if (shownMarkerHeading.isNaN()) {
                shownMarkerHeading = lastHeading
            } else {
                val turn = ((lastHeading - shownMarkerHeading) % 360f + 540f) % 360f - 180f
                if (Math.abs(turn) > 0.05f) {
                    shownMarkerHeading =
                        ((shownMarkerHeading + turn * MARKER_EASE) % 360f + 360f) % 360f
                    moving = true
                }
            }

            val where = Position(shownLat, shownLon)
            marker?.let {
                it.position = where
                it.rotation = shownMarkerHeading
            }
            updateHeading()
            updateHomeLine()
            if (followMode && map.initialized()) {
                map.moveCamera(Position(shownLat + mapLeanLat, shownLon + mapLeanLon))
            } else {
                map.invalidate()
            }
            if (moving) keepSmoothing()
        }
    }

    private fun keepSmoothing() {
        if (smoothingMarker) return
        smoothingMarker = true
        mapHolder.postOnAnimation(markerStep)
    }

    /** Where the model is being drawn, for anything that has to sit with it. */
    private fun shownPosition(): Position =
        if (shownLat.isNaN()) lastGPS else Position(shownLat, shownLon)

    private fun leanOutOfFollowing() {
        val centre = map?.getCentre() ?: return
        if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) {
            mapLeanLat = centre.lat - lastGPS.lat
            mapLeanLon = centre.lon - lastGPS.lon
        }
        if (chaseMode) {
            val wanted = -lastHeading
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
        if (!chaseMode || terrain3D != null) return
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


    fun showSetPlaybackDurationDialog() {
        val options = resources.getStringArray(R.array.playback_durations)
        val options_values = resources.getStringArray(R.array.playback_durations_values)

        this.showDialog( AlertDialog.Builder(this)
        .setTitle("Set playback duration:")
        .setItems(options) { dialog: DialogInterface, which: Int ->
            val v = options_values[which].toInt();
            preferenceManager.setPlaybackDuration(v)
            if ( this.logPlayer?.isPlaying() ?: false ) {
                this.logPlayer?.stop();
                this.logPlayer?.startPlayback();
            }
            Toast.makeText(this, "Duration changed", Toast.LENGTH_SHORT).show()
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
        if (isInReplayMode()) return
        if (preferenceManager.isFr24Enabled()) {
            fr24Manager = Fr24Manager(preferenceManager, this)
            fr24Manager?.start { myLastKnownPlace() }
        }
    }

    private fun stopFr24() {
        fr24Manager?.stop()
        fr24Manager = null
        airplaneMarkers.values.forEach { it.remove() }
        airplaneMarkers.clear()
        // The ground view's posts and the list they are rebuilt from. Without
        // this a replay opened from the 3D view kept this afternoon's airliners
        // standing over last month's flight — the map had cleared its own, and
        // a view built later put them back from the list.
        lastAirplanes = emptyList()
        terrain3D?.setTraffic(emptyList())
        map?.invalidate()
    }

    private var lastAirplanes: List<Fr24Manager.AirplaneInfo> = emptyList()

    override fun onAirplanesUpdated(airplanes: List<Fr24Manager.AirplaneInfo>) {
        lastAirplanes = airplanes
        runOnUiThread { terrain3D?.setTraffic(airplanes) }
        val currentIds = airplanes.map { it.flightId }.toSet()

        // Remove stale markers
        val staleIds = airplaneMarkers.keys.filter { it !in currentIds }
        staleIds.forEach { id ->
            airplaneMarkers.remove(id)?.remove()
        }

        // Update or create markers
        for (airplane in airplanes) {
            val title = airplane.displayName
            val snippet = buildString {
                if (airplane.aircraftType.isNotEmpty()) append(airplane.aircraftType)
                if (airplane.registration.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append(airplane.registration)
                }
                append("\nAlt: ${airplane.altMeters}m | Spd: ${airplane.speedKmh}km/h")
                val route = airplane.route
                if (route.isNotEmpty()) append("\n$route")
            }

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
        map?.invalidate()
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
