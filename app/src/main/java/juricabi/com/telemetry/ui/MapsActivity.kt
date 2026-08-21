package juricabi.com.telemetry.ui

import android.app.Activity
import android.app.ProgressDialog
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.ActivityInfo
import android.location.Location
import android.location.LocationManager
import android.content.pm.PackageManager
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.*
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.hoho.android.usbserial.driver.UsbSerialPort
import juricabi.com.telemetry.R
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
import juricabi.com.telemetry.protocol.pollers.LogPlayer
import juricabi.com.telemetry.service.DataService
import juricabi.com.telemetry.logger.OperatorTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.roundToInt

//class MapsActivity : AppCompatActivity(), DataDecoder.Listener {
class MapsActivity : androidx.appcompat.app.AppCompatActivity(), Fr24Manager.Listener {

    /**
     * The flight as flown: position with height above sea level, which is what
     * the profile and the 3D view need and neither the map nor the polyline
     * keeps. Bounded, so a long session cannot grow without end.
     */
    private val flightPath: List<juricabi.com.telemetry.gl.TerrainScene.TrackPoint>
        get() = juricabi.com.telemetry.gl.LiveFlightPath.snapshot()
    private val flightAltitude = juricabi.com.telemetry.gl.FlightAltitude()

    companion object {

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
        internal const val REQUEST_ENABLE_BT: Int = 0
        private const val REQUEST_LOCATION_PERMISSION: Int = 1
        private const val REQUEST_WRITE_PERMISSION: Int = 2
        private const val REQUEST_READ_PERMISSION: Int = 3
        private const val REQUEST_CAMERA_PERMISSION: Int = 4
        private const val REQUEST_RECORD_AUDIO_PERMISSION: Int = 5
        internal const val ACTION_USB_DEVICE = "action_usb_device"
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
    /** The overlays both views draw, said once — see FlightOverlays. */
    private val flightOverlays = FlightOverlays(
        map = { map }, terrain = { terrain3D },
        describeAirplane = { it.displayName to airplaneSummary(it) }
    )
    /** The traffic toast and voice, and the speech engine behind them. */
    private var trafficWarnings: TrafficWarnings? = null
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
            flightOverlays.showPhoneHeading(degrees)
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
        // With nothing flying, the sky is watched from here. A model takes
        // that over the moment it reports a fix of its own, and hands it back
        // when its flight is ended — so the warnings follow whatever there is
        // to be near, and never stop at the one moment somebody is standing
        // in a field about to launch.
        if (!haveModelPosition()) {
            fr24Manager?.watchFrom(fix.latitude, fix.longitude, model = false)
        }
        if (!showLiveArrow()) return
        flightOverlays.showPhone(
            fix.latitude, fix.longitude,
            if (fix.hasAccuracy()) fix.accuracy else Float.NaN,
            phoneHeading
        )
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
    internal lateinit var replayButton: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: FloatingActionButton
    private lateinit var followButton: FloatingActionButton
    private lateinit var chaseButton: FloatingActionButton
    private lateinit var mapTypeButton: FloatingActionButton
    private lateinit var northUpButton: FloatingActionButton
    private lateinit var myLocationButton: FloatingActionButton
    private lateinit var findQuadButton: FloatingActionButton
    // the switch in the top bar beside replay; sound, fill and turn on the
    // picture's own top-right, folding away with the half
    private lateinit var fullscreenButton: ImageView
    private lateinit var menuButton: FloatingActionButton
    private lateinit var settingsButton: ImageView
    private lateinit var topLayout: RelativeLayout
    private lateinit var bottomLayout: RelativeLayout
    private lateinit var horizonView: HorizonView
    private lateinit var rootLayout: CoordinatorLayout
    private lateinit var compassHeading: TextViewOutline
    private lateinit var loadingGrid: LoadingGrid
    private lateinit var clock_text: TextViewOutline
    private lateinit var mapHolder: FrameLayout
    // the map and its overlays together — the half arrangement resizes this,
    // and the overlays ride along instead of standing over the picture
    private lateinit var mapPane: FrameLayout

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
    internal var replayFileString: String? = null

    /**
     * Whether a loaded replay starts — the resume answer and the hold that
     * keeps playback off bare mesh while the ground is fetched, in one place.
     *
     * A log starts playing the moment it has finished decoding, and the ground
     * it happened over takes a few seconds the first time it is fetched —
     * sixty-four pictures for each tile of it. So the flight raced across bare
     * mesh while the terrain came in behind it, and by the time there was
     * anything to see, most of it had already happened. Only ever the first
     * visit to a field: after that the pictures are on the phone and the wait
     * is nothing.
     */
    private val replayHold = ReplayHold { preferenceManager.getPlaybackAutostart() }

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

    internal var logPlayer : LogPlayer? = null;

    private var requestWritePermissionSequence = RequestWritePermissionSequenceType.NONE;


    /** The log list, its two modes and its import/export, lifted to their own file. */
    private val logManager = LogManager(this)


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

    // One USB drop is a cable coming out. Two auto-reconnects in quick
    // succession are a rhythm: the radio drinking charge from the phone
    // faster than the phone can feed it, resetting the bus. Nothing on
    // screen distinguishes that from a loose plug, so it gets said once.
    private var lastUsbAutoReconnect = 0L
    private var usbPowerHintShown = false

    // what a network reconnect needs to repeat; there is no device object to
    // hold on to as there is for Bluetooth
    private var lastNetworkHost = ""
    private var lastNetworkPort = 0
    private var lastNetworkMode = 0
    private var lastNetworkHighLatency = false

    /**
     * The flight-shaping half of the decoded stream, bound to the private
     * handlers below. The readouts are not forwarded from here any more —
     * the panel hears the stream itself, beside this, through the multicast.
     */
    private val flightListener = object : DataDecoder.Companion.DefaultDecodeListener() {
        override fun onConnected() = this@MapsActivity.onConnected()
        override fun onConnectionFailed() = this@MapsActivity.onConnectionFailed()
        override fun onDisconnected() = this@MapsActivity.onDisconnected()
        override fun onDecoderRestart() = this@MapsActivity.onDecoderRestart()
        override fun onGPSData(latitude: Double, longitude: Double) =
            this@MapsActivity.onGPSData(latitude, longitude)
        override fun onGPSData(list: List<Position>, addToEnd: Boolean) =
            this@MapsActivity.onGPSData(list, addToEnd)
        override fun onGPSState(satellites: Int, gpsFix: Boolean) =
            this@MapsActivity.onGPSState(satellites, gpsFix)
        override fun onAltitudeData(altitude: Float) =
            this@MapsActivity.onAltitudeData(altitude)
        override fun onGPSAltitudeData(altitude: Float) =
            this@MapsActivity.onGPSAltitudeData(altitude)
        override fun onHomeData(latitude: Double, longitude: Double, altitudeMsl: Float) =
            this@MapsActivity.onHomeData(latitude, longitude, altitudeMsl)
        override fun onHeadingData(heading: Float) = this@MapsActivity.onHeadingData(heading)
        override fun onRollData(rollAngle: Float) = this@MapsActivity.onRollData(rollAngle)
        override fun onPitchData(pitchAngle: Float) = this@MapsActivity.onPitchData(pitchAngle)
        override fun onFlyModeData(
            armed: Boolean,
            heading: Boolean,
            firstFlightMode: DataDecoder.Companion.FlyMode?,
            secondFlightMode: DataDecoder.Companion.FlyMode?
        ) = this@MapsActivity.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
        override fun onProtocolDetected(protocolName: String) =
            this@MapsActivity.onProtocolDetected(protocolName)
        override fun commit() = this@MapsActivity.commit()
    }

    /**
     * Where every decoded reading lands, live and replay alike: the flight
     * handlers and the telemetry panel, each hearing the stream directly.
     */
    private val decodeListener =
        juricabi.com.telemetry.protocol.decoder.MulticastListener(
            { flightListener }, { telemetryPanel })

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(p0: ComponentName?) {
            onDisconnected()
        }

        override fun onServiceConnected(p0: ComponentName?, p1: IBinder?) {
            dataService = (p1 as DataService.DataBinder).getService()
            dataService?.setDataListener(decodeListener)
            dataService?.let {
                setPhoneWatch(phoneWatchWanted)
                if (it.isConnected()) {
                    // A rotation rebuilds this screen with an empty 2D track
                    // while the flight lives on in the service. The 3D view
                    // reseeds from the process-wide path; the map must too, or
                    // the trail vanishes on turning the phone — one flight,
                    // two views.
                    if (publishedVisualTrack.isEmpty()) {
                        publishedVisualTrack.addAll(
                            juricabi.com.telemetry.gl.LiveFlightPath.snapshot()
                                .map { p -> Position(p.lat, p.lon) })
                    }
                    switchToConnectedState()
                    redrawFlightLine()
                } else if (reconnectOnFailure) {
                    // A rotation during the five-second reconnect wait dropped
                    // the posted retry with the old activity; the target was
                    // restored above, so pick the loop back up.
                    tryReconnect()
                }
            }
        }
    }

    /**
     * Which protocol the link turned out to speak; "" until it has said.
     * The fact lives here because flight decisions hang on it — the LTM
     * altitude settle among them — and the panel only renders under it.
     */
    @Volatile private var detectedProtocol: String = ""

    private fun onProtocolDetected(protocolName: String) {
        // Said out loud only when the answer changes: a log says one thing as
        // it loads and its decoder may say a longer one later, and a seek
        // asks the whole question again.
        val changed = protocolName != detectedProtocol
        detectedProtocol = protocolName
        if (changed) {
            runOnUiThread {
                Toast.makeText(this, "Protocol: $protocolName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** The telemetry readouts, whole: tiles, formatting, icons, greying. */
    private lateinit var telemetryPanel: TelemetryPanel

    /** The live picture and everything that owns it — see VideoPane. */
    private lateinit var videoPane: VideoPane

    /** The connect choosers, whole - see ConnectFlow. */
    private lateinit var connectFlow: ConnectFlow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)
        // Recover a backup mid-flight if a rotation rebuilt us while its save-picker was open.
        savedInstanceState?.let { logManager.onRestoreInstanceState(it) }

        // diagnostics into the file "Copy debug info" copies, so a tester
        // away from the desk can send what logcat would have said — crashes too
        juricabi.com.telemetry.utils.DebugLog.init(applicationContext)

        preferenceManager = PreferenceManager(this)

        telemetryPanel = TelemetryPanel(
            this, preferenceManager,
            showDialog = ::showDialog,
            idle = ::isIdle,
            // a replay's battery is history; its cell question is never asked
            replaying = { logPlayer != null },
            linkProtocol = { detectedProtocol }
        )
        connectFlow = ConnectFlow(this, preferenceManager)
        videoPane = VideoPane(
            this, preferenceManager,
            showDialog = ::showDialog,
            askPermission = permissionFunnel::ask,
            cameraPermissionCode = REQUEST_CAMERA_PERMISSION,
            recordAudioPermissionCode = REQUEST_RECORD_AUDIO_PERMISSION
        )

        trafficWarnings = TrafficWarnings(this)

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
        followMode = savedInstanceState?.getBoolean("follow_mode")
            ?: preferenceManager.getCameraFollow()
        savedInstanceState?.let { telemetryPanel.restoreFrom(it) }
        // the re-announced name must not toast afresh on every turn of the phone
        detectedProtocol = savedInstanceState?.getString("detected_protocol") ?: ""
        replayFileString = savedInstanceState?.getString("replay_file_name")
        // Restored so a link that drops after a rotation still knows what to
        // reconnect to; a cold start has no bundle and keeps the idle defaults.
        savedInstanceState?.let {
            lastConnectionType = it.getInt("last_conn_type", CONNTYPE_NONE)
            lastBluetoothDevice = it.getParcelable("last_bt_device")
            lastNetworkHost = it.getString("last_net_host") ?: ""
            lastNetworkPort = it.getInt("last_net_port", 0)
            lastNetworkMode = it.getInt("last_net_mode", 0)
            lastNetworkHighLatency = it.getBoolean("last_net_hl", false)
            reconnectOnFailure = it.getBoolean("reconnect_on_failure", false)
            reconnectionStartTime = it.getLong("reconnect_start", 0L)
        }
        fullscreenWindow = preferenceManager.isFullscreenWindow()


        // The recordings, the CSVs and — on a debug build — the diagnostics
        // all live on storage, and a first flight recorded to nowhere is
        // found out too late. Asked up front therefore, but only once: a
        // refusal is not nagged at every start, and the in-context asks at
        // connect and replay still come as they always have.
        if (!preferenceManager.wasStorageAskedAtStart()
            && ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            preferenceManager.setStorageAskedAtStart()
            requestWritePermission(RequestWritePermissionSequenceType.NONE)
        }

        rootLayout = findViewById(R.id.rootLayout)
        topLayout = findViewById(R.id.top_layout)
        bottomLayout = findViewById(R.id.bottom_layout)
        connectButton = findViewById(R.id.connect_button)
        followButton = findViewById(R.id.follow_button)
        chaseButton = findViewById(R.id.chase_button)
        chaseButton.imageAlpha = 128
        // The mode the person last chose, remembered across restarts: the
        // Bundle wins on a rotation, the stored preference on a cold start.
        // Chase engages through its own setter so the buttons and both views
        // agree; follow is only a field until a flight arrives, so its button
        // is lit by hand here to match what was remembered.
        if (savedInstanceState?.getBoolean("chase_mode")
                ?: preferenceManager.getCameraChase()) {
            // remembered, not just tapped: engage it silently, no armed toast
            setChaseMode(true, announce = false)
        }
        followButton.imageAlpha = if (followMode && !chaseMode) 255 else 128
        mapTypeButton = findViewById(R.id.map_type_button)
        northUpButton = findViewById(R.id.north_up_button)
        compassHeading = findViewById(R.id.compass_heading)
        loadingGrid = findViewById(R.id.loading_grid)
        clock_text = findViewById(R.id.clock_text)
        myLocationButton = findViewById(R.id.my_location_button)
        findQuadButton = findViewById(R.id.find_quad_button)
        savedInstanceState?.let { videoPane.restoreFrom(it) }
        settingsButton = findViewById(R.id.settings_button)
        replayButton = findViewById(R.id.replay_button)
        seekBar = findViewById(R.id.seekbar)
        playButton = findViewById(R.id.play_button)
        horizonView = findViewById(R.id.horizon_view)
        fullscreenButton = findViewById(R.id.fullscreen_button)
        menuButton = findViewById(R.id.replay_menu_button)
        mapHolder = findViewById(R.id.map_holder)
        mapPane = findViewById(R.id.map_pane)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        fullscreenButton.setOnClickListener {
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
            flightOverlays.faceNorth()
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
            replayHold.handTakesOver()
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
                ),
                // A rotation carries the replay's place across the rebuild; a
                // fresh open from the log list has no bundle and starts as ever.
                savedInstanceState?.getInt("replay_position", -1) ?: -1,
                savedInstanceState?.getBoolean("replay_playing", false) ?: false
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
        this.registerReceiver(
            this.usbAttached, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))
    }

    // The one definition of "fullscreen", shared by the window, the dialogs
    // and the state check, so they can never disagree. STICKY, not plain
    // IMMERSIVE: a plain-immersive swipe for the notifications cancelled the
    // flags for good, so peeking at a message quietly ended fullscreen —
    // sticky shows the bars as a passing overlay and keeps the mode. The
    // LAYOUT flags hold the layout at full size while those transient bars
    // come and go, and lay the content into the strip the hidden bars leave.
    private val fullscreenFlags =
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

    internal fun updateWindowFullscreenDecoration() {
        window.decorView.systemUiVisibility =
            if (this.fullscreenWindow) fullscreenFlags else 0
        // The flags hide the status bar, but a phone with a camera cutout
        // still keeps the window out of the cutout's strip by default — the
        // bar went away and a black band stayed, which read as fullscreen
        // stopping short of the top. Short-edges lets the app own that strip,
        // and only in fullscreen: the default is restored otherwise, or a side
        // cutout held landscape would eat into the split view too. Written
        // through a copy — assigning the window its own attributes object back
        // compares the values to themselves, sees no change, and applies
        // nothing (the bug that made the first attempt do nothing).
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val decorated = WindowManager.LayoutParams()
            decorated.copyFrom(window.attributes)
            decorated.layoutInDisplayCutoutMode = if (fullscreenWindow)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            else
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = decorated
        }
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
        flightOverlays.forgetMapTraffic()

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
        operatorLine = map?.addOperatorLine(LineWeights.HOME, preferenceManager.getOperatorLineColor())
        drawFlightPlans()
        flightHeadLine = map?.addFlightHeadLine(
            LineWeights.FLIGHT, preferenceManager.getRouteColor()
        )
        // Keep a real two-point line from the start, as the heading line
        // does, so a missing phone fix leaves it alone instead of repeatedly
        // dismantling its renderer source — but only where there is a flight
        // to seed it with. Idle, lastGPS is (0,0), and a zero-length segment
        // there drew its round cap as a dot on the equator: a fresh install
        // opens on the whole world and stared straight at it.
        if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) {
            homeLine?.addPoints(listOf(lastGPS, lastGPS))
            operatorLine?.addPoints(listOf(lastGPS, lastGPS))
            flightHeadLine?.addPoints(listOf(lastGPS, lastGPS))
        }
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
        val csv = File(log.parentFile, logManager.replaceExtension(log.name, ".csv"))
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
            flightOverlays.hideOperator()
            recordedMe = null
            updateHomeLine()
            return
        }
        val track = operatorTrack
        val now = replayTimeNow()
        if (track == null || now == null) {
            // nothing recorded of where anybody stood, so nothing orange drawn
            flightOverlays.hideOperator()
            updateHomeLine()
            return
        }
        // A flight recorded with the app in the background has times and no
        // places: the clock runs, and nothing orange is drawn.
        val where = track.at(now.time)
        if (where == null) {
            flightOverlays.hideOperator()
            recordedMe = null
            updateHomeLine()
            return
        }
        recordedMe = Position(where.lat, where.lon)

        // Its own arrow in both views — orange, beside the blue one that is
        // where the phone is now. On the map it is a second overlay of the same
        // kind, so it is drawn exactly as the live one is.
        flightOverlays.showOperator(where.lat, where.lon, where.accuracy, where.heading)
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
            permissionFunnel.ask(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                REQUEST_LOCATION_PERMISSION
            )
            map?.isMyLocationEnabled = false
        }
    }

    /** Every permission ask takes its turn here — see PermissionFunnel. */
    private val permissionFunnel = PermissionFunnel(this, showDialog = ::showDialog)


    private fun initHeadingLine() {
        polyLine?.let { it.color = preferenceManager.getRouteColor() }
        flightHeadLine?.let { it.color = preferenceManager.getRouteColor() }
        marker?.setIcon(modelIcon(), preferenceManager.getPlaneColor())
        if (!isIdle()) {
            // and only once there is a fix to seed it at: connected but not
            // yet located, lastGPS is (0,0), and the seed's round cap drew a
            // dot on the equator — tryCreateMarker builds the line when the
            // fix lands
            if (preferenceManager.isHeadingLineEnabled() && headingPolyline == null &&
                (lastGPS.lat != 0.0 || lastGPS.lon != 0.0)
            ) {
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
        // stays joined to it as it moves — and with no model there is nothing
        // for it to start from. Keyed on the model itself, not on a position:
        // a receiver still hunting satellites forwards its last remembered
        // spot, non-zero with no fix, and a line drawn to it hung there
        // pointing at a model the fix-gated marker had rightly withheld — the
        // "GPS wait" line with no model. Cleared rather than left, or one end
        // survives the flight it was drawn for.
        val drone = if (marker != null) {
            displayedDrone ?: presentedPosition()
        } else {
            line.clear()
            return
        }
        // where this phone is, from the system if the map's own overlay has
        // not found it yet: a newly built map takes a while to get its first
        // fix, and the line waited all of it
        val phone = myLastKnownPlace()
        if (phone == null) {
            line.clear()
            return
        }
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
        // and no model is the same answer as no record, for the same reason
        val drone = if (marker != null) {
            displayedDrone ?: presentedPosition()
        } else {
            line.clear()
            return
        }
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

    /**
     * Whether there is a model position to be measured from or drawn at.
     *
     * A place and a reason to believe it. A receiver hands over the position
     * it remembers while it hunts for satellites, and that is not where the
     * model is — so the marker, the track and the sky all ask this rather
     * than asking whether a position exists.
     */
    private fun haveModelPosition(): Boolean =
        hasGPSFix && (lastGPS.lat != 0.0 || lastGPS.lon != 0.0)

    private fun isIdle(): Boolean {
        return !isInReplayMode() && !(dataService?.isConnected() ?: false)
    }

    internal fun replay() {
        if (dataService?.isConnected() != true) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_DENIED
            ) {
                permissionFunnel.ask(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    REQUEST_READ_PERMISSION
                )
            } else {
                logManager.open()
            }
        } else {
            Toast.makeText(this, "You need to disconnect first", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun startReplay(file: File?, resumePosition: Int = -1, resumePlaying: Boolean = false) {
        // A hold belonging to the replay being closed. Left set, tearing down
        // the old 3D view below released it — onto a player just disposed.
        replayHold.clear()
        logPlayer?.dispose()
        GhstProtocol.forgetLaunchAltitude()
        juricabi.com.telemetry.gl.AltitudeFrame.forget()
        telemetryPanel.forgetCells()
        // The replay forwards its log's device name now, so the rate system a
        // previous link or replay earned must not outlive it here. The
        // protocol FACT is deliberately kept: a rotation restores it from the
        // bundle and re-runs this, and wiping it here made the re-announced
        // name read as news — the toast fired on every turn of the phone.
        telemetryPanel.forgetLinkName()
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
            progressDialog.window?.decorView?.systemUiVisibility =
                if (this.fullscreenWindow) fullscreenFlags else 0
            progressDialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

            switchToReplayMode()

            readOperatorTrack(file)

            this.logPlayer = LogPlayer(decodeListener)

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
                    logPlayer?.let { player ->
                        val firstFix = player.firstFixPosition()
                        if (resumePosition > firstFix) {
                            // A rotation caught the replay part-way in: land
                            // exactly there. Whether it goes on playing is left
                            // to getPlaybackAutostart below — the one place that
                            // also holds playback until the 3D ground is ready,
                            // so a resumed replay never starts over an empty
                            // world.
                            replayHold.armResume(resumePlaying)
                            player.seek(resumePosition)
                            seekBar.progress = resumePosition
                        } else {
                            // A fresh open, or a rotation before the flight had
                            // moved off its first fix (a resume caught mid-decode
                            // still reads position 0). Open framed on the first
                            // fix — where the model stands — never on a 0 that
                            // can sit before the first GPS fix and draw nothing,
                            // and follow the autostart preference. Rewind to the
                            // very start only when playback will run from there;
                            // otherwise the bar's thumb goes to the first fix so
                            // it does not sit at 0 while the model is drawn
                            // further in and then jump when play is pressed.
                            replayHold.armResume(null)
                            player.seek(firstFix)
                            if (preferenceManager.getPlaybackAutostart()) {
                                player.seek(0)
                            } else {
                                seekBar.progress = firstFix
                            }
                        }
                    }
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
                    // The decision is ReplayHold's; only the ground's state is
                    // read here, where the 3D view lives.
                    val view = terrain3D
                    return replayHold.shouldStart(
                        view != null && view.groundBegun() && !view.groundReady())
                }

                override fun onProtocolDetected(protocolName: String) {
                    // The fact to its owner, the rendering to the row — the
                    // same two halves the live road's multicast feeds.
                    this@MapsActivity.onProtocolDetected(protocolName)
                    telemetryPanel.onProtocolDetected(protocolName)
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

    private fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) {
        // the mode row itself is the panel's, told through the multicast
        isArmed = armed
        gotArmedState = true
    }

    override fun onLowMemory() {
        super.onLowMemory()
        map?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        logManager.onSaveInstanceState(outState)
        map?.onSaveInstanceState(outState)
        outState?.putBoolean("follow_mode", followMode)
        telemetryPanel.saveInto(outState)
        outState?.putString("detected_protocol", detectedProtocol)
        outState?.putBoolean("chase_mode", chaseMode)
        videoPane.saveInto(outState)
        outState?.putString("replay_file_name", replayFileString)
        // Where a replay had got to, and whether it was running, so a rotation
        // lands back on the same moment instead of reloading the whole log to
        // its start.
        outState?.putInt("replay_position", logPlayer?.currentPosition ?: -1)
        outState?.putBoolean("replay_playing", replayWasPlaying)
        // What a dropped link needs to come back to lives only on this screen,
        // and turning the phone round builds it again from nothing. Without
        // these a link that drops after a rotation would never reconnect, and
        // one already mid-retry when the phone turned would be forgotten.
        outState?.putInt("last_conn_type", lastConnectionType)
        outState?.putParcelable("last_bt_device", lastBluetoothDevice)
        outState?.putString("last_net_host", lastNetworkHost)
        outState?.putInt("last_net_port", lastNetworkPort)
        outState?.putInt("last_net_mode", lastNetworkMode)
        outState?.putBoolean("last_net_hl", lastNetworkHighLatency)
        outState?.putBoolean("reconnect_on_failure", reconnectOnFailure)
        outState?.putLong("reconnect_start", reconnectionStartTime)
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

        telemetryPanel.placeSensors()
    }

    internal fun connect() {
        lastConnectionType = CONNTYPE_NONE;
        // Tapping connect is a deliberate act: it ends any reconnect the last
        // drop had armed, so a retry cannot fire behind the chooser or race a
        // manual connect. The flag itself, not only the type guard, so even a
        // retry caught mid-attempt schedules no successor.
        if (reconnectOnFailure) {
            Toast.makeText(this, "Reconnect called off", Toast.LENGTH_SHORT).show()
        }
        reconnectOnFailure = false
        reconnectionStartTime = 0
        // and the label steps down with it: the USB wait wears Reconnecting…
        // on this button, and cancelling the chooser is not a road back here
        connectButton.text = getString(R.string.connect)
        connectFlow.open()
    }

    override fun onResume() {
        super.onResume()
        map?.onResume()
        terrain3D?.let {
            it.onResume()
            applyTerrainSettings(it)
        }
        this.telemetryPanel.resume();
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
        videoPane.updateControls()
        // built fresh from the settings just left, so a changed source or
        // address takes effect on the way back. Noted, because a resume also
        // follows every system dialog — a run of these lines in the log means
        // something keeps pausing the screen, not that somebody keeps tapping.
        videoPane.restartIfWanted()
    }

    /**
     * Whether a replay was running when the screen last paused, caught in
     * onPause before the player is stopped — see the note there.
     */
    private var replayWasPlaying = false

    override fun onPause() {
        super.onPause()
        clock_text.removeCallbacks(clockTicker)
        terrain3D?.onPause()
        map?.onPause()
        this.telemetryPanel.pause();
        // Caught before the player is stopped on the next line: onPause always
        // runs before onSaveInstanceState, so reading isPlaying() there would
        // see this stop, and a replay that was running would come back paused
        // after a rotation.
        replayWasPlaying = this.logPlayer?.isPlaying() == true
        this.logPlayer?.stop();
        stopFr24(clear = false)
        // The service keeps both location and compass for a connected flight;
        // this only removes callbacks to a screen that is no longer drawing.
        setPhoneWatch(false)
    }

    override fun onStop() {
        super.onStop()
        map?.onStop()
        this.logPlayer?.stop();
        this.telemetryPanel.pause();
        videoPane.releaseForStop()
    }

    private fun resetUI() {
        telemetryPanel.reset()
        this.lastTraveledDistance = 0.0;
        horizonView.snapLevel()
    }


    internal fun connectToBluetoothDevice(device: BluetoothDevice, isBLE: Boolean) {
        clearCrsfSystem()
        if ( isBLE ) {
            lastConnectionType = CONNTYPE_BLE;
        }
        else {
            lastConnectionType = CONNTYPE_BT;
        }
        // Recorded outside the let, beside the type it pairs with: a null
        // service must never leave the type saying Bluetooth with no device to
        // reconnect to — the reconnect's own `device != null` guard would then
        // make that link silently non-reconnectable. The network params are
        // kept outside their let for the same reason.
        lastBluetoothDevice = device;
        reconnectionStartTime = 0;
        reconnectOnFailure = false;

        startDataService()
        dataService?.let {
            connectButton.text = getString(R.string.connecting)
            connectButton.isEnabled = false
            it.connect(device, isBLE)
        }
    }

    private fun reconnectToBluetoothDevice() {
        // A retry is scheduled five seconds out, long enough for someone — or a
        // retry from an earlier drop — to have got there first. Without this it
        // would tear down the live link and start again; the network reconnect
        // guards the same way.
        if (dataService?.isConnected() == true) {
            return
        }
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
                it.connect(lastBluetoothDevice as BluetoothDevice, true, newSession = false)
                } else if (lastConnectionType == CONNTYPE_BT) {
                it.connect(lastBluetoothDevice as BluetoothDevice, false, newSession = false)
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
            telemetryPanel.setTimeoutWindow(
                if (lastNetworkHighLatency) SensorTimeoutManager.HIGH_LATENCY_TIMEOUT_MS
                else SensorTimeoutManager.DEFAULT_TIMEOUT_MS)
            it.connect(lastNetworkHost, lastNetworkPort, lastNetworkMode,
                lastNetworkHighLatency, newSession = false)
        }
    }

    internal fun connectToUSBDevice(
        port: UsbSerialPort,
        connection: UsbDeviceConnection,
        newSession: Boolean = true
    ) {
        // a reconnect continues the flight and its log; only a fresh
        // session starts over
        if (newSession) clearCrsfSystem()
        lastConnectionType = CONNTYPE_USB;
        startDataService()
        dataService?.let {
            connectButton.text = getString(
                if (newSession) R.string.connecting else R.string.reconnecting)
            connectButton.isEnabled = false
            it.connect(port, connection, newSession)
        }
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
        homeAltitudeMsl = Float.NaN
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
        // forgotten before the ground is started again, or it is started on the
        // flight that has just been thrown away
        forgetFlight()
        startFlightIn3D()
        // the fact and its rendering, each at its owner
        detectedProtocol = ""
        telemetryPanel.newLink()
    }

    internal fun connectToNetwork(host: String, port: Int, mode: Int,
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
        telemetryPanel.setTimeoutWindow(
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
        trafficWarnings?.shutdown()
        trafficWarnings = null
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
        this.unregisterReceiver(this.usbAttached)
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
        permissionFunnel.resolved()
        if (grantResults.isEmpty()) {
            // an interrupted ask delivers no results at all; the camera ask
            // put a waiting half on screen that must not outlive its dialog
            if (requestCode == REQUEST_CAMERA_PERMISSION) {
                videoPane.onCameraAskInterrupted()
            }
            return
        }
        if (grantResults.isNotEmpty()) {
            if (requestCode == REQUEST_LOCATION_PERMISSION) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // through the same door the ask came from, so the
                    // live-arrow setting and the 3D view are both honoured
                    showMyLocation()
                } else {
                    permissionFunnel.explainDenied(
                        "Location permission is needed in order to discover BLE devices and show your location on map",
                        permissions.firstOrNull()
                    )
                }
            } else if (requestCode == REQUEST_WRITE_PERMISSION) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    when (requestWritePermissionSequence) {
                        RequestWritePermissionSequenceType.CONNECT -> connect()
                        RequestWritePermissionSequenceType.DELETE -> logManager.showDeleteLogDialog()
                        RequestWritePermissionSequenceType.LOG_PICKER -> replay()
                        RequestWritePermissionSequenceType.RENAME -> logManager.showRenameLogDialog()
                        RequestWritePermissionSequenceType.EXPORT_GPX -> logManager.showExportGPXDialog()
                        RequestWritePermissionSequenceType.EXPORT_KML -> logManager.showExportKMLDialog1()
                        // nothing was waiting on the permission
                        RequestWritePermissionSequenceType.NONE -> {}
                    }
                    requestWritePermissionSequence = RequestWritePermissionSequenceType.NONE;
                } else {
                    permissionFunnel.explainDenied(
                        "Write permission is required in order to log telemetry data. Disable logging or grant permission to continue",
                        permissions.firstOrNull()
                    )
                }
            } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
                if (!videoPane.onCameraPermission(
                        grantResults[0] == PackageManager.PERMISSION_GRANTED)
                ) {
                    permissionFunnel.explainDenied(
                        "Camera permission is needed for a USB camera — " +
                            "Android refuses the USB device without it",
                        permissions.firstOrNull()
                    )
                }
            } else if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
                if (!videoPane.onRecordAudioPermission(
                        grantResults[0] == PackageManager.PERMISSION_GRANTED)
                ) {
                    permissionFunnel.explainDenied(
                        "Microphone permission lets the app play the USB " +
                            "receiver's own sound — the picture plays without it",
                        permissions.firstOrNull()
                    )
                }
            } else if (requestCode == REQUEST_READ_PERMISSION) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    replay()
                } else {
                    permissionFunnel.explainDenied(
                        "Read permission is required in order to read and replay telemetry data",
                        permissions.firstOrNull()
                    )
                }
            }
        }
    }

    /**
     * A "don't ask again" refusal makes every later ask come back denied
     * instantly, dialog unseen — an explanation alone then describes a grant
     * that is impossible from inside the app. Exactly there, and only there,
     * the dialog grows a way to the one place the grant still lives.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (logManager.onActivityResult(requestCode, resultCode, data)) return
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_OK) {
            connectFlow.connectBluetooth()
        }
    }

    // the panel hears these itself through the multicast; only the flight's
    // own business is left here
    private fun onAltitudeData(altitude: Float) {
        flightAltitude.onFallback(altitude)
    }

    private fun onGPSAltitudeData(altitude: Float) {
        // raw: the height question judges the reported values, and lifting
        // its own input would beg it
        flightAltitude.onGps(altitude)
        // The widget is named above MSL, so once the flight has proven its
        // heights are measured from the launch, it shows them lifted — the
        // same number the 3D view draws and the published position says.
        // Except known-disarmed readings, whose datum cannot be known —
        // old Betaflight goes back to saying sea level on disarm, and the
        // lift doubled it on the ground; shown raw, like every consumer
        // treats them.
        val lift =
            if (gotArmedState && !isArmed) 0f
            else juricabi.com.telemetry.gl.AltitudeFrame
                .lift(juricabi.com.telemetry.gl.AltitudeFrame.currentEpoch()) ?: 0f
        telemetryPanel.showAltitude(altitude + lift, true)
    }

    /** How high the aircraft's home stands; only iNav over LTM says it. */
    @Volatile private var homeAltitudeMsl = Float.NaN

    private fun onHomeData(latitude: Double, longitude: Double, altitudeMsl: Float) {
        homeAltitudeMsl = altitudeMsl
    }

    private fun onRollData(rollAngle: Float) {
        lastRoll = rollAngle
        runOnUiThread {
            horizonView.setRoll(rollAngle)
            terrain3D?.setModelAttitude(lastHeading, lastPitch, lastRoll)
        }
    }

    private fun onPitchData(pitchAngle: Float) {
        lastPitch = pitchAngle
        runOnUiThread {
            horizonView.setPitch(pitchAngle)
            terrain3D?.setModelAttitude(lastHeading, lastPitch, lastRoll)
        }
    }

    private fun onGPSState(satellites: Int, gpsFix: Boolean) {
        runOnUiThread {
            // Nothing is feeding this screen, so this was decoded before the
            // link went and posted after: it belongs to a flight that has
            // ended. See onGPSData, which it would otherwise re-arm.
            if (isIdle()) return@runOnUiThread
            this.hasGPSFix = gpsFix
            this.tryCreateMarker()
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
            replayHold.holdForGround()
        }
        view.onFollowingLost = { setFollowMode(false) }
        // The world belongs to whatever is alive: a link, or a replay, or —
        // when neither is — the person holding the phone. Standing outside a
        // world a finished flight left behind, locate is asking for that
        // handover, and the only button whose whole job is "show me where I
        // am" should be able to do it.
        view.onLocateBeyondWorld = {
            // Somewhere to go home to, or there is nothing this can offer and
            // the view's own words are the honest answer.
            val fix = bestPhoneFix ?: myLastKnownFix()
            if (isIdle() && fix != null) {
                // The asking is to protect a flight that is still drawn. With
                // none — ended already, and only its world left standing in
                // another country — there is nothing to lose and nothing to
                // ask about, and "end the flight" named something that was
                // not there.
                if (juricabi.com.telemetry.gl.LiveFlightPath.size() > 0) {
                    askToEndTheFlight()
                } else {
                    endTheFlight()
                }
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
        // Arriving in a view shows the flight, in both of them alike. The map
        // is built afresh every time and aims itself at the flight; a world
        // adopted from the garage keeps whatever it was last pointed at, so
        // after the locate button it handed back a view of the phone, switch
        // after switch, while the map beside it showed the model. Only
        // arriving re-aims — panning away inside a view still stays where it
        // is put, which is the whole point of not snapping back.
        if (adopted != null) {
            juricabi.com.telemetry.gl.LiveFlightPath.latest()?.let {
                view.lookAt(it.lat, it.lon, it.altitudeMsl)
            }
        }
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
        // counted on from the flight's first point, the way the scene counts
        // its own extent: taken raw, a flight either side of the 180th
        // meridian asks for every tile on the row and the prefetch refuses
        // it, and the altitude profile draws against no ground at all
        val meridian = path[0].lon
        var minLon = meridian; var maxLon = meridian
        for (p in path) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            val lon = juricabi.com.telemetry.gl.TerrainScene.unwrapped(p.lon, meridian)
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
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
        // The height question must not depend on which view is open: the
        // mock broadcasts and the MSL widget reads the answer with the map
        // in 2D, where neither the 3D tick nor the profile dialog asks —
        // a live flight watched flat published relative heights as sea
        // level for as long as nobody glanced at the terrain.
        if (!isInReplayMode()) askHeightQuestion()
    }

    /** When the live road may next walk the flight; the walk is the dear part. */
    private var heightQuestionAskAt = 0L

    private fun askHeightQuestion() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < heightQuestionAskAt) return
        heightQuestionAskAt = now + 5_000
        juricabi.com.telemetry.utils.Elevation.init(this)
        // The one link that tells the zero outright — iNav over LTM says
        // where home is and how high it stands — proves the lift straight
        // off the wire: exact, pad or no pad, mid-air joins included, no
        // terrain model owed. Only LTM: on any other link the reported
        // heights may already be sea level, and lifting truth is the one
        // mistake this machinery must never make.
        if (!homeAltitudeMsl.isNaN() && detectedProtocol == "LTM") {
            juricabi.com.telemetry.gl.AltitudeFrame.settle(
                juricabi.com.telemetry.gl.TerrainScene.Companion.Reference(
                    true, homeAltitudeMsl),
                juricabi.com.telemetry.gl.AltitudeFrame.currentEpoch())
        }
        val path = flightPath
        if (path.size < 2) return
        // the ground under the flight, fired and not waited for — the same
        // warm the 3D view runs before it judges
        var s = path[0].lat; var n = path[0].lat
        val meridian = path[0].lon
        var w = meridian; var e = meridian
        for (p in path) {
            if (p.lat < s) s = p.lat; if (p.lat > n) n = p.lat
            val lon = juricabi.com.telemetry.gl.TerrainScene.unwrapped(p.lon, meridian)
            if (lon < w) w = lon; if (lon > e) e = lon
        }
        juricabi.com.telemetry.utils.Elevation.warmBox(
            s, w, n, e, juricabi.com.telemetry.utils.Elevation.TILE_ZOOM)
        val epoch = juricabi.com.telemetry.gl.AltitudeFrame.currentEpoch()
        val proposed = juricabi.com.telemetry.gl.TerrainScene.referenceOf(
            path, juricabi.com.telemetry.utils.Elevation.TILE_ZOOM) ?: return
        juricabi.com.telemetry.gl.AltitudeFrame.settle(proposed, epoch)
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
     * cannot be known from the reading. And left out always, not behind the
     * switch this once was: a craft that is not armed is standing on ground
     * the terrain already draws, so its height adds nothing a viewer could
     * miss — while keeping it is what drew the spike, and the switch made
     * the person flying know their firmware's arming habits to escape it.
     * The fix itself is still recorded, and is drawn where it was, on the
     * ground.
     */
    private fun heightNow(fixCount: Int = 1): Float {
        val reported = flightAltitude.forFix(fixCount)
        if (gotArmedState && !isArmed) return Float.NaN
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

    private fun onHeadingData(heading: Float) {
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
        // Pinned to the model like the home line beside it: no model, no line —
        // before the first fix, or while a receiver reports a remembered spot
        // with none, the heading line no longer stands over an empty map.
        if (marker != null) {
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
        } else {
            headingPolyline?.clear()
        }
    }

    private fun onDisconnected() {
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
            if (asked) {
                val recorded = dataService?.isRecording() == true
                juricabi.com.telemetry.utils.DebugLog.note(
                    "Flight",
                    "disconnect asked for, recorded=" + recorded +
                        (if (recorded) "" else " - flight kept, nothing written"))
                if (recorded) endTheFlight()
            }

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
            val isUsb = lastConnectionType == CONNTYPE_USB

            // Each transport has its own switch. They are not the same event:
            // someone may want the radio link retried but not a Wi-Fi one that
            // would keep chasing an access point they have walked away from.
            // USB serial is the radio link too, so it shares the radio switch.
            val enabled = when {
                isBluetooth || isUsb -> preferenceManager.getReconnectionEnabled()
                isNetwork -> preferenceManager.getNetworkReconnectionEnabled()
                else -> false
            }

            if (enabled) {
                if (reconnectionStartTime == 0L) {
                    reconnectionStartTime = System.currentTimeMillis()
                }

                // One minute, every transport. The network earned it first — a
                // rebooting transmitter, and the phone re-associating with its
                // access point, before anything can be reached at all. USB
                // spends it on the radio's USB-mode chooser between the plug
                // and the port. Bluetooth had 21 seconds and was raised to
                // match: a radio that browned out and rebooted got no time.
                val window = 60000

                if ((System.currentTimeMillis() - reconnectionStartTime) < window) {
                    if (isUsb) {
                        // Nothing to poll: the cable that left took the port
                        // with it, and its return announces itself — the
                        // attach broadcast is the retry. Stay armed for it,
                        // and wear the label: without it the wait looks like
                        // a link nobody intends to bring back. The button
                        // stays live — a tap is still the way out into a
                        // hand connect, which calls the retry off.
                        connectButton.text = getString(R.string.reconnecting)
                        // No attach may ever come, and nothing else runs at
                        // the window's end to take the label down — this
                        // does, unless a newer drop owns a fresh window or
                        // a link is already up.
                        connectButton.postDelayed({
                            if (isFinishing || isDestroyed) return@postDelayed
                            if (!reconnectOnFailure ||
                                lastConnectionType != CONNTYPE_USB) return@postDelayed
                            if (dataService?.isConnected() == true) return@postDelayed
                            if (reconnectionStartTime == 0L ||
                                (System.currentTimeMillis() - reconnectionStartTime) < window
                            ) return@postDelayed
                            reconnectOnFailure = false
                            reconnectionStartTime = 0L
                            connectButton.text = getString(R.string.connect)
                            Toast.makeText(this@MapsActivity,
                                "Reconnect timed out", Toast.LENGTH_SHORT).show()
                        }, window - (System.currentTimeMillis() - reconnectionStartTime) + 500)
                        return@runOnUiThread
                    }
                    AsyncTask.execute {
                        Thread.sleep(5000)
                        runOnUiThread {
                            // reconnecting from a dead Activity re-binds the
                            // service to it, leaving the live screen deaf
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            // and a deliberate act in the five-second wait — a
                            // manual connect, or opening a replay — clears this
                            // and so calls the whole retry off before it fires
                            if (!reconnectOnFailure) return@runOnUiThread
                            if (isNetwork) {
                                reconnectToNetwork()
                            } else {
                                reconnectToBluetoothDevice()
                            }
                        }
                    }
                    return@runOnUiThread
                }
            }

            // Nothing more will be tried: the transport does not reconnect (a
            // deliberate disconnect that set the type to none), the setting is
            // off, or the window has run out. Clear the intent to retry rather
            // than leave it armed for the next, unrelated link to inherit —
            // the type guard was the only thing catching that before.
            // Only the run-out is worth a word — an armed retry dying in
            // silence read as still trying; the other reasons announce
            // nothing anyone did not already choose.
            if (enabled && reconnectOnFailure && reconnectionStartTime != 0L) {
                Toast.makeText(this, "Reconnect timed out", Toast.LENGTH_SHORT).show()
            }
            reconnectOnFailure = false
            reconnectionStartTime = 0L
        }
    }

    /**
     * The USB link's retry, fired by the attach broadcast rather than a
     * timer. The first enumeration after a plug-in may be the radio's mode
     * chooser and not yet a serial port; choosing USB Serial re-enumerates
     * and comes back through here — quiet until then, since the chooser is
     * on the radio's own screen.
     */
    private fun reconnectToUSB() {
        if (lastConnectionType != CONNTYPE_USB || !reconnectOnFailure) return
        if (dataService?.isConnected() == true) return
        if (!preferenceManager.getReconnectionEnabled()) return
        if (reconnectionStartTime != 0L &&
            (System.currentTimeMillis() - reconnectionStartTime) >= 60000
        ) {
            // the window ran out waiting; the next plug is a fresh connect —
            // said at the plug that arrived too late, in case the timed
            // check never got to say it
            reconnectOnFailure = false
            reconnectionStartTime = 0L
            connectButton.text = getString(R.string.connect)
            Toast.makeText(this, "Reconnect timed out", Toast.LENGTH_SHORT).show()
            return
        }
        if (!connectFlow.hasSerialDevice()) return
        val now = System.currentTimeMillis()
        if (!usbPowerHintShown && now - lastUsbAutoReconnect < 45000) {
            usbPowerHintShown = true
            // a toast was tried first and lost twice over: the connect's own
            // toasts replaced it, and its few seconds were too short for
            // three lines anyway. The bar stays until waved off.
            val bar = Snackbar.make(connectButton, R.string.usb_power_hint,
                Snackbar.LENGTH_INDEFINITE)
            bar.setAction(R.string.ok) { }
            bar.view.findViewById<TextView>(
                com.google.android.material.R.id.snackbar_text)?.maxLines = 6
            bar.show()
        }
        lastUsbAutoReconnect = now
        if (preferenceManager.getConnectionVoiceMessagesEnabled()) {
            soundPool!!.play(reconnectingSoundId, 1f, 1f, 0, 0, 1f)
        }
        connectFlow.connectUSB(newSession = false)
    }

    private fun switchToReplayMode() {
        stopFr24(clear = true)
        // A replay opens in whatever mode is selected — a flight's armed mode
        // takes hold from its first point, the way a live link's does. Forcing
        // follow here overrode a chosen chase, and it never told the 3D view,
        // whose follow state then stood stale over a kept world. Re-sync the
        // selected mode to the 3D view; the 2D map reads keepingUp() per fix
        // and needs nothing more.
        terrain3D?.setFollowing(keepingUp())
        terrain3D?.setChasing(chaseMode)
        // Opening a replay is a deliberate choice: it ends any reconnect the
        // dropped link had armed, so the retry does not fire on top of it.
        if (reconnectOnFailure) {
            Toast.makeText(this, "Reconnect called off", Toast.LENGTH_SHORT).show()
        }
        reconnectOnFailure = false
        reconnectionStartTime = 0
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
        this.telemetryPanel.enterReplay()
        lastGPS = Position(0.0, 0.0);
        hasGPSFix = false;
    }

    internal fun switchToIdleState() {
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
        this.telemetryPanel.leaveReplay()
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
        if (replayHold.releaseForGround()) logPlayer?.startPlayback()
    }

    internal fun closeReplay() {
        // first, so that everything asking whether this is a replay is answered
        // truthfully by the time it is asked — the live arrow is switched on by
        // one of those answers, and was being switched on while the replay was
        // still officially open, which left it off
        replayFileString = null
        // A replay held back for ground it never got is owed nothing once it
        // has been closed.
        replayHold.clear()
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
        // and the link's own business ends with it. Pressing Disconnect
        // already stops the retries; a flight ended from the locate button
        // was left with them running, so a link that had dropped on its own
        // could come back a few seconds after somebody said they were done
        // — and the ending would read as though it had not worked. The
        // retry already scheduled reads this before it acts.
        lastConnectionType = CONNTYPE_NONE
        // There is no link, so there is no fix. Left standing, a frame the
        // decoder was already holding when the button was pressed lands
        // afterwards and draws the first point of a flight that has ended.
        hasGPSFix = false
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
        // The sky measures from the person now, not from the model that has
        // gone: standing at the field between packs is exactly when an
        // aircraft crossing overhead is worth being told about.
        if (fix != null) fr24Manager?.watchFrom(fix.latitude, fix.longitude, model = false)
        else fr24Manager?.watchNothing()
        // Nowhere to come home to: end the flight and leave the ground alone
        // rather than tear down a working world for a place we do not know.
        if (fix == null) {
            juricabi.com.telemetry.utils.DebugLog.note(
                "Flight", "ended: no phone fix, ground left where it stands")
            startFlightIn3D()
            return
        }
        val mine = Position(fix.latitude, fix.longitude)
        val standing = terrain3D ?: parked3D
        val near = standing?.worldNear(mine.lat, mine.lon) == true
        // The one line that says which way this went. Two endings look the
        // same on the screen — a flight kept because nothing was recorded,
        // and a flight ended over a world that could not be brought home —
        // and only this tells them apart afterwards.
        juricabi.com.telemetry.utils.DebugLog.note(
            "Flight", "ended: world " + (if (near) "kept" else "rebuilt at the phone") +
                ", standing=" + (if (standing == null) "none" else "yes"))
        startFlightIn3D(keepWorld = near)
        // The camera is left exactly where it stands. Disconnect used to fly
        // both views home to the phone; that is the person's to do now — the
        // cameras are theirs to control, and closing a replay already leaves
        // them put. Both endings match, and nothing moves the view but a hand.
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
            // Two different questions wearing one button. Recorded, this is
            // tidying up and the recording holds everything; unrecorded, it
            // is the only copy of the flight going, and the difference has
            // to be visible before the finger lands rather than after.
            .setTitle(
                if (recorded) "Beyond this flight's ground"
                else "Nothing was recorded")
            .setMessage(
                if (recorded) "End the flight and build the world where you " +
                    "are? It stays in your recordings."
                else "End the flight and build the world where you are? This " +
                    "flight was never written to a log - the screen is the " +
                    "only copy of it, and ending it is final.")
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
        telemetryPanel.showConnected()
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

    private fun onConnectionFailed() {
        runOnUiThread {
            Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
            connectButton.text = getString(R.string.connect)
            telemetryPanel.showDisconnected()
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

    private fun onDecoderRestart() {
        // A fresh decoder reaches here two ways: a replay run to its end and
        // started again, which wants the old flight cleared before it re-runs;
        // and a live link (re)connecting. The live case needs no clear here — a
        // deliberate connect already cleared it through clearCrsfSystem, and a
        // reconnect must KEEP the flight a dropped link held for walking to a
        // downed model. So only a replay forgets here; a link coming back no
        // longer wipes the flight the instant it returns.
        runOnUiThread {
            hasGPSFix = false
            if (isInReplayMode()) forgetFlight()
        }
    }

    private fun commit() {
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

    private fun onGPSData(list: List<Position>, addToEnd: Boolean) {
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

    private fun onGPSData(latitude: Double, longitude: Double) {
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
                // Only a fix is a real position — for lastGPS as for the model.
                // A receiver still hunting satellites forwards a remembered, and
                // sometimes wildly wrong, spot: trusted, it put the last-known
                // thousands of km off (the "Find my quad" in the Arctic) and
                // swung both the 2D and 3D camera to it on the next open. Left
                // at 0,0 without a fix, every framing here already reads it as
                // "no position yet" and opens on the operator instead.
                if (hasGPSFix && (latitude != 0.0 || longitude != 0.0)) {
                    lastGPS = Position(latitude, longitude)
                    // live link only: a replayed log comes through here too, and
                    // would overwrite where the model was actually last seen
                    if (logPlayer == null) {
                        lastKnownGPS = lastGPS
                        lastKnownGPSAt = System.currentTimeMillis()
                    }
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
                    this.telemetryPanel.showTraveledDistance(this.lastTraveledDistance)
                    // and the sky measures from the model only where the model
                    // is believed. A receiver reports the place it remembers
                    // while it looks for satellites, which is why nothing else
                    // in here draws from one either — the sky took it, moved
                    // the watch to yesterday's field, and went quiet about the
                    // aircraft actually overhead.
                    fr24Manager?.watchFrom(latitude, longitude, model = true)
                }

                this.tryCreateMarker()
            }
        }
    }

    private fun onConnected() {
        runOnUiThread {
            reconnectionStartTime = 0L;
            // A link is up, so no reconnect is owed; it re-arms on the next
            // drop. Cleared here so the flag is never left true while connected
            // for the resume-on-rebuild path to misread.
            reconnectOnFailure = false
            Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show()
            switchToConnectedState()
            this.lastTraveledDistance = 0.0;
            this.telemetryPanel.showNoTraveledDistance()
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

    /**
     * A USB link's retry is an event, not a timer: the cable that left took
     * the port with it, and its return announces itself here — as does the
     * radio's own USB-mode choice, which re-enumerates the device. Without
     * an armed retry this does nothing.
     */
    private val usbAttached: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
            reconnectToUSB()
        }
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
        this.telemetryPanel.showPhoneBattery(lastPhoneBattery)
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
            logManager.showExportGPXDialog()
        }
        view.findViewById<View>(R.id.playback_kml).setOnClickListener {
            dialog.dismiss()
            logManager.showExportKMLDialog1()
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

    internal fun showDialog(dialog: AlertDialog) {
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        );
        dialog.show();
        dialog.window?.decorView?.systemUiVisibility =
            if (this.fullscreenWindow) fullscreenFlags else 0
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
        // remember it, so the next run opens in the mode last chosen
        preferenceManager.setCameraFollow(followMode)
        preferenceManager.setCameraChase(chaseMode)
        // Arrive at the model now rather than on the next fix. The 2D camera
        // only rides along inside the marker's easing step, which a paused
        // replay has let settle and stop, so without the kick following turned
        // on over one sat where it was until play was pressed. The 3D view goes
        // at once; this brings the map with it.
        if (mode) keepSmoothing()
    }

    /**
     * Riding behind the model, looking the way it is going: over its shoulder
     * in 3D, and the map turned to its heading in 2D.
     *
     * It keeps up with the model itself, so plain tracking gives way to it.
     */
    private fun setChaseMode(on: Boolean, announce: Boolean = true) {
        if (chaseMode == on) return
        chaseMode = on
        chaseButton.imageAlpha = if (on) 255 else 128
        if (on && lastGPS.lat == 0.0 && lastGPS.lon == 0.0 && announce) {
            // armed, not engaged — only when there is truly no flight to ride
            // behind, the same test the follow button makes. A paused replay
            // has a model and chase centres on it at once (the heading-up
            // follows once a heading is known), so keying this to
            // modelHeadingKnown fired "engages when a flight is up" even as the
            // camera plainly engaged. announce still keeps it off the cold-start
            // restore path.
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
        // remember it, so the next run opens in the mode last chosen
        preferenceManager.setCameraChase(chaseMode)
        preferenceManager.setCameraFollow(followMode)
        // The angle is left where the chase left it, in both views: the
        // north-up button is the way back to north and it is one tap, and
        // swinging the map round unasked, at the moment somebody has asked for
        // something else, is a movement nobody wanted.
        //
        // Go to the model now, though, without waiting for it to move: the 2D
        // camera only rides along inside the marker's easing step, which a
        // paused replay has let settle and stop, so chase turned on over a
        // stopped flight sat where it was until playback woke the step. The 3D
        // view already arrives at once; this brings the map with it.
        if (on) keepSmoothing()
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
                // Leaned or centred, the same per-frame follow. moveCameraNow
                // holds the raster still through a programmatic move and skips
                // an unchanged write, so following from an offset tracks the
                // model as smoothly as a centred chase without starving the
                // ground the hand was dragged to. A lean used to catch up once
                // every 500ms through the un-held moveCamera path — that was
                // the half-second stutter while chase or locate held a reframe.
                map.moveCameraNow(
                    Position(where.lat + mapLeanLat, where.lon + mapLeanLon),
                    orientation
                )
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
        // Nothing to post to before the holder exists. onCreate restores a
        // remembered chase mode — setChaseMode(true) — before it finds
        // mapHolder, and the kick added there would touch this lateinit too
        // early and crash the launch. There is no flight to arrive at that
        // early anyway; the first fix kicks the loop the ordinary way.
        if (!this::mapHolder.isInitialized) return
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

    fun requestWritePermission(seq: RequestWritePermissionSequenceType): Boolean {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_DENIED
        ) {
            requestWritePermissionSequence = seq;
            permissionFunnel.ask(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
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
        if (!preferenceManager.isFr24Enabled()) {
            // Switched off while the aircraft were on the map, the markers
            // stayed where the last poll left them — a frozen sky, hours old,
            // that nothing would ever move or remove again.
            stopFr24(clear = true)
            return
        }
        if (isInReplayMode()) return
        // onResume and leaving replay can meet in the same foreground lifetime.
        // Keep the manager already polling instead of orphaning it and its
        // executor behind a new reference.
        if (fr24Manager != null) return
        val manager = Fr24Manager(preferenceManager, this)
        fr24Manager = manager
        // Watching from wherever there is something to be near, from the
        // first poll: the model if one is flying, this phone if not. Left to
        // the next fix, a fresh manager warned about nothing at all until
        // one arrived — and with no link nothing listens for one.
        if (haveModelPosition()) {
            manager.watchFrom(lastGPS.lat, lastGPS.lon, model = true)
        } else {
            myLastKnownPlace()?.let { manager.watchFrom(it.lat, it.lon, model = false) }
        }
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
        // Both views, and the list they are rebuilt from. Without the list a
        // replay opened from the 3D view kept this afternoon's airliners
        // standing over last month's flight — a view built later put them
        // back from it.
        lastAirplanes = emptyList()
        flightOverlays.clearTraffic()
    }

    private var lastAirplanes: List<Fr24Manager.AirplaneInfo> = emptyList()

    override fun onAirplanesUpdated(airplanes: List<Fr24Manager.AirplaneInfo>) {
        lastAirplanes = airplanes
        runOnUiThread { flightOverlays.showTraffic(airplanes) }
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
        directionDeg: Double,
        fromModel: Boolean
    ) {
        trafficWarnings?.warn(airplane, distanceMeters, directionDeg, fromModel)
    }

}
