package juricabi.com.telemetry.logger

import android.os.Environment
import juricabi.com.telemetry.maps.Position
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*


/**
 * [name] is the name this flight's files share, without an extension — the
 * recording, this, and the note of when it started. Given none, this makes its
 * own from the time it was created, which is a second or two after the
 * recording's and so a different name for the same flight: renaming or deleting
 * one then left the other behind.
 */
class OtxCsvLogger(
    name: String? = null,
    append: Boolean = false,
    private val bytesRecorded: () -> Long = { 0L }
) : DataDecoder.Listener {

    private val timer = Timer()
    private val output: BestEffortCsvWriter
    private val file: File

    private val header = listOf(
        "Date",
        "Time",
        "RSSI(dB)",
        "1RSS(dB)",
        "2RSS(dB)",
        "RQly(%)",
        "RSNR(dB)",
        "ANT",
        "RFMD",
        "TPWR(mW)",
        "TRSS(dB)", // Downlink - signal strength
        "TQly(%)",
        "TSNR(dB)",
        "Ptch(rad)",
        "Roll(rad)",
        //"Yaw(rad)",
        "FM",
        "VSpd(m/s)",
        "GPS",
        "GSpd(kmh)",
        "Hdg(°)",
        "Alt(m)",
        "Sats",
//            "RxBt(V)",
        "Curr(A)",
        "VFAS(V)",
        "Dist(m)",
        // Where the person holding the phone was, which is half of what a
        // flight looked like: the line home is drawn to it, the arrow and its
        // ring are drawn on it, and none of that can be reconstructed from a
        // recording of what the model said.
        "MyLat",
        "MyLon",
        "MyAcc(m)",
        "MyHdg(deg)",
        // How far through the recording beside this one the link had got when
        // this row was written. A row is written every fifth of a second
        // whether or not anything is arriving, so where this stops climbing the
        // link had gone quiet — and a replay can put that silence back where it
        // happened instead of spreading it across the flight.
        "LogBytes"
    )

    @Volatile private var myLat = Double.NaN
    @Volatile private var myLon = Double.NaN
    @Volatile private var myAccuracy = Float.NaN
    @Volatile private var myHeading = Float.NaN

    /** Where this phone is and which way it is facing, as it changes. */
    /**
     * Where the phone is, from the service, which hears it whether or not
     * anybody is looking at the screen.
     */
    fun setMyPosition(lat: Double, lon: Double, accuracy: Float) {
        myLat = lat
        myLon = lon
        myAccuracy = accuracy
    }

    /**
     * Which way it is facing, from the screen, which is the only thing that
     * reads a compass — and which says so with NaN when it stops.
     */
    fun setMyHeading(heading: Float) {
        myHeading = heading
    }

    /** Blank rather than a nought, which would read as the Gulf of Guinea. */
    private fun place(value: Double): String =
        if (value.isNaN()) "" else String.format(Locale.US, "%.7f", value)

    private fun measure(value: Float): String =
        if (value.isNaN()) "" else String.format(Locale.US, "%.1f", value)

    init {
        val stem = name ?: SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(Date())
        val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
        dir.mkdirs()
        file = File(dir, "$stem.csv")
        // Append continues the same CSV across a reconnect — its header is
        // already there. Write one only when starting a file, or when append
        // lands on one a first link never actually opened (the setting was off
        // then on).
        val writeHeader = !append || !file.exists() || file.length() == 0L
        output = BestEffortCsvWriter(FileWriter(file, append))
        if (writeHeader && !outputLine(header)) timer.cancel()
    }

    private var fuel: Int = 0
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var batVoltage: Float = 0F
    private var cellVoltage: Float = 0F
    private var current: Float = 0F
    private var heading: Float = 0F
    private var rssi: Int = 0
    private var upLq: Int = 0
    private var dnLq: Int = 0
    private var elrsMode: Int = 0
    private var satellites: Int = 0
    private var gpsFix: Boolean = false
    private var vSpeed: Float = 0F
    private var throttle: Int = 0;
    private var altitude: Float = 0F
    private var gpsAltitude: Float = 0F
    private var distance: Int = 0
    private var rollAngle: Float = 0F
    private var pitchAngle: Float = 0F
    private var gSpeed: Float = 0F
    private var armed: Boolean = false
    private var airSpeed: Float = 0F
    private var rcChannels: IntArray? = null
    private var statusText: String = ""
    private var dnSnr: Int = 0
    private var upSnr: Int = 0
    private var activeAntenna: Int = 0
    private var power: Int = 0
    private var rssiDbm1: Int = 0
    private var rssiDbm2: Int = 0
    private var rssiDbmd: Int = 0
    private var flightMode: String = ""

    private fun outputLine(line: List<String>): Boolean {
        val csv = line.joinToString(",")
        return output.writeLine(csv)
    }

    /*
    *
    * "Alt(m)" altitude 0.0
      -        armed false
      -        hdg false
      "FM" firstFlightMode ERROR
      "Curr(A)"  current: 0.4
      "TSNR(dB)"  dn snr 4
      "TQly(%)"   dnLq: 100
      "RFMD"      elrs mode: 5
      "Fuel(mAh)" fuel: 0 mah
      "GPS"       GPS: 0.0 0.0
      "GSpd(kmh)" ground speed 0.0
      "Hdg(°)"    heading: 103.89444
      "Ptch(rad)" pitchAngle -81.49752
      "TPWR(mW)"  power 1
      "Roll(rad)" rollAngle 83.99561
      "1RSS(dB)"  rssi dbm1 -40
      "2RSS(dB)"  rssi dbm2 0
      "TRSS(dB)"  rssi dbmd -16
      "RSSI(dB)"  rssi: 94
      "Sats"      sats 0
      -           fix: false
      "RSNR(dB)"  up snr 2
      "RQly(%)"   upLq: 100
      "VFAS(V)"   voltage vbat or cell 16.4

    * */
    private fun outputData(): Boolean {
        val date = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val time = SimpleDateFormat("HH:mm:ss.SSS").format(Date())

        val data = listOf<String>(
            date,
            time,
            rssi.toString(),
            rssiDbm1.toString(),
            rssiDbm2.toString(),
            upLq.toString(),
            upSnr.toString(),
            activeAntenna.toString(),
            elrsMode.toString(),
            power.toString(),
            rssiDbmd.toString(), // Downlink - signal strength
            dnLq.toString(),
            dnSnr.toString(),
            pitchAngle.toString(),
            rollAngle.toString(),
            flightMode,
            vSpeed.toString(),
            "$latitude $longitude",
            gSpeed.toString(),
            heading.toString(),
            altitude.toString(),
            satellites.toString(),
//            "RxBt(V)",
            current.toString(),
            batVoltage.toString(),
            distance.toString(),
            place(myLat),
            place(myLon),
            measure(myAccuracy),
            measure(myHeading),
            bytesRecorded().toString()
        )
        return outputLine(data)
    }

    private fun closeLog() {
        timer.cancel()
        output.close()
        timer.purge()
        // Nothing came off the link: only the header and the timer's own empty
        // rows reached this. The recording beside it — a zero-byte .tlm the log
        // list hides from "delete all" — is being dropped the same way, so drop
        // this with it rather than leave a headers-only CSV standing for a
        // flight that was never recorded. Matched by name, as a delete is.
        if (bytesRecorded() == 0L) file.delete()
    }

    override fun onConnectionFailed() {
        // The timer first. Closing the writer takes a moment, and a row being
        // written in that moment threw on a closed stream — out of a
        // TimerTask, where nothing catches it and the process ends.
        //
        // The timer thread is not a daemon either, so a failed attempt would
        // otherwise leave one parked for the life of the process, and a
        // reconnect loop leaves many.
        closeLog()
    }

    override fun onFuelData(fuel: Int) {
        this.fuel = fuel

    }

    override fun onConnected() {
        if (!output.isOpen()) return
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!outputData()) timer.cancel()
            }
        },1000,200)
    }

    override fun onGPSData(latitude: Double, longitude: Double) {
        this.latitude = latitude
        this.longitude = longitude
    }

    override fun onGPSData(list: List<Position>, addToEnd: Boolean) {

    }

    override fun onVBATData(voltage: Float) {
        this.batVoltage = voltage
    }

    override fun onCellVoltageData(voltage: Float) {
        this.cellVoltage = voltage
    }

    override fun onCurrentData(current: Float) {
        this.current = current
    }

    override fun onHeadingData(heading: Float) {
        this.heading = heading
    }

    override fun onRSSIData(rssi: Int) {
        this.rssi = rssi
    }

    override fun onUpLqData(lq: Int) {
        this.upLq = lq
    }

    override fun onDnLqData(lq: Int) {
        this.dnLq = lq
    }

    override fun onElrsModeModeData(mode: Int) {
        this.elrsMode = mode
    }

    override fun onDisconnected() {
//        val sendIntent = Intent()
//        sendIntent.action = Intent.ACTION_SEND
//        sendIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
//        sendIntent.type = "text/csv"
//        startActivity(Intent.createChooser(sendIntent, "SHARE"))
        closeLog()
    }

    override fun onGPSState(satellites: Int, gpsFix: Boolean) {
        this.satellites = satellites
        this.gpsFix = gpsFix
    }

    override fun onVSpeedData(vspeed: Float) {
        this.vSpeed = vspeed
    }

    override fun onThrottleData(throttle: Int) {
        this.throttle = throttle
    }

    override fun onAltitudeData(altitude: Float) {
        this.altitude = altitude
    }

    override fun onGPSAltitudeData(altitude: Float) {
        this.gpsAltitude = altitude
    }

    override fun onDistanceData(distance: Int) {
        this.distance = distance
    }

    override fun onRollData(rollAngle: Float) {
        this.rollAngle = rollAngle
    }

    override fun onPitchData(pitchAngle: Float) {
        this.pitchAngle = pitchAngle
    }

    override fun onGSpeedData(speed: Float) {
        this.gSpeed = speed
    }

    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) {
        this.flightMode = firstFlightMode.toString()
    }

    override fun onAirSpeedData(speed: Float) {
        this.airSpeed=speed
    }

    override fun onRCChannels(rcChannels: IntArray) {
        // not yet implemented
    }

    override fun onStatusText(message: String) {
        this.statusText=message
    }

    override fun onDNSNRData(snr: Int) {
        this.dnSnr=snr
    }

    override fun onUPSNRData(snr: Int) {
        this.upSnr=snr
    }

    override fun onAntData(activeAntena: Int) {
        this.activeAntenna=activeAntena
    }

    override fun onPowerData(power: Int) {
        this.power=power
    }

    /**
     * Uplink - received signal strength antenna 1 (RSSI)
     */
    override fun onRssiDbm1Data(rssi: Int) {
        this.rssiDbm1=rssi
    }

    /**
     * Uplink - received signal strength antenna 2 (RSSI)
     */
    override fun onRssiDbm2Data(rssi: Int) {
        this.rssiDbm2=rssi
    }

    /**
     * Downlink - received signal strength (RSSI)
     */
    override fun onRssiDbmdData(rssi: Int) {
        this.rssiDbmd=rssi
    }

    override fun onVBATOrCellData(voltage: Float) {
        this.batVoltage=voltage
    }


    override fun onTelemetryByte() {

    }

    override fun onSuccessDecode() {

    }

    override fun onDecoderRestart() {

    }

    override fun onProtocolDetected( protocolName: String) {
    }

    override fun commit() {
    }

}
