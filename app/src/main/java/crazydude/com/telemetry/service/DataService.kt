package crazydude.com.telemetry.service

import android.Manifest
import android.annotation.TargetApi
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    private fun createLogFile(): FileOutputStream? {
        var fileOutputStream: FileOutputStream? = null
        logName = null
        if (preferenceManager.isLoggingEnabled()
            && ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val started = Date()
            val name = SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(started)
            logName = name
            val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
            dir.mkdirs()
            val file = File(dir, "$name.tlm")
            fileOutputStream = FileOutputStream(file)
            noteStartTime(dir, name, started)
        }

        return fileOutputStream
    }

    /**
     * When this flight began, beside the recording of it.
     *
     * A log is a recording of the bytes off the link and carries no clock in
     * it, and the file's own dates say when it was last written — which is when
     * the flight ended, a quarter of an hour out on a long one. The name says
     * when it started, until somebody renames the log.
     *
     * So it is written down: one small file alongside the recording and the
     * CSV, named after the same flight, and renamed and deleted with them. The
     * epoch is what is read back; the second line is for whoever opens it.
     */
    private fun noteStartTime(dir: File, name: String, started: Date) {
        try {
            File(dir, "$name.start").writeText(
                "epoch=" + started.time + "\n" +
                    "started=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(started) + "\n"
            )
        } catch (e: Exception) {
            // A log without one is still a log: replaying it falls back to the
            // file's own date, as every log recorded before this did.
        }
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
            OtxCsvLogger(logName)
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
        dataPoller?.disconnect()
        dataPoller = null
        satellites = 0
        hasGPSFix = false
    }
}