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
import java.io.File
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
            "3D terrain"
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
    private val phoneLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // kept for the 3D view, which draws the same accuracy circle the map does
            phoneAccuracy = if (location.hasAccuracy()) location.accuracy else 0f
            runOnUiThread {
                updateHomeLine()
                terrain3D?.setMyPosition(location.latitude, location.longitude, phoneAccuracy)
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
    private var lastKnownGPSAt: Long = 0L
    private var lastHeading = 0f
    private var followMode = true
    private var hasGPSFix = false
    private var replayFileString: String? = null
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
                    polyLine?.submitPoints(it.points)
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

        mapType = preferenceManager.getMapType()
        followMode = savedInstanceState?.getBoolean("follow_mode", true) ?: true
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
            setFollowMode(!followMode);
            terrain3D?.setFollowing(followMode)
            if (followMode) {
                marker?.let {
                    if (map?.initialized() ?: false) {
                        map?.moveCamera(it.position)
                    }
                }
            }
        }

        mapTypeButton.setOnClickListener {
            showMapTypeSelectorDialog()
        }

        northUpButton.setOnClickListener {
            map?.resetMapOrientation()
            terrain3D?.faceNorth()
        }

        myLocationButton.setOnClickListener {
            terrain3D?.let {
                setFollowMode(false)
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

            val options = arrayOf(option7, option4, option5, option6)

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
            setFollowMode(false);
        }
        osmMap.setOnOrientationChangedListener { orientation ->
            updateCompassHeading(orientation)
        }
        polyLine = map?.addPolyline(preferenceManager.getRouteColor())
        val p = dataService?.points;
        if (p != null) {
            polyLine?.submitPoints(p)
        }
        homeLine = map?.addPolyline(2f, preferenceManager.getHomeLineColor())
        drawFlightPlans()
        showMyLocation()
    }

    private fun updateCompassHeading(orientation: Float) {
        val heading = (((-orientation % 360f) + 360f) % 360f).roundToInt() % 360
        compassHeading.text = "↑ %03d°".format(heading)
        compassHeading.visibility = View.VISIBLE
    }

    private fun showMyLocation() {
        if (checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map?.isMyLocationEnabled = true
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

    private fun updateHomeLine() {
        val line = homeLine ?: return
        line.color = preferenceManager.getHomeLineColor()
        if (!preferenceManager.isHomeLineEnabled()) {
            line.clear()
            map?.invalidate()
            return
        }
        val drone = if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) lastGPS else return
        val phone = map?.getMyLocation() ?: return
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
        crazydude.com.telemetry.gl.LiveFlightPath.clear()
        lastGpsAltitudeMsl = Float.NaN
        lastAnyAltitude = Float.NaN
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
        terrain3D?.onResume()
        this.sensorTimeoutManager.resume();
        updateWindowFullscreenDecoration()
        updateScreenOrientation()
        // reapplies the colours and the heading line, which is how a change made
        // in the settings reaches the map; it draws the flight plans too
        initHeadingLine()
        updateHomeLine()
        if (checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, phoneLocationListener)
        }
        startFr24()
    }

    override fun onPause() {
        super.onPause()
        terrain3D?.onPause()
        map?.onPause()
        this.sensorTimeoutManager.pause();
        this.logPlayer?.stop();
        stopFr24()
        updateFullscreenState()//check if user has brought system ui with swipe
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(phoneLocationListener)
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
    private fun clearCrsfSystem() {
        GhstProtocol.forgetLaunchAltitude()
        detectedCells = 0
        highestPackVoltage = 0f
        cellsAsked = false
        cellsAnswered = false
        crazydude.com.telemetry.gl.LiveFlightPath.clear()
        lastGpsAltitudeMsl = Float.NaN
        lastAnyAltitude = Float.NaN
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
        if (lastGpsAltitudeMsl.isNaN()) lastAnyAltitude = altitude
        this.sensorTimeoutManager.onAltitudeData(altitude);
        runOnUiThread {
            this.altitude.text = this.formatHeight(altitude);
        }
    }

    override fun onGPSAltitudeData(altitude: Float) {
        this.sensorTimeoutManager.onGPSAltitudeData(altitude);
        lastGpsAltitudeMsl = altitude
        runOnUiThread {
            this.altitude_msl.text = this.formatHeight(altitude);
        }
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
        if (flightPath.size < 2) {
            Toast.makeText(this, "No flight with position and altitude yet", Toast.LENGTH_SHORT).show()
            return
        }
        crazydude.com.telemetry.utils.Elevation.init(this)
        val view = AltitudeProfileView(this)
        val points = ArrayList<AltitudeProfileView.Point>(flightPath.size)
        // Betaflight reports height above the arming point once armed, so the
        // ground under the launch is what those heights are missing before they
        // can be drawn against terrain. Worked out the same way the 3D view
        // works it out: from the ground at the first fix.
        val lift = launchGroundLift()
        for (p in flightPath) {
            points.add(AltitudeProfileView.Point(p.lat, p.lon, p.altitudeMsl + lift))
        }
        view.setTrack(points)
        view.minimumHeight = (resources.displayMetrics.density * 220).toInt()

        fetchTerrainFor(flightPath) {
            val settled = launchGroundLift()
            val updated = ArrayList<AltitudeProfileView.Point>(flightPath.size)
            for (p in flightPath) {
                updated.add(AltitudeProfileView.Point(p.lat, p.lon, p.altitudeMsl + settled))
            }
            view.setTrack(updated)
            view.terrainUpdated()
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
        for (p in flightPath) {
            val ground = crazydude.com.telemetry.utils.Elevation.elevationAt(p.lat, p.lon)
                ?: continue
            return if (Math.abs(p.altitudeMsl - ground) > 60f) ground else 0f
        }
        return 0f
    }

    private fun show3DView() {
        val mine = map?.getMyLocation()
        // No flight yet is no reason to refuse: the ground is there either way.
        // Anywhere we know about will do — the model, where it was last seen,
        // or here.
        val where = when {
            lastGPS.lat != 0.0 || lastGPS.lon != 0.0 -> lastGPS
            lastKnownGPS != null -> lastKnownGPS
            else -> mine
        }
        if (where == null && crazydude.com.telemetry.gl.LiveFlightPath.size() < 2) {
            Toast.makeText(this, "No position yet, from the model or this phone",
                Toast.LENGTH_SHORT).show()
            return
        }

        hide3DView()
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
        view.setModelShape(preferenceManager.getModelType())
        view.setTrackColor(preferenceManager.getRouteColor())
        // the same overlays the map carries, from the same settings
        view.setOverlaySettings(
            preferenceManager.isHomeLineEnabled(), preferenceManager.getHomeLineColor(),
            preferenceManager.isHeadingLineEnabled(), preferenceManager.getHeadLineColor()
        )
        if (preferenceManager.isFlightPlansEnabled()) {
            val plans = FlightPlanManager(this).getPlans()
                .filter { it.visible && it.waypoints.size >= 2 }
                .map { Pair(it.waypoints, it.color) }
            view.setFlightPlans(plans)
        }
        view.setTraffic(lastAirplanes)
        view.onFollowingLost = { setFollowMode(false) }
        setFollowMode(true)
        view.start(
            crazydude.com.telemetry.gl.LiveFlightPath.snapshot(),
            where?.lat ?: Double.NaN, where?.lon ?: Double.NaN,
            mine?.lat ?: Double.NaN, mine?.lon ?: Double.NaN, phoneAccuracy
        )
    }

    private fun forgetMapOverlays() {
        polyLine = null
        homeLine = null
        headingPolyline = null
        marker = null
        flightPlanLines.clear()
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
        val height = if (!lastGpsAltitudeMsl.isNaN()) lastGpsAltitudeMsl else lastAnyAltitude
        if (height.isNaN()) return
        if (latitude == 0.0 && longitude == 0.0) return
        crazydude.com.telemetry.gl.LiveFlightPath.add(latitude, longitude, height)
    }

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
            preferenceManager.getMapType() - OsmMapWrapper.MAP_TYPE_DEFAULT
        }

        builder.setSingleChoiceItems(
            MAP_TYPE_ITEMS,
            checkItem
        ) { dialog, item ->
            dialog.dismiss()
            // The last two are not map types but the same ground in three
            // dimensions, so they open that screen and leave the map as it was.
            if (item == ITEM_3D) {
                show3DView()
                return@setSingleChoiceItems
            }
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
            marker?.let {
                it.rotation = heading
                updateHeading()
            }
        }
    }

    private fun updateHeading() {
        if (lastGPS.lat != 0.0 && lastGPS.lon != 0.0) {
            headingPolyline?.let { headingLine ->
                headingLine.setPoint(0, lastGPS)
                val (offsetLat, offsetLon) = GeoUtils.computeOffset(lastGPS.lat, lastGPS.lon, 1000.0, lastHeading.toDouble())
                headingLine.setPoint(1, Position(offsetLat, offsetLon))
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
        setFollowMode(true);
        seekBar.setOnSeekBarChangeListener(null)
        seekBar.progress = 0
        menuButton.show()
        connectButton.visibility = View.GONE
        replayButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_close))
        replayButton.setOnClickListener {
            lastConnectionType = CONNTYPE_NONE; //reset last connection type to skip reconnection
            switchToIdleState()
            replayFileString = null
        }
        this.sensorTimeoutManager.disableTimeouts()
        this.tlmRate.setAlpha(0.5f);
        lastGPS = Position(0.0, 0.0);
        hasGPSFix = false;
        marker?.remove()
        marker = null
        headingPolyline?.remove()
        headingPolyline = null
    }

    private fun switchToIdleState() {
        this.logPlayer?.stop();
        this.logPlayer = null;
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
        marker?.remove()
        marker = null
        polyLine?.clear()
        headingPolyline?.remove()
        headingPolyline = null;
        this.sensorTimeoutManager.enableTimeouts()
        this.tlmRate.setAlpha(1.0f);
        lastGPS = Position(0.0, 0.0)
    }

    private fun switchToConnectedState() {
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
        runOnUiThread {
            this.lastGPS = Position(0.0, 0.0);
            this.hasGPSFix = false;
            this.lastTraveledDistance = 0.0
            this.polyLine?.clear()
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
                crazydude.com.telemetry.gl.LiveFlightPath.clear()
                polyLine?.clear()
                this.lastTraveledDistance = 0.0;
                lastGPS = Position(0.0,0.0)
            }
            if (hasGPSFix && list.isNotEmpty()) {
                //add all points except last one
                //last one will be fired in onGPSData()
                if ( list.size>=2) {
                    polyLine?.submitPoints(list.dropLast(1))
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
                marker?.let { it.position = lastGPS }
                rememberForProfile(latitude, longitude)
                updateHomeLine()
                updateHeading()
                if (followMode) {
                    if (map?.initialized() ?: false) {
                        map?.moveCamera(lastGPS)
                    }
                }
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
        if (mode) {
            this.followButton.imageAlpha = 255
        } else {
            this.followButton.imageAlpha = 128
        }
    }

    fun commitRouteLinePoints() {
        var maxCount = preferenceManager.getMaxRoutePoints()
        if ( maxCount < 0) {
            maxCount = 10000
        }
        polyLine?.commitPoints(maxCount)
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
            replayFileString = null
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
                switchToIdleState()
                replayFileString = null
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
        if (preferenceManager.isFr24Enabled()) {
            fr24Manager = Fr24Manager(preferenceManager, this)
            fr24Manager?.start { map?.getMyLocation() }
        }
    }

    private fun stopFr24() {
        fr24Manager?.stop()
        fr24Manager = null
        airplaneMarkers.values.forEach { it.remove() }
        airplaneMarkers.clear()
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
