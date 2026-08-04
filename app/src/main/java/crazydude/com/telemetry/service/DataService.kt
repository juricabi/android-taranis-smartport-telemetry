package crazydude.com.telemetry.service

import android.Manifest
import android.annotation.TargetApi
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.usb.UsbDeviceConnection
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import crazydude.com.telemetry.R
import crazydude.com.telemetry.logger.OtxCsvLogger
import crazydude.com.telemetry.manager.PreferenceManager
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.protocol.pollers.BluetoothDataPoller
import crazydude.com.telemetry.protocol.pollers.BluetoothLeDataPoller
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.protocol.pollers.DataPoller
import crazydude.com.telemetry.protocol.pollers.NetworkDataPoller
import crazydude.com.telemetry.protocol.pollers.UsbDataPoller
import crazydude.com.telemetry.utils.WifiNetworkBinder
import crazydude.com.telemetry.ui.MapsActivity
import java.io.File
import crazydude.com.telemetry.logger.CountingLog
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
    private lateinit var preferenceManager: PreferenceManager
    private var notification: Notification? = null

    override fun onCreate() {
        super.onCreate()

        preferenceManager = PreferenceManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel =
                NotificationChannel("bt_channel", "Bluetooth", importance)
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }


        notification = NotificationCompat.Builder(this, "bt_channel")
            .setContentText("Telemetry service is running. To stop - disconnect and close the app")
            .setContentTitle("Telemetry service is running")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    -1,
                    Intent(this, MapsActivity::class.java),
                    0
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        return Service.START_REDELIVER_INTENT
    }

    inner class DataBinder : Binder() {
        fun getService(): DataService = this@DataService
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    fun connect(device: BluetoothDevice, isBle: Boolean) {
        try {
            dataPoller?.disconnect()

            val logFile = createLogFile()

            createLogger()

            if (!isBle) {
                val socket =
                    device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                dataPoller =
                    BluetoothDataPoller(
                        socket,
                        this,
                        logFile
                    )
            } else {
                dataPoller =
                    BluetoothLeDataPoller(
                        this,
                        device,
                        this,
                        logFile
                    )
            }
        } catch (e: IOException) {
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

    private fun createLogFile(): FileOutputStream? {
        var fileOutputStream: FileOutputStream? = null
        logName = null
        // and no recording, until there is one: left pointing at the last
        // flight's, every row of a CSV recorded without a log beside it carried
        // the size the last recording happened to end at
        recording = null
        if (preferenceManager.isLoggingEnabled()
            && ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val name = SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(Date())
            logName = name
            val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
            dir.mkdirs()
            val file = File(dir, "$name.tlm")
            val counted = CountingLog(file)
            recording = counted
            fileOutputStream = counted
        }

        return fileOutputStream
    }

    /**
     * Where the phone is, heard by the thing that outlives the screen.
     *
     * The recording goes on while the app is in somebody's pocket — that is
     * what the notification is for — and the operator's own position is half of
     * what a replay puts back. Heard on the screen alone, it stopped the moment
     * the screen went away, which is most of a flight.
     *
     * The screen still hears its own, for drawing, and still supplies the
     * bearing, because a compass is only read while there is something to draw.
     */
    private var phoneFix: Location? = null

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

    /** The screen, while it is drawing. Null when it goes away. */
    private var phoneFixListener: ((Location) -> Unit)? = null

    fun watchPhone(listener: ((Location) -> Unit)?) {
        phoneFixListener = listener
        wantedByScreen = listener != null
        updatePhoneListening()
        // whatever is known already, so a screen coming back does not wait for
        // the next fix to draw an arrow
        if (listener != null) phoneFix?.let { listener(it) }
    }

    private fun updatePhoneListening() {
        if (wantedByLink || wantedByScreen) listenForPhone() else stopListeningForPhone()
    }

    private val phoneLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
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
        phoneFix = null
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager)
                .removeUpdates(phoneLocationListener)
        } catch (e: Exception) {
            // never started, or already gone
        }
    }

    /**
     * Which way the phone is facing, from the screen's own compass.
     *
     * The recording is of what came off the link and has nothing in it about
     * the person holding the phone — so without this a replay can only draw the
     * operator where the operator is standing now, which for a flight recorded
     * anywhere else puts the line home across the county.
     */
    fun setPhoneBearing(heading: Float) {
        logListener?.setMyHeading(heading)
    }

    fun connect(serialPort: UsbSerialPort, connection: UsbDeviceConnection) {
        val logFile = createLogFile()
        createLogger()
        dataPoller = UsbDataPoller(
            this,
            serialPort,
            preferenceManager.getUsbSerialBaudrate(),
            connection,
            logFile
        )
    }

    /**
     * Telemetry over the network: a TCP server such as a TBS Crossfire WiFi
     * module, or a UDP sender such as an ExpressLRS backpack.
     *
     * Nothing touches the network here — the poller does all of it on its own
     * thread, because this runs on the UI thread and a socket call would throw
     * NetworkOnMainThreadException.
     */
    fun connect(host: String, port: Int, mode: Int) {
        dataPoller?.disconnect()

        // A log that cannot be opened is worth saying so about, and worth
        // connecting anyway. It used to abandon the connection instead, which
        // left the button on "Connecting…" with nothing on its way to clear it,
        // because no poller was ever created to report a failure.
        val logFile = try {
            createLogFile()
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to open the telemetry log", Toast.LENGTH_LONG).show()
            null
        }
        // inside the same guard: this opens a file in the same directory, so it
        // fails for the same reasons
        try {
            createLogger()
        } catch (e: Exception) {
            logListener = null
        }

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

        dataPoller = NetworkDataPoller(
            mode,
            host,
            port,
            this,
            logFile,
            binder
        )
    }

    private fun createLogger() {
        // Always replaced, never left behind. A logger from a previous
        // connection has had its timer cancelled, and starting it again throws
        // "Timer already cancelled" the moment the next link comes up.
        logListener = if (preferenceManager.isCSVLoggingEnabled()
            && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            OtxCsvLogger(logName) { recording?.bytesWritten ?: 0L }
        } else {
            null
        }
    }

    fun setDataListener(dataListener: DataDecoder.Listener?) {
        this.dataListener = dataListener
        if (dataListener != null) {
            dataListener.onGPSState(satellites, hasGPSFix)
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
        wantedByLink = false
        wantedByScreen = false
        phoneFixListener = null
        stopListeningForPhone()
        dataPoller?.disconnect()
        dataPoller = null
    }

    override fun onConnectionFailed() {
        dataListener?.onConnectionFailed()
        logListener?.onConnectionFailed()
        dataPoller = null
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
        dataListener?.onConnected()
        logListener?.onConnected()

        startForeground(1, notification)
    }

    override fun onGPSData(latitude: Double, longitude: Double) {
        lastLatitude = latitude
        lastLongitude = longitude
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

    override fun onProtocolDetected( protocolName: String) {
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
        wantedByLink = false
        updatePhoneListening()
        dataListener?.onDisconnected()
        logListener?.onDisconnected()
        dataPoller = null
        satellites = 0
        hasGPSFix = false
        stopForeground(true)
    }

    override fun onGPSState(satellites: Int, gpsFix: Boolean) {
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
        wantedByLink = false
        updatePhoneListening()
        dataPoller?.disconnect()
        dataPoller = null
        satellites = 0
        hasGPSFix = false
    }
}