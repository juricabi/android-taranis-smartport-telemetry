package juricabi.com.telemetry.service

import android.Manifest
import android.annotation.TargetApi
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import juricabi.com.telemetry.protocol.pollers.DataPoller
import juricabi.com.telemetry.protocol.pollers.NetworkDataPoller
import juricabi.com.telemetry.protocol.pollers.UsbDataPoller
import juricabi.com.telemetry.utils.WifiNetworkBinder
import juricabi.com.telemetry.ui.MapsActivity
import java.io.File
import juricabi.com.telemetry.logger.CountingLog
import java.io.FileOutputStream
import java.io.IOException
import java.lang.NullPointerException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

class DataService : Service(), DataDecoder.Listener {

    private var dataPoller: DataPoller? = null
    private var dataListener: DataDecoder.Listener? = null
    private var logListener: OtxCsvLogger? = null
    private val dataBinder = DataBinder()
    private var hasGPSFix = false
    private var isArmed = false
    private var satellites = 0
    private var lastLatitude: Double = 0.0
    private var lastLongitude: Double = 0.0
    private var lastAltitude: Float = 0.0f
    private var lastSpeed: Float = 0.0f
    private var lastHeading: Float = 0.0f
    // NaN until a link says one: the mock fix carries an altitude only once
    // the drone has reported its own, rather than a made-up sea level
    private var lastGPSAltitude: Float = Float.NaN
    private lateinit var preferenceManager: PreferenceManager
    private var settingsPreferences: SharedPreferences? = null

    private val settingsChanged =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "background_compass" -> updatePhoneListening()
                // flipped mid-flight it takes effect now, not on the next link
                "mock_location_enabled" -> updateMockProvider()
            }
        }

    /**
     * A poller may finish on another thread after its replacement is already
     * running. Its callbacks must not be allowed to clear or write into that
     * replacement. Every poller therefore gets a listener tied to the
     * generation in which it was created.
     */
    private val connectionLock = Any()
    private var connectionGeneration = 0L

    private inner class ConnectionListener(val generation: Long) : DataDecoder.Listener {
        private inline fun ifCurrent(block: () -> Unit) {
            synchronized(connectionLock) {
                if (generation == connectionGeneration) block()
            }
        }

        override fun onConnectionFailed() = ifCurrent { this@DataService.onConnectionFailed() }
        override fun onFuelData(fuel: Int) = ifCurrent { this@DataService.onFuelData(fuel) }
        override fun onConnected() = ifCurrent { this@DataService.onConnected() }
        override fun onGPSData(latitude: Double, longitude: Double) =
            ifCurrent { this@DataService.onGPSData(latitude, longitude) }
        override fun onGPSData(list: List<Position>, addToEnd: Boolean) =
            ifCurrent { this@DataService.onGPSData(list, addToEnd) }
        override fun onVBATData(voltage: Float) = ifCurrent { this@DataService.onVBATData(voltage) }
        override fun onCellVoltageData(voltage: Float) =
            ifCurrent { this@DataService.onCellVoltageData(voltage) }
        override fun onCurrentData(current: Float) =
            ifCurrent { this@DataService.onCurrentData(current) }
        override fun onHeadingData(heading: Float) =
            ifCurrent { this@DataService.onHeadingData(heading) }
        override fun onRSSIData(rssi: Int) = ifCurrent { this@DataService.onRSSIData(rssi) }
        override fun onUpLqData(lq: Int) = ifCurrent { this@DataService.onUpLqData(lq) }
        override fun onDnLqData(lq: Int) = ifCurrent { this@DataService.onDnLqData(lq) }
        override fun onElrsModeModeData(mode: Int) =
            ifCurrent { this@DataService.onElrsModeModeData(mode) }
        override fun onDisconnected() = ifCurrent { this@DataService.onDisconnected() }
        override fun onGPSState(satellites: Int, gpsFix: Boolean) =
            ifCurrent { this@DataService.onGPSState(satellites, gpsFix) }
        override fun onVSpeedData(vspeed: Float) =
            ifCurrent { this@DataService.onVSpeedData(vspeed) }
        override fun onThrottleData(throttle: Int) =
            ifCurrent { this@DataService.onThrottleData(throttle) }
        override fun onAltitudeData(altitude: Float) =
            ifCurrent { this@DataService.onAltitudeData(altitude) }
        override fun onGPSAltitudeData(altitude: Float) =
            ifCurrent { this@DataService.onGPSAltitudeData(altitude) }
        override fun onDistanceData(distance: Int) =
            ifCurrent { this@DataService.onDistanceData(distance) }
        override fun onRollData(rollAngle: Float) =
            ifCurrent { this@DataService.onRollData(rollAngle) }
        override fun onPitchData(pitchAngle: Float) =
            ifCurrent { this@DataService.onPitchData(pitchAngle) }
        override fun onGSpeedData(speed: Float) =
            ifCurrent { this@DataService.onGSpeedData(speed) }
        override fun onFlyModeData(
            armed: Boolean,
            heading: Boolean,
            firstFlightMode: DataDecoder.Companion.FlyMode?,
            secondFlightMode: DataDecoder.Companion.FlyMode?
        ) = ifCurrent {
            this@DataService.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
        }
        override fun onAirSpeedData(speed: Float) =
            ifCurrent { this@DataService.onAirSpeedData(speed) }
        override fun onRCChannels(rcChannels: IntArray) =
            ifCurrent { this@DataService.onRCChannels(rcChannels) }
        override fun onStatusText(message: String) =
            ifCurrent { this@DataService.onStatusText(message) }
        override fun onDNSNRData(snr: Int) = ifCurrent { this@DataService.onDNSNRData(snr) }
        override fun onUPSNRData(snr: Int) = ifCurrent { this@DataService.onUPSNRData(snr) }
        override fun onAntData(activeAntena: Int) =
            ifCurrent { this@DataService.onAntData(activeAntena) }
        override fun onPowerData(power: Int) = ifCurrent { this@DataService.onPowerData(power) }
        override fun onRssiDbm1Data(rssi: Int) =
            ifCurrent { this@DataService.onRssiDbm1Data(rssi) }
        override fun onRssiDbm2Data(rssi: Int) =
            ifCurrent { this@DataService.onRssiDbm2Data(rssi) }
        override fun onRssiDbmdData(rssi: Int) =
            ifCurrent { this@DataService.onRssiDbmdData(rssi) }
        override fun onVBATOrCellData(voltage: Float) =
            ifCurrent { this@DataService.onVBATOrCellData(voltage) }
        override fun onTelemetryByte() = ifCurrent { this@DataService.onTelemetryByte() }
        override fun onSuccessDecode() = ifCurrent { this@DataService.onSuccessDecode() }
        override fun onDecoderRestart() = ifCurrent { this@DataService.onDecoderRestart() }
        override fun onProtocolDetected(protocolName: String) =
            ifCurrent { this@DataService.onProtocolDetected(protocolName) }
        override fun onDeviceName(name: String) = ifCurrent { this@DataService.onDeviceName(name) }
        override fun commit() = ifCurrent { this@DataService.commit() }
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
        updatePhoneListening()
        updateMockProvider()
        retired.logger?.onDisconnected()
        retired.poller?.disconnect()
        if (retired.poller != null || retired.logger != null) stopForeground(true)
        return ConnectionListener(retired.generation)
    }

    /**
     * Poller constructors start their work immediately. A synchronous failure
     * can therefore arrive before the constructor returns; never install that
     * poller after its generation has already been retired.
     */
    private fun installPoller(listener: ConnectionListener, poller: DataPoller) {
        val keep = synchronized(connectionLock) {
            if (listener.generation == connectionGeneration) {
                dataPoller = poller
                true
            } else {
                false
            }
        }
        if (!keep) poller.disconnect()
    }

    override fun onCreate() {
        super.onCreate()

        preferenceManager = PreferenceManager(this)
        settingsPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(settingsChanged)
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
                if (mockActive)
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
            installPoller(listener, poller)
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
     * Where the phone is, heard by the thing that outlives the screen.
     *
     * The recording goes on while the app is in somebody's pocket — that is
     * what the notification is for — and the operator's own position is half of
     * what a replay puts back. Heard on the screen alone, it stopped the moment
     * the screen went away, which is most of a flight.
     *
     * The compass belongs here too. If the screen owns it, locking the phone
     * leaves a position in every CSV row but a heading in almost none of them,
     * and the recorded navigation marker disappears during replay.
     */
    private var phoneFix: Location? = null
    private var phoneHeading = Float.NaN

    /**
     * Who is listening, and why.
     *
     * The recording wants the phone's position for as long as a link is up,
     * screen or no screen. The screen wants it for as long as it is drawing,
     * link or no link — a map with nothing flying still shows where you are.
     * Either is reason enough to listen and neither alone is reason to stop.
     */
    private var wantedByLink = false
    private var wantedByScreen = false
    private var listening = false
    private var compassListening = false

    /** The screen, while it is drawing. Null when it goes away. */
    private var phoneFixListener: ((Location) -> Unit)? = null
    private var phoneHeadingListener: ((Float) -> Unit)? = null

    fun watchPhone(
        fixListener: ((Location) -> Unit)?,
        headingListener: ((Float) -> Unit)?
    ) {
        phoneFixListener = fixListener
        phoneHeadingListener = headingListener
        wantedByScreen = fixListener != null || headingListener != null
        updatePhoneListening()
        // whatever is known already, so a screen coming back does not wait for
        // the next fix or compass sample to draw its marker
        if (fixListener != null) phoneFix?.let { fixListener(it) }
        headingListener?.invoke(phoneHeading)
    }

    private fun updatePhoneListening() {
        if (wantedByLink || wantedByScreen) listenForPhone() else stopListeningForPhone()

        val wantCompass = wantedByScreen ||
            (wantedByLink && preferenceManager.isBackgroundCompassEnabled())
        if (wantCompass) listenForPhoneCompass() else stopListeningForPhoneCompass()
        updateCompassWakeLock()
    }

    private val compassHandler = Handler(Looper.getMainLooper())
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
            val rotation = FloatArray(9)
            if (!SensorManager.getRotationMatrix(
                    rotation, null, phoneGravity, phoneGeomagnetic
                )
            ) return
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotation, orientation)
            var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (degrees < 0f) degrees += 360f
            publishPhoneHeading(degrees)
        }
    }

    /** A fifth of the way to each reading: a compass on its own jitters. */
    private fun settle(held: FloatArray, fresh: FloatArray, had: Boolean) {
        for (i in held.indices) {
            held[i] = if (had) held[i] + (fresh[i] - held[i]) * 0.2f else fresh[i]
        }
    }

    private fun publishPhoneHeading(heading: Float) {
        phoneHeading = heading
        logListener?.setMyHeading(
            if (preferenceManager.isMyPositionLoggingEnabled()) heading else Float.NaN
        )
        phoneHeadingListener?.invoke(heading)
    }

    private val phoneLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Our own mock coming straight back: while the drone's GPS is
            // being republished, the phone's GPS provider answers with the
            // drone. Believed, it would draw the operator arrow on the model
            // and write the drone into the CSV's operator columns. Dropped,
            // the operator keeps the network provider's coarser answer for
            // the duration.
            if (mockActive && location.isFromMockProvider) return
            if (!worthBelieving(location)) return
            phoneFix = location
            if (!preferenceManager.isMyPositionLoggingEnabled()) {
                // Turned off while a flight is being recorded: the rows that
                // follow say nothing, rather than repeating the last place they
                // were told about for the rest of the flight. The screen is
                // still told — the setting is about what is written down, not
                // about what is drawn.
                logListener?.setMyPosition(Double.NaN, Double.NaN, Float.NaN)
                phoneFixListener?.invoke(location)
                return
            }
            logListener?.setMyPosition(
                location.latitude, location.longitude,
                if (location.hasAccuracy()) location.accuracy else Float.NaN
            )
            phoneFixListener?.invoke(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * A mast answers at once and puts you hundreds of metres from where the
     * satellites say. Anything while nothing is known, anything once what is
     * known has gone stale, and otherwise only a fix at least as good.
     */
    private fun worthBelieving(fix: Location): Boolean {
        val held = phoneFix ?: return true
        if (System.currentTimeMillis() - held.time > 20000L) return true
        val newer = fix.time - held.time
        if (newer > 20000L) return true
        if (newer < -20000L) return false
        if (!fix.hasAccuracy()) return !held.hasAccuracy()
        if (!held.hasAccuracy()) return true
        return fix.accuracy <= held.accuracy || fix.provider == held.provider
    }

    private fun listenForPhone() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        listening = true
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        for (provider in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.requestLocationUpdates(provider, 1000L, 0f, phoneLocationListener)
            } catch (e: Exception) {
                // a phone without that provider; the other one still runs
            }
        }
    }

    private fun stopListeningForPhone() {
        if (!listening) return
        listening = false
        // The fix is kept. It does not stop being true because nobody is
        // listening — and thrown away here, a screen coming back from the
        // settings drew the system's stale answer and waited seconds for the
        // satellites, over a fix this service had held moments before.
        // worthBelieving already ages it: past twenty seconds anything
        // fresher wins, so keeping it cannot pin the arrow to the past.
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager)
                .removeUpdates(phoneLocationListener)
        } catch (e: Exception) {
            // never started, or already gone
        }
    }

    /**
     * Which way the phone is facing, owned by the same foreground service that
     * records its position.
     *
     * Supplying a Handler pins callbacks to the main looper even when a poller
     * reports its connection from a worker thread.
     */
    private fun listenForPhoneCompass() {
        if (compassListening) return
        val sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetic = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (accelerometer == null || magnetic == null) {
            publishPhoneHeading(Float.NaN)
            return
        }

        hasPhoneGravity = false
        hasPhoneGeomagnetic = false
        val registered = try {
            val gravity = sensors.registerListener(
                phoneCompass, accelerometer, SensorManager.SENSOR_DELAY_UI, compassHandler
            )
            val geomagnetic = sensors.registerListener(
                phoneCompass, magnetic, SensorManager.SENSOR_DELAY_UI, compassHandler
            )
            gravity && geomagnetic
        } catch (failure: RuntimeException) {
            false
        }
        if (!registered) {
            sensors.unregisterListener(phoneCompass)
            publishPhoneHeading(Float.NaN)
            return
        }
        compassListening = true
    }

    private fun stopListeningForPhoneCompass() {
        if (compassListening) {
            (getSystemService(SENSOR_SERVICE) as SensorManager)
                .unregisterListener(phoneCompass)
        }
        compassListening = false
        hasPhoneGravity = false
        hasPhoneGeomagnetic = false
        phoneHeading = Float.NaN
        // Blank subsequent CSV rows instead of repeating the last direction
        // forever after sampling was deliberately stopped.
        logListener?.setMyHeading(Float.NaN)
        phoneHeadingListener?.invoke(Float.NaN)
    }

    private var compassWakeLock: PowerManager.WakeLock? = null

    /**
     * Non-wakeup compass sensors are allowed to sleep with the display. The
     * lock exists only for an active telemetry link in the background and is
     * released on resume, disconnect, preference change and service teardown.
     */
    private fun updateCompassWakeLock() {
        val shouldHold = compassListening && wantedByLink && !wantedByScreen &&
            preferenceManager.isBackgroundCompassEnabled()
        if (!shouldHold) {
            releaseCompassWakeLock()
            return
        }
        val lock = compassWakeLock ?: (
            getSystemService(POWER_SERVICE) as PowerManager
        ).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:background-compass"
        ).also {
            it.setReferenceCounted(false)
            compassWakeLock = it
        }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseCompassWakeLock() {
        compassWakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    /**
     * The listening above in reverse: the drone's GPS republished as the
     * phone's own position, for a tracker app on this phone — Overland,
     * PureTrack — to broadcast the drone instead of the phone.
     *
     * Opt-in, and plain AOSP: the fixes go to Android's test-provider API
     * under the real GPS provider's name, which is the one name every tracker
     * listens to, with or without Play services. The only grant is being
     * picked under Developer options → Select mock location app — that is
     * what addTestProvider checks.
     *
     * Live only by construction: a replay never reaches this service, and
     * yesterday's flight must not become today's position. Volatile because
     * the provider is installed and removed on the main thread while the
     * fixes are published from the poller's.
     */
    @Volatile private var mockActive = false

    private fun updateMockProvider() {
        val wanted = wantedByLink && preferenceManager.isMockLocationEnabled()
        if (wanted == mockActive) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (wanted) {
            try {
                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, true, false, false, true, true, true,
                    Criteria.POWER_HIGH, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                mockActive = true
            } catch (e: SecurityException) {
                // Not the chosen mock location app. Said once, at the seam
                // where it fails; the settings screen shows the same state
                // and where to fix it.
                Toast.makeText(
                    this,
                    "Drone GPS is not published: pick this app under " +
                        "Developer options, Select mock location app",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            try {
                lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) {
                // the choice of mock app was revoked first and took the
                // provider with it
            }
            mockActive = false
        }
        refreshNotification()
    }

    /** On the poller's thread, like every other decode callback. */
    private fun publishMockFix(latitude: Double, longitude: Double) {
        if (!mockActive) return
        // the map's own bar for a believable position: a fix, and not 0,0,
        // which is a receiver still warming up
        if (!hasGPSFix || (latitude == 0.0 && longitude == 0.0)) return
        val fix = Location(LocationManager.GPS_PROVIDER)
        fix.latitude = latitude
        fix.longitude = longitude
        fix.time = System.currentTimeMillis()
        fix.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        // The link does not say how good the drone's fix is, and a Location
        // without an accuracy is refused as incomplete. Five metres is an
        // ordinary satellite answer, inside the filters tracker apps apply.
        fix.accuracy = 5f
        if (!lastGPSAltitude.isNaN()) fix.altitude = lastGPSAltitude.toDouble()
        fix.speed = lastSpeed / 3.6f // decoders deliver km/h; Location wants m/s
        fix.bearing = lastHeading
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager)
                .setTestProviderLocation(LocationManager.GPS_PROVIDER, fix)
        } catch (e: Exception) {
            // Unselected as the mock app mid-flight. Every later fix would
            // throw the same way, so stop claiming to publish rather than
            // crash the poller's thread.
            mockActive = false
            refreshNotification()
        }
    }

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
        installPoller(listener, poller)
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

        // Pinning to Wi-Fi and holding the multicast lock: without these a
        // transmitter's own access point, which has no internet, loses to
        // mobile data and the broadcast never arrives.
        // Always taken, whatever network was chosen: the multicast lock is
        // what stops Wi-Fi power saving from quietly dropping broadcast
        // telemetry, and an ExpressLRS backpack and a TBS module in UDP
        // mode both broadcast. Only the socket *pinning* is conditional —
        // forcing Wi-Fi is wrong when the module is a client of this
        // phone's own hotspot.
        val binder = WifiNetworkBinder(this)
        binder.acquire(preferenceManager.getNetworkPinWifi())

        val poller = NetworkDataPoller(
            mode,
            host,
            port,
            listener,
            logFile,
            binder,
            highLatency
        )
        installPoller(listener, poller)
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
        updateMockProvider()
        wantedByScreen = false
        phoneFixListener = null
        phoneHeadingListener = null
        settingsPreferences?.unregisterOnSharedPreferenceChangeListener(settingsChanged)
        settingsPreferences = null
        stopListeningForPhone()
        stopListeningForPhoneCompass()
        releaseCompassWakeLock()
        retired.logger?.onDisconnected()
        retired.poller?.disconnect()
    }

    override fun onConnectionFailed() {
        val retired = retireCurrentConnection()
        updatePhoneListening()
        updateMockProvider()
        dataListener?.onConnectionFailed()
        retired.logger?.onConnectionFailed()
        retired.poller?.disconnect()
        stopForeground(true)
    }

    override fun onFuelData(fuel: Int) {
        dataListener?.onFuelData(fuel)
        logListener?.onFuelData(fuel)
    }

    override fun onConnected() {
        // From here the phone's own position is worth writing down, and this
        // goes on hearing it while the screen is away.
        wantedByLink = true
        updatePhoneListening()
        updateMockProvider()
        dataListener?.onConnected()
        logListener?.onConnected()

        startForeground(1, buildNotification())
    }

    override fun onGPSData(latitude: Double, longitude: Double) {
        lastLatitude = latitude
        lastLongitude = longitude
        publishMockFix(latitude, longitude)
        dataListener?.onGPSData(latitude, longitude)
        logListener?.onGPSData(latitude, longitude)
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

    override fun onVBATData(voltage: Float) {
        dataListener?.onVBATData(voltage)
        logListener?.onVBATData(voltage)
    }

    override fun onCellVoltageData(voltage: Float) {
        dataListener?.onCellVoltageData(voltage)
        logListener?.onCellVoltageData(voltage)
    }

    override fun onVBATOrCellData(voltage: Float) {
        dataListener?.onVBATOrCellData(voltage)
        logListener?.onVBATOrCellData(voltage)
    }

    override fun onCurrentData(current: Float) {
        dataListener?.onCurrentData(current)
        logListener?.onCurrentData(current)
    }

    override fun onHeadingData(heading: Float) {
        lastHeading = heading
        dataListener?.onHeadingData(heading)
        logListener?.onHeadingData(heading)
    }

    override fun onAirSpeedData(speed: Float) {
        dataListener?.onAirSpeedData(speed)
        logListener?.onAirSpeedData(speed)
    }

    override fun onTelemetryByte() {
        dataListener?.onTelemetryByte()
        logListener?.onTelemetryByte()
    }

    override fun onSuccessDecode() {
        dataListener?.onSuccessDecode()
        logListener?.onSuccessDecode()
    }

    override fun onDecoderRestart() {
        dataListener?.onDecoderRestart()
        logListener?.onDecoderRestart()
    }

    /** What this connection speaks, for a screen that arrives after the news. */
    @Volatile private var protocolName: String? = null

    override fun onProtocolDetected( protocolName: String) {
        this.protocolName = protocolName
        dataListener?.onProtocolDetected(protocolName)
    }

    /**
     * Everything the decoder produces passes through this service on its way to
     * the screen, so a callback with a default body in the interface is a
     * callback that stops here. This one has to be forwarded like the rest, or
     * the map never learns which radio system is sending and shows an
     * ExpressLRS rate for a Crossfire.
     */
    override fun onDeviceName(name: String) {
        dataListener?.onDeviceName(name)
    }

    override fun commit() {
        dataListener?.commit()
    }

    override fun onRSSIData(rssi: Int) {
        dataListener?.onRSSIData(rssi)
        logListener?.onRSSIData(rssi)
    }

    override fun onUpLqData(lq: Int) {
        dataListener?.onUpLqData(lq)
        logListener?.onUpLqData(lq)
    }

    override fun onDnLqData(lq: Int) {
        dataListener?.onDnLqData(lq)
        logListener?.onDnLqData(lq)
    }

    override fun onElrsModeModeData(mode: Int) {
        dataListener?.onElrsModeModeData(mode)
        logListener?.onElrsModeModeData(mode)
    }

    override fun onDisconnected() {
        val retired = retireCurrentConnection()
        updatePhoneListening()
        // the phone's real position comes back the moment the link ends
        updateMockProvider()
        dataListener?.onDisconnected()
        retired.logger?.onDisconnected()
        retired.poller?.disconnect()
        stopForeground(true)
    }

    override fun onGPSState(satellites: Int, gpsFix: Boolean) {
        this.satellites = satellites
        hasGPSFix = gpsFix
        dataListener?.onGPSState(satellites, gpsFix)
        logListener?.onGPSState(satellites, gpsFix)
    }

    override fun onVSpeedData(vspeed: Float) {
        dataListener?.onVSpeedData(vspeed)
        logListener?.onVSpeedData(vspeed)
    }

    override fun onThrottleData(throttle: Int) {
        dataListener?.onThrottleData(throttle)
        logListener?.onThrottleData(throttle)
    }

    override fun onAltitudeData(altitude: Float) {
        lastAltitude = altitude
        dataListener?.onAltitudeData(altitude)
        logListener?.onAltitudeData(altitude)
    }

    override fun onGPSAltitudeData(altitude: Float) {
        lastGPSAltitude = altitude
        dataListener?.onGPSAltitudeData(altitude)
        logListener?.onGPSAltitudeData(altitude)
    }

    override fun onDistanceData(distance: Int) {
        dataListener?.onDistanceData(distance)
        logListener?.onDistanceData(distance)
    }

    override fun onRollData(rollAngle: Float) {
        dataListener?.onRollData(rollAngle)
        logListener?.onRollData(rollAngle)
    }

    override fun onPitchData(pitchAngle: Float) {
        dataListener?.onPitchData(pitchAngle)
        logListener?.onPitchData(pitchAngle)
    }

    override fun onGSpeedData(speed: Float) {
        lastSpeed = speed
        dataListener?.onGSpeedData(speed)
        logListener?.onGSpeedData(speed)
    }

    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) {
        isArmed = armed
        dataListener?.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
        logListener?.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
    }

    override fun onRCChannels(rcChannels: IntArray) {
        dataListener?.onRCChannels(rcChannels)
        logListener?.onRCChannels(rcChannels)
    }

    override fun onStatusText(message: String) {
        dataListener?.onStatusText(message)
        logListener?.onStatusText(message)
    }

    override fun onDNSNRData(snr: Int) {
        dataListener?.onDNSNRData(snr)
        logListener?.onDNSNRData(snr)
    }

    override fun onUPSNRData(snr: Int) {
        dataListener?.onUPSNRData(snr)
        logListener?.onUPSNRData(snr)
    }

    override fun onAntData(activeAntena: Int) {
        dataListener?.onAntData(activeAntena)
        logListener?.onAntData(activeAntena)
    }

    override fun onPowerData(power: Int) {
        dataListener?.onPowerData(power)
        logListener?.onPowerData(power)
    }

    override fun onRssiDbm1Data(rssi: Int) {
        dataListener?.onRssiDbm1Data(rssi)
        logListener?.onRssiDbm1Data(rssi)
    }

    override fun onRssiDbm2Data(rssi: Int) {
        dataListener?.onRssiDbm2Data(rssi)
        logListener?.onRssiDbm2Data(rssi)
    }

    override fun onRssiDbmdData(rssi: Int) {
        dataListener?.onRssiDbmdData(rssi)
        logListener?.onRssiDbmdData(rssi)
    }

    fun disconnect() {
        onDisconnected()
    }
}
