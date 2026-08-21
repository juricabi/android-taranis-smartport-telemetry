package juricabi.com.telemetry.service

import android.Manifest
import android.annotation.TargetApi
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.hardware.usb.UsbDeviceConnection
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import juricabi.com.telemetry.R
import juricabi.com.telemetry.logger.OtxCsvLogger
import juricabi.com.telemetry.manager.PreferenceManager
import juricabi.com.telemetry.maps.Position
import juricabi.com.telemetry.protocol.pollers.BluetoothDataPoller
import juricabi.com.telemetry.protocol.pollers.BluetoothLeDataPoller
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import juricabi.com.telemetry.protocol.decoder.ForwardingListener
import juricabi.com.telemetry.protocol.decoder.MulticastListener
import juricabi.com.telemetry.protocol.pollers.DataPoller
import juricabi.com.telemetry.protocol.pollers.NetworkDataPoller
import juricabi.com.telemetry.protocol.pollers.UsbDataPoller
import juricabi.com.telemetry.utils.NetworkBinder
import juricabi.com.telemetry.ui.MapsActivity
import java.io.File
import juricabi.com.telemetry.logger.CountingLog
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Every reading with no service business rides the delegated [bus] straight
 * to the ears — the screen and the CSV — through one MulticastListener wired
 * in onCreate. The explicit overrides below are the ones the service has its
 * own stake in: retention for a late-binding screen, the mock publisher, and
 * the connection lifecycle. Adding a telemetry value no longer touches this
 * file at all.
 */
class DataService private constructor(
    private val bus: ForwardingListener
) : Service(), DataDecoder.Listener by bus {

    constructor() : this(ForwardingListener())

    private var dataPoller: DataPoller? = null
    private var dataListener: DataDecoder.Listener? = null
    private var logListener: OtxCsvLogger? = null
    private val dataBinder = DataBinder()
    // Whether a link is up — the one fact the phone watcher and the mock
    // publisher both hang on. Owned here because the service owns connections.
    private var wantedByLink = false
    private var hasGPSFix = false
    private var isArmed = false
    // whether the link has said so at all: a link that never speaks of
    // arming is not treated as a disarmed one
    private var gotArmedState = false
    // the last fly-mode heard, kept whole: a screen rebuilt by rotation
    // asks setDataListener for the world as it stands, and arming was the
    // one piece it was not told — the disarmed-height gate then leaked
    // until the next fly-mode frame happened along
    private var lastFlyModeHeading = false
    private var lastFlyModeFirst: DataDecoder.Companion.FlyMode? = null
    private var lastFlyModeSecond: DataDecoder.Companion.FlyMode? = null
    private var satellites = 0
    private var lastSpeed: Float = 0.0f
    private var lastHeading: Float = 0.0f
    // NaN until a link says one: the mock fix carries an altitude only once
    // the drone has reported its own, rather than a made-up sea level
    private var lastGPSAltitude: Float = Float.NaN
    // the working-channel height, NaN until heard: published only when the
    // flight has PROVEN its heights are measured from the launch, which is
    // how a link with no absolute altitude at all — LTM — still hands the
    // tracker a true one
    private var lastRelativeAltitude: Float = Float.NaN
    private lateinit var preferenceManager: PreferenceManager

    private val settingsChanged =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PreferenceManager.KEY_BACKGROUND_COMPASS -> phoneWatcher.refresh(wantedByLink)
                // flipped mid-flight it takes effect now, not on the next link
                PreferenceManager.KEY_MOCK_LOCATION_ENABLED -> mockPublisher.refresh(wantedByLink)
            }
        }

    // The toast and the settings row both send people to Developer options
    // mid-flight, and the pick used to change nothing until the next
    // connect — the switch stood on, obeyed, publishing nothing. Watching
    // the app-op is what makes the pick take effect where it lands,
    // whichever screen it happens on.
    private val mockChoiceChanged =
        AppOpsManager.OnOpChangedListener { _, _ ->
            // arrives on a binder thread; the provider is installed and
            // removed on the main one
            Handler(Looper.getMainLooper()).post { mockPublisher.refresh(wantedByLink) }
        }

    /**
     * A poller may finish on another thread after its replacement is already
     * running. Its callbacks must not be allowed to clear or write into that
     * replacement. Every poller therefore gets a listener tied to the
     * generation in which it was created.
     */
    private val connectionLock = Any()
    private var connectionGeneration = 0L

    private inner class ConnectionListener(val generation: Long) :
        ForwardingListener(this@DataService) {
        override fun relay(deliver: (DataDecoder.Listener) -> Unit) {
            synchronized(connectionLock) {
                if (generation == connectionGeneration) super.relay(deliver)
            }
        }
    }

    private data class RetiredConnection(
        val generation: Long,
        val poller: DataPoller?,
        val logger: OtxCsvLogger?
    )

    /** Invalidates callbacks before the old poller is asked to stop. */
    private fun retireCurrentConnection(): RetiredConnection {
        synchronized(connectionLock) {
            connectionGeneration++
            val retired = RetiredConnection(connectionGeneration, dataPoller, logListener)
            dataPoller = null
            logListener = null
            wantedByLink = false
            satellites = 0
            hasGPSFix = false
            // dies with the link it described, so a screen binding to a
            // service with nothing connected is told nothing
            protocolName = null
            return retired
        }
    }

    private fun listenerForNewConnection(): ConnectionListener {
        val retired = retireCurrentConnection()
        // the new link speaks for itself: a mute one must not wear the old
        // one's arming state — nor its altitude, speed or heading, which
        // rode the first mock fixes of the next link until it spoke
        isArmed = false
        gotArmedState = false
        lastGPSAltitude = Float.NaN
        lastRelativeAltitude = Float.NaN
        lastSpeed = 0f
        lastHeading = 0f
        lastFlyModeHeading = false
        lastFlyModeFirst = null
        lastFlyModeSecond = null
        phoneWatcher.refresh(wantedByLink)
        mockPublisher.refresh(wantedByLink)
        retired.logger?.onDisconnected()
        retired.poller?.disconnect()
        if (retired.poller != null || retired.logger != null) stopForeground(true)
        return ConnectionListener(retired.generation)
    }

    /**
     * Every poller posts its terminal callbacks to the main thread — the
     * chassis's contract — so none can retire its own generation before its
     * constructor returns, and connect() runs here on the main thread with
     * nothing between retire and install. A generation check here guarded a
     * synchronous-failure design the pollers have left behind.
     */
    private fun installPoller(poller: DataPoller) {
        synchronized(connectionLock) {
            dataPoller = poller
        }
    }

    override fun onCreate() {
        super.onCreate()

        preferenceManager = PreferenceManager(this)
        // the ears change — the screen unbinds on rotation, the logger is
        // born and retired with each connection — so they are read live
        bus.next = MulticastListener({ dataListener }, { logListener })
        mockPublisher = MockPublisher(this, preferenceManager) { refreshNotification() }
        phoneWatcher = PhoneWatcher(
            this, preferenceManager,
            // Our own mock coming straight back: while the drone's GPS is
            // being republished, the phone's GPS provider answers with the
            // drone. Believed, it would draw the operator arrow on the model
            // and write the drone into the CSV's operator columns.
            refuseFix = { mockPublisher.active && it.isFromMockProvider },
            onFix = ::recordPhoneFix,
            onHeading = ::recordPhoneHeading
        )
        preferenceManager.watch(settingsChanged)
        // the mock app-op and its Developer-options picker exist since M;
        // before that there is nothing to watch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
                .startWatchingMode(
                    AppOpsManager.OPSTR_MOCK_LOCATION, packageName, mockChoiceChanged
                )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel =
                NotificationChannel("bt_channel", "Bluetooth", importance)
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Built at each showing rather than once: while the drone's GPS is being
     * republished as the phone's own, the one always-visible surface this app
     * has — the tracker app is the one on screen — should say so.
     */
    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "bt_channel")
            .setContentText(
                if (mockPublisher.active)
                    "Drone GPS is being published as this phone's location"
                else
                    "Telemetry service is running. To stop - disconnect and close the app"
            )
            .setContentTitle("Telemetry service is running")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    -1,
                    Intent(this, MapsActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private fun refreshNotification() {
        if (!wantedByLink) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        return Service.START_REDELIVER_INTENT
    }

    inner class DataBinder : Binder() {
        fun getService(): DataService = this@DataService
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    fun connect(device: BluetoothDevice, isBle: Boolean, newSession: Boolean = true) {
        val listener = listenerForNewConnection()
        try {
            // The classic RFCOMM socket is made before the log is opened: it is
            // the one step here that throws, and opening the recording first
            // left an empty .tlm and a leaked stream behind every failed
            // connect. Once a poller exists it owns closing the log; nothing
            // does in the gap before there is one.
            val socket = if (!isBle)
                device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            else null

            val append = beginLogSession(newSession)
            val logFile = createLogFile(append)
            createLogger(append)

            val poller = if (!isBle) {
                BluetoothDataPoller(socket!!, listener, logFile)
            } else {
                BluetoothLeDataPoller(this, device, listener, logFile)
            }
            installPoller(poller)
        } catch (e: IOException) {
            listener.onConnectionFailed()
            Toast.makeText(this, "Failed to connect to bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The name this flight's files share, without an extension.
     *
     * The recording, the CSV beside it and the note of when it started are all
     * matched to each other by name — that is how renaming or deleting one
     * takes the others with it — so the name is made once, here, and they are
     * all given it. Made twice it came out a second or two apart, and they were
     * no longer the same flight as far as anything else was concerned.
     */
    private var logName: String? = null

    /**
     * The recording being written, for the CSV to say how far through it each
     * of its rows was written — which is the only thing that lines the two up
     * when the link goes quiet.
     */
    @Volatile private var recording: CountingLog? = null

    /**
     * Whether the flight going on — or the one that has just ended — reached a
     * file. The setting says a recording was wanted; a full card, a volume
     * that has gone, or a link brought up without the storage permission all
     * leave the setting on and nothing written. Anything about to throw the
     * flight away on the grounds that the replay has it must ask this.
     */
    fun isRecording(): Boolean = recording != null

    /**
     * Names this connection's log, or keeps the last name for a reconnect to
     * continue. A fresh connect (newSession) starts a new session — a new name,
     * new files; a reconnect keeps the name a drop left standing, so the
     * recording and CSV re-open for append and one flight stays one log, not
     * two. The name is chosen here whatever the logging settings, so the .tlm
     * and the CSV beside it always share it. Returns whether this connection
     * appends to a log the last one left.
     */
    // internal rather than private only so the "one flight, one log" contract
    // can be tested directly — see DataServiceLogSessionTest. Called nowhere
    // but the connect() overloads.
    internal fun beginLogSession(newSession: Boolean): Boolean {
        if (newSession) logName = null
        val append = logName != null
        if (!append) {
            logName = SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(Date())
        }
        return append
    }

    // internal, like beginLogSession, only so the log-file gates (logging off,
    // storage permission denied) and the append can be tested — see
    // DataServiceLogFileTest. Called only by the connect() overloads.
    internal fun createLogFile(append: Boolean): FileOutputStream? {
        // The old stream was closed where the link went; a new — or appended —
        // one opens here.
        recording = null
        val name = logName ?: return null
        if (!preferenceManager.isLoggingEnabled()
            || ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
            dir.mkdirs()
            val file = File(dir, "$name.tlm")
            // Append only onto a file that already stands: a reconnect continues
            // its recording, but a session whose first link opened none (the
            // setting was off, then on) starts it here.
            val counted = CountingLog(file, append && file.exists())
            recording = counted
            counted
        } catch (e: Exception) {
            // Storage is optional. A full, missing, or revoked volume must not
            // turn an otherwise healthy telemetry link into a failure.
            recording = null
            Toast.makeText(this, "Failed to open the telemetry log", Toast.LENGTH_LONG).show()
            null
        }
    }

    /**
     * The phone's own position and its republishing live in modules of their
     * own — PhoneWatcher hears and arbitrates, MockPublisher speaks. The
     * service keeps the one fact both hang on, whether a link is up, and the
     * policy of what gets written down.
     */
    private lateinit var mockPublisher: MockPublisher
    private lateinit var phoneWatcher: PhoneWatcher

    fun watchPhone(
        fixListener: ((Location) -> Unit)?,
        headingListener: ((Float) -> Unit)?
    ) {
        phoneWatcher.watch(fixListener, headingListener)
    }

    private fun recordPhoneFix(location: Location) {
        if (!preferenceManager.isMyPositionLoggingEnabled()) {
            // Turned off while a flight is being recorded: the rows that
            // follow say nothing, rather than repeating the last place they
            // were told about for the rest of the flight. The screen is
            // still told — the setting is about what is written down, not
            // about what is drawn.
            logListener?.setMyPosition(Double.NaN, Double.NaN, Float.NaN)
            return
        }
        logListener?.setMyPosition(
            location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy else Float.NaN
        )
    }

    private fun recordPhoneHeading(heading: Float) {
        logListener?.setMyHeading(
            if (preferenceManager.isMyPositionLoggingEnabled()) heading else Float.NaN
        )
    }

    /** The link's last word on everything a mock fix carries. */
    private fun heardNow() = MockPublisher.Heard(
        hasFix = hasGPSFix,
        gpsAltitude = lastGPSAltitude,
        relativeAltitude = lastRelativeAltitude,
        speedKmh = lastSpeed,
        heading = lastHeading,
        knownDisarmed = gotArmedState && !isArmed
    )

    fun connect(serialPort: UsbSerialPort, connection: UsbDeviceConnection,
                newSession: Boolean = true) {
        val listener = listenerForNewConnection()
        val append = beginLogSession(newSession)
        val logFile = createLogFile(append)
        createLogger(append)
        val poller = UsbDataPoller(
            listener,
            serialPort,
            preferenceManager.getUsbSerialBaudrate(),
            connection,
            logFile
        )
        installPoller(poller)
    }

    /**
     * Telemetry over the network: a TCP server such as a TBS Crossfire WiFi
     * module, or a UDP sender such as an ExpressLRS backpack.
     *
     * Nothing touches the network here — the poller does all of it on its own
     * thread, because this runs on the UI thread and a socket call would throw
     * NetworkOnMainThreadException.
     */
    fun connect(host: String, port: Int, mode: Int, highLatency: Boolean = false,
                newSession: Boolean = true) {
        val listener = listenerForNewConnection()

        // A log that cannot be opened is worth saying so about, and worth
        // connecting anyway. It used to abandon the connection instead, which
        // left the button on "Connecting…" with nothing on its way to clear it,
        // because no poller was ever created to report a failure.
        val append = beginLogSession(newSession)
        val logFile = createLogFile(append)
        createLogger(append)

        // The binder pins each socket to the network that routes to its
        // target — see NetworkBinder — and holds the multicast lock, which is
        // what stops Wi-Fi power saving from quietly dropping broadcast
        // telemetry: an ExpressLRS backpack and a TBS module in UDP mode
        // both broadcast.
        val binder = NetworkBinder(this)
        binder.acquire()

        val poller = NetworkDataPoller(
            mode,
            host,
            port,
            listener,
            logFile,
            binder,
            highLatency
        )
        installPoller(poller)
    }

    private fun createLogger(append: Boolean) {
        // Always replaced, never left behind. A logger from a previous
        // connection has had its timer cancelled, and starting it again throws
        // "Timer already cancelled" the moment the next link comes up.
        logListener = if (preferenceManager.isCSVLoggingEnabled()
            && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                OtxCsvLogger(logName, append) { recording?.bytesWritten ?: 0L }
            } catch (e: Exception) {
                // CSV is a companion recording, never a prerequisite for the
                // telemetry connection itself.
                Toast.makeText(this, "Failed to open the CSV log", Toast.LENGTH_LONG).show()
                null
            }
        } else {
            null
        }
    }

    fun setDataListener(dataListener: DataDecoder.Listener?) {
        this.dataListener = dataListener
        if (dataListener != null) {
            dataListener.onGPSState(satellites, hasGPSFix)
            // and what the link turned out to speak. Detection says it once
            // per connection and never again, so a screen built after that —
            // a phone turned round mid-flight — had no way to learn it and
            // showed nothing for the rest of the link.
            protocolName?.let { dataListener.onProtocolDetected(it) }
            // and whether the model is armed, for the same reason: the
            // rebuilt screen's disarmed-height gate stood open until the
            // next fly-mode frame happened along
            if (gotArmedState) {
                dataListener.onFlyModeData(
                    isArmed, lastFlyModeHeading, lastFlyModeFirst, lastFlyModeSecond)
            }
        } else {
            if (!isConnected()) {
                stopSelf()
            }
        }
    }

    fun isConnected(): Boolean {
        return dataPoller != null
    }

    override fun onBind(intent: Intent): IBinder? {
        return dataBinder
    }

    override fun onDestroy() {
        super.onDestroy()
        val retired = retireCurrentConnection()
        mockPublisher.refresh(false)
        preferenceManager.unwatch(settingsChanged)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
                .stopWatchingMode(mockChoiceChanged)
        }
        phoneWatcher.shutdown()
        retired.logger?.onDisconnected()
        retired.poller?.disconnect()
    }

    override fun onConnectionFailed() {
        val retired = retireCurrentConnection()
        phoneWatcher.refresh(wantedByLink)
        mockPublisher.refresh(wantedByLink)
        dataListener?.onConnectionFailed()
        retired.logger?.onConnectionFailed()
        retired.poller?.disconnect()
        stopForeground(true)
    }

    override fun onConnected() {
        // From here the phone's own position is worth writing down, and this
        // goes on hearing it while the screen is away.
        wantedByLink = true
        phoneWatcher.refresh(true)
        mockPublisher.refresh(true)
        bus.onConnected()

        startForeground(1, buildNotification())
    }

    override fun onGPSData(latitude: Double, longitude: Double) {
        // gathered only when someone is listening: this runs per fix for the
        // whole flight, and publish() keeps its own check for the race
        if (mockPublisher.active) mockPublisher.publish(latitude, longitude, heardNow())
        bus.onGPSData(latitude, longitude)
    }

    /**
     * Nothing to keep here: there is one record of the flight and it is not
     * this one.
     *
     * There were two, filled by different paths — a replay reached one, a link
     * with no height reached the other — so whichever was consulted was
     * sometimes the empty one, and a map came up bare beside a view showing
     * the flight.
     */
    override fun onGPSData(list: List<Position>, addToEnd: Boolean) {
    }

    override fun onHeadingData(heading: Float) {
        lastHeading = heading
        bus.onHeadingData(heading)
    }

    /** What this connection speaks, for a screen that arrives after the news. */
    @Volatile private var protocolName: String? = null

    override fun onProtocolDetected( protocolName: String) {
        this.protocolName = protocolName
        bus.onProtocolDetected(protocolName)
    }

    override fun onDisconnected() {
        val retired = retireCurrentConnection()
        phoneWatcher.refresh(wantedByLink)
        // the phone's real position comes back the moment the link ends
        mockPublisher.refresh(wantedByLink)
        dataListener?.onDisconnected()
        retired.logger?.onDisconnected()
        retired.poller?.disconnect()
        stopForeground(true)
    }

    override fun onGPSState(satellites: Int, gpsFix: Boolean) {
        this.satellites = satellites
        hasGPSFix = gpsFix
        bus.onGPSState(satellites, gpsFix)
    }

    override fun onAltitudeData(altitude: Float) {
        lastRelativeAltitude = altitude
        bus.onAltitudeData(altitude)
    }

    override fun onGPSAltitudeData(altitude: Float) {
        lastGPSAltitude = altitude
        bus.onGPSAltitudeData(altitude)
    }

    override fun onGSpeedData(speed: Float) {
        lastSpeed = speed
        bus.onGSpeedData(speed)
    }

    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) {
        isArmed = armed
        gotArmedState = true
        lastFlyModeHeading = heading
        lastFlyModeFirst = firstFlightMode
        lastFlyModeSecond = secondFlightMode
        bus.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
    }

    fun disconnect() {
        onDisconnected()
    }
}
