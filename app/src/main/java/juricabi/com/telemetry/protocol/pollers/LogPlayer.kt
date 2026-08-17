package juricabi.com.telemetry.protocol.pollers
import android.annotation.SuppressLint
import android.os.AsyncTask
import android.os.Environment
import juricabi.com.telemetry.maps.Position
import juricabi.com.telemetry.protocol.*
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.io.File
import java.io.FileInputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class LogPlayer(val originalListener: DataDecoder.Listener) : DataDecoder.Listener {

    companion object {
        /**
         * How often a running replay moves on: once per frame the screen draws.
         *
         * Everything that follows the flight — the marker, the model, the
         * camera — glides toward wherever the replay has got to by a fixed
         * fraction of the way there, on every frame. That is smooth when it is
         * given somewhere new to go on every frame, and a visible pulse when it
         * is given somewhere new twenty times a second: a lurch on the frame
         * after each step, then a coast until the next.
         *
         * It cost nothing to move here. A step of the replay draws once,
         * however few packets it carries, so three small steps and one large
         * one are the same work — and the same number of packets a second,
         * because the step is measured from this.
         */
        private const val TICK_MS = 16L

        /**
         * The fastest a replay may be wound on, in packets a second — a short
         * playback time asked of a very long log. Kept where it was when a step
         * was a twentieth of a second and at most a thousand packets.
         */
        private const val PACKETS_PER_SECOND_MAX = 20000
    }

    private var cachedData = ArrayList<Protocol.Companion.TelemetryData>()

    /** How far into the recording each packet of [cachedData] finished. */
    private var offsets = LongList()
    private var decoded = LongList()

    /**
     * A long list without the boxes: ArrayList held each offset as a
     * java.lang.Long, thirty megabytes of wrappers on a million-packet
     * log beside eight of numbers.
     */
    private class LongList {
        var size = 0
            private set
        private var a = LongArray(1024)
        fun add(v: Long) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            a[size++] = v
        }
        operator fun get(i: Int) = a[i]
        fun isEmpty() = size == 0
    }

    /**
     * Where in the recording the replay has got to.
     *
     * Packets counted say nothing about time passing — a link that went quiet
     * for a minute wrote nothing at all — but bytes written line up with the
     * CSV, which was written on a clock.
     */
    fun bytesAt(position: Int): Long {
        if (offsets.isEmpty()) return 0L
        val at = Math.max(0, Math.min(position, offsets.size - 1))
        return offsets[at]
    }

    /** How many packets the recording decoded to, for estimating its pace. */
    fun packetCount(): Int = cachedData.size
    private var decodedCoordinates = ArrayList<Position>()
    private var hasGPSFix = false
    private var satellites = 0;
    private var dataReadyListener: DataReadyListener? = null
    public var currentPosition: Int = 0
    private var uniqueData = HashMap<Int, Int>()
    private var uniqueDataIndex = HashMap<Int, Int>()
    private lateinit var protocol: Protocol

    private var decodedAltitude : Float = -1f;
    private var decodedSpeed : Float = 0f;
    private var decodedHeading : Float = 0f;

    private var statusTextExpire : Int = 0;

    private var fireGPSState = false;

    private var mTimer: Timer? = null

    private var totalPlaybackDurationMS : Int = 30000;

    public var launchPointMSLAltitude = 0;

    //async task used to load file, detect protocol and decode packets into arrayList
    private val task = @SuppressLint("StaticFieldLeak") object :
        AsyncTask<File, Long, ArrayList<Protocol.Companion.TelemetryData>>() {

        override fun doInBackground(vararg file: File): ArrayList<Protocol.Companion.TelemetryData> {
            val arrayList = ArrayList<Protocol.Companion.TelemetryData>()
            val collected = LongList()
            decoded = collected
            var tempProtocol: Protocol? = null

            // Where in the file each packet finished, so a position in the
            // replay can be turned into a place in the recording — and from
            // there, through the CSV, into the time it actually happened.
            // Counted through the decoding pass, which starts again from the
            // beginning of the file once the protocol has been detected.
            var consumed = 0L

            val tempDecoder = object : DataDecoder(this@LogPlayer) {
                override fun decodeData(data: Protocol.Companion.TelemetryData) {
                    arrayList.add(data)
                    collected.add(consumed)
                }
            }

            val protocolDetector =
                ProtocolDetector(object :
                    ProtocolDetector.Callback {
                    override fun onProtocolDetected(detectedProtocol: Protocol?) {
                        when (detectedProtocol) {
                            is FrSkySportProtocol -> {
                                tempProtocol =
                                    FrSkySportProtocol(
                                        tempDecoder
                                    )
                                protocol =
                                    FrSkySportProtocol(
                                        this@LogPlayer
                                    )
                                dataReadyListener?.onProtocolDetected("FrSky")
                            }

                            is CrsfProtocol -> {
                                tempProtocol =
                                    CrsfProtocol(
                                        tempDecoder
                                    )
                                protocol =
                                    CrsfProtocol(
                                        this@LogPlayer
                                    )
                                dataReadyListener?.onProtocolDetected("CRSF")
                            }

                            is GhstProtocol -> {
                                tempProtocol =
                                    GhstProtocol(
                                        tempDecoder
                                    )
                                protocol =
                                    GhstProtocol(
                                        this@LogPlayer
                                    )
                                dataReadyListener?.onProtocolDetected("GHST")
                            }

                            is LTMProtocol -> {
                                tempProtocol =
                                    LTMProtocol(
                                        tempDecoder
                                    )
                                protocol =
                                    LTMProtocol(
                                        this@LogPlayer
                                    )
                                dataReadyListener?.onProtocolDetected("LTM")
                            }

                            is MAVLinkProtocol -> {
                                tempProtocol =
                                    MAVLinkProtocol(
                                        tempDecoder
                                    )
                                protocol =
                                    MAVLinkProtocol(
                                        this@LogPlayer
                                    )
                                dataReadyListener?.onProtocolDetected("Mavlink v1")
                            }

                            is MAVLink2Protocol -> {
                                tempProtocol = MAVLink2Protocol(tempDecoder)
                                protocol = MAVLink2Protocol(this@LogPlayer)
                                dataReadyListener?.onProtocolDetected("Mavlink v2")
                            }
                        }
                    }
                })

            val buffer = ByteArray(1024)

            //feed protocolDetector until protocol is detected and
            //tempProtocol and protocol are assigned correct protocol decoder
            FileInputStream(file[0]).use { logFile ->
                var bytesRead = logFile.read(buffer)
                while (bytesRead != -1 && tempProtocol == null && !isCancelled) {
                    for (i in 0 until bytesRead) {
                        if (isCancelled) break
                        if (tempProtocol == null) {
                            protocolDetector.feedData(buffer[i].toUByte().toInt())
                        } else {
                            break
                        }
                    }
                    if (tempProtocol == null) bytesRead = logFile.read(buffer)
                }
            }

            if (tempProtocol == null) {
                publishProgress(100)
                //just assign dummy protocol
                protocol = CrsfProtocol(
                    this@LogPlayer
                )
                dataReadyListener?.onProtocolDetected("Unknown")
            } else {
                //now when protocol is detected and tempProtocol is assigned,
                //feed tempProtocol to decode all packets into arrayList
                val length = file[0].length()
                // About one progress update per percent for ordinary logs,
                // without a zero-byte buffer for tiny files or a huge
                // allocation for very large recordings.
                val size = (length / 100L).coerceIn(1L, 64L * 1024L).toInt()
                val bytes = ByteArray(size)
                FileInputStream(file[0]).use { input ->
                    var allBytes = 0L
                    var bytesRead = input.read(bytes)
                    while (bytesRead != -1 && !isCancelled) {
                        for (i in 0 until bytesRead) {
                            if (isCancelled) break
                            consumed++
                            tempProtocol?.process(bytes[i].toUByte().toInt())
                        }
                        if (isCancelled) break
                        allBytes += bytesRead
                        if (length > 0L) {
                            publishProgress(
                                Math.min(100L, (allBytes.toDouble() / length * 100.0).toLong())
                            )
                        }
                        bytesRead = input.read(bytes)
                    }
                }
                publishProgress(100)
            }

            return arrayList
        }

        override fun onProgressUpdate(vararg values: Long?) {
            values.let { dataReadyListener?.onUpdate(values[0]?.toInt() ?: 0) }
        }

        override fun onPostExecute(result: ArrayList<Protocol.Companion.TelemetryData>) {
            cachedData = result
            offsets = decoded
            dataReadyListener?.onDataReady(result.size)

            if (dataReadyListener?.getPlaybackAutostart() == true ){
                startPlayback();
            }
        }
    }

    fun load(file: File, dataReadyListener: DataReadyListener) {
        this.dataReadyListener = dataReadyListener
        task.execute(file)
    }

    fun seek(position: Int) {
        // where this step started, for ageing the status text by the flight
        // it covers — currentPosition has moved on by the time that is asked
        val walkedFrom = if (position >= currentPosition) currentPosition else 0
        //seek forward: fire all packets from last position to new position
        //seek backward: fire all packets from the start to the new position

        //in the range of processed packets during the seek,
        //packets which produce onGPSData: all fired (are required to build correct track without cut corners)
        //other packets: only last one is fired (there is no need to fire data which will be replaced by last packet)
        uniqueData.clear()
        uniqueDataIndex.clear()
        decodedCoordinates.clear()

        //when decodedCoordinates.size=key, cachedData[value]
        var outUniqueData: HashMap<Int, ArrayList<Int>> = HashMap<Int, ArrayList<Int>>();

        this.fireGPSState = false;

        var addToEnd: Boolean = false;

        if ( position == 0) {
            //clear router line and message
            protocol.dataDecoder.restart()
            this.expireStatusText(10000)
        }

        if (position > currentPosition) {
            for (i in currentPosition until position) {
                var prevFix = this.hasGPSFix
                var prevSatellites = this.satellites;
                if ( protocol.dataDecoder.isGPSOrImageData( cachedData[i].telemetryType )) {
                    protocol.dataDecoder.decodeData(cachedData[i])
                    if ( (prevFix != this.hasGPSFix) || (prevSatellites != this.satellites))
                    {
                        var index = decodedCoordinates.size;
                        if ( outUniqueData[index] == null) {
                            outUniqueData[index] = ArrayList<Int>();
                        }
                        outUniqueData[index]?.add(i);
                    }
                } else if ( protocol.dataDecoder.isHeightData( cachedData[i].telemetryType )) {
                    // where it happened, not collapsed to the last one: a
                    // height belongs to the position it was measured at
                    var index = decodedCoordinates.size;
                    if ( outUniqueData[index] == null) {
                        outUniqueData[index] = ArrayList<Int>();
                    }
                    outUniqueData[index]?.add(i);
                } else {
                    uniqueData[cachedData[i].telemetryType] = i
                    uniqueDataIndex[cachedData[i].telemetryType] = decodedCoordinates.size;
                }
            }
            addToEnd = true
            currentPosition = position
        } else if (position < currentPosition) {
            protocol.dataDecoder.restart()
            this.hasGPSFix = false;
            this.satellites = 0;
            for (i in 0 until position) {
                var prevFix = this.hasGPSFix
                var prevSatellites = this.satellites;
                if ( protocol.dataDecoder.isGPSOrImageData( cachedData[i].telemetryType )) {
                    protocol.dataDecoder.decodeData(cachedData[i])
                    if ( (prevFix != this.hasGPSFix) || (prevSatellites != this.satellites))
                    {
                        var index = decodedCoordinates.size;
                        if ( outUniqueData[index] == null) {
                            outUniqueData[index] = ArrayList<Int>();
                        }
                        outUniqueData[index]?.add(i);
                        uniqueData.remove(cachedData[i].telemetryType)
                        uniqueDataIndex.remove(cachedData[i].telemetryType)
                    }
                } else if ( protocol.dataDecoder.isHeightData( cachedData[i].telemetryType )) {
                    // where it happened, not collapsed to the last one: a
                    // height belongs to the position it was measured at
                    var index = decodedCoordinates.size;
                    if ( outUniqueData[index] == null) {
                        outUniqueData[index] = ArrayList<Int>();
                    }
                    outUniqueData[index]?.add(i);
                } else {
                    uniqueData[cachedData[i].telemetryType] = i
                    uniqueDataIndex[cachedData[i].telemetryType] = decodedCoordinates.size;
                }
            }
            currentPosition = position
            addToEnd = false
        }

        uniqueDataIndex.forEach {
            var type = it.key;
            var index = it.value;
            if (outUniqueData[index] == null) {
                outUniqueData[index] = ArrayList<Int>();
            }
            outUniqueData[index]?.add(uniqueData[type]!!);
        }

        //we can fire only last packet for unique data,
        //but it has to be correctly fired between gps coords
        var outDecodedCoordinates = ArrayList<Position>()
        this.fireGPSState = true;

        for ( index in 0..decodedCoordinates.size) {
            var uids: ArrayList<Int>? = outUniqueData[index];
            if (uids != null) {
                if (outDecodedCoordinates.size > 0) {
                    originalListener.onGPSData(outDecodedCoordinates, addToEnd)
                    addToEnd = true;
                    outDecodedCoordinates.clear();
                }
                uids.forEach({
                    protocol.dataDecoder.decodeData(cachedData[it])
                })
            }
            if ( index < decodedCoordinates.size )
            {
                outDecodedCoordinates.add(decodedCoordinates[index]);
            }
        }

        if ( outDecodedCoordinates.size > 0 ) {
            originalListener.onGPSData(outDecodedCoordinates, addToEnd)
        }
        // and the message ages by however much flight this seek covered,
        // counted once for the whole step rather than per fix
        if (position > walkedFrom) this.expireStatusText(flightMsAcross(position - walkedFrom))

        originalListener?.commit();
    }

    /**
     * The packet by which the flight first has somewhere to be drawn.
     *
     * Decoded, not replayed: nothing is handed to the screen on the way there.
     * Walking to it with seek() was what made opening a log look like the
     * flight being flown once, at speed, before it began.
     */
    fun firstFixPosition(): Int {
        if (cachedData.isEmpty()) return 0
        val wasFiring = fireGPSState
        fireGPSState = false
        forget()
        var found = 0
        for (i in 0 until cachedData.size) {
            if (!protocol.dataDecoder.isGPSOrImageData(cachedData[i].telemetryType)) continue
            protocol.dataDecoder.decodeData(cachedData[i])
            if (hasGPSFix && decodedCoordinates.isNotEmpty()) {
                found = i + 1
                break
            }
        }
        // left as it was found: the seek that follows starts from the beginning
        forget()
        currentPosition = 0
        fireGPSState = wasFiring
        return found
    }

    private fun forget() {
        protocol.dataDecoder.restart()
        hasGPSFix = false
        satellites = 0
        decodedCoordinates.clear()
    }

    fun stop() {
        if ( mTimer != null ) {
            this.mTimer?.cancel();
            this.mTimer = null;
            this.dataReadyListener?.onPlaybackStateChange(false)
        }
    }

    /**
     * Where the recording's first fix is, found without playing any of it.
     *
     * The ground for a replay is built around the flight, and building it
     * before playback starts needs the flight's first place. The replay's own
     * decoder cannot be asked — walking it fires every reading at the screen
     * and moves the replay — so the packets are walked through a decoder of
     * their own, and the walk stops at the first coordinate held with a fix.
     */
    fun firstPosition(): Position? {
        if (cachedData.isEmpty() || !::protocol.isInitialized) return null
        var found: Position? = null
        var fix = false
        val probe = object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onGPSState(satellites: Int, gpsFix: Boolean) {
                fix = gpsFix
            }
            override fun onGPSData(latitude: Double, longitude: Double) {
                if (found == null && fix && (latitude != 0.0 || longitude != 0.0)) {
                    found = Position(latitude, longitude)
                }
            }
        }
        val decoder = when (protocol) {
            is FrSkySportProtocol -> FrSkySportProtocol(probe)
            is CrsfProtocol -> CrsfProtocol(probe)
            is GhstProtocol -> GhstProtocol(probe)
            is LTMProtocol -> LTMProtocol(probe)
            is MAVLinkProtocol -> MAVLinkProtocol(probe)
            is MAVLink2Protocol -> MAVLink2Protocol(probe)
            else -> return null
        }.dataDecoder
        for (data in cachedData) {
            decoder.decodeData(data)
            found?.let { return it }
        }
        return null
    }

    /** Permanently abandon this replay, including a log that is still loading. */
    fun dispose() {
        dataReadyListener = null
        stop()
        task.cancel(true)
    }

    fun startPlayback() {
        if ( this.mTimer == null ) {
            this.mTimer = Timer();

            if ( currentPosition == cachedData.size) {
                seek(0)
            }

            totalPlaybackDurationMS = dataReadyListener!!.getTotalPlaybackDurationSec() * 1000

            val ticks = Math.max(1, totalPlaybackDurationMS / TICK_MS.toInt())
            val most = (PACKETS_PER_SECOND_MAX * TICK_MS / 1000L).toInt()
            // Fractional, and carried from frame to frame. A recording played
            // at the speed it happened moves on by a fraction of a packet per
            // frame, and rounding that up to one is the difference between
            // twenty minutes and three; the same rounding made every other
            // length approximate, so a short log ran out before its time.
            val perTick = Math.min(most.toDouble(), cachedData.size.toDouble() / ticks)
            var carried = 0.0

            // schedule, not scheduleAtFixedRate: at a fixed rate a timer makes
            // up for runs it missed, and the first seconds of a replay are
            // exactly when it misses them — the ground is loading and the
            // screen is busy. The backlog then ran off in one go and the flight
            // sprinted through its first minute before settling to the speed
            // that was asked for. Late is better than fast: a replay held up
            // for a moment simply carries on from where it is.
            this.mTimer?.schedule(object : TimerTask() {
                override fun run() {
                    val prevPosition = currentPosition;

                    if ( currentPosition == cachedData.size ) {
                        stop();
                    } else {
                        carried += perTick
                        val step = carried.toInt()
                        // a frame with no packet of the recording in it
                        if (step < 1) return
                        carried -= step
                        var nextPosition = Math.min(currentPosition + step, cachedData.size)
                        dataReadyListener?.onPlaybackPositionChange( prevPosition, nextPosition );
                    }
                }
            }, 100, TICK_MS)
            this.dataReadyListener?.onPlaybackStateChange(true)
        }

    }

    public fun isPlaying() : Boolean {
        return this.mTimer != null;
    }


    override fun onConnectionFailed() {
    }

    override fun onFuelData(fuel: Int) {
        originalListener.onFuelData(fuel)
    }

    override fun onConnected() {
    }

    override fun onGPSData(latitude: Double, longitude: Double) {
        if (latitude != 0.0 && longitude != 0.0) {
            decodedCoordinates.add(Position(latitude, longitude))
        }
    }

    override fun onVBATData(voltage: Float) {
        originalListener.onVBATData(voltage)
    }

    override fun onCellVoltageData(voltage: Float) {
        originalListener.onCellVoltageData(voltage)
    }

    override fun onVBATOrCellData(voltage: Float) {
        originalListener.onVBATOrCellData(voltage)
    }

    override fun onCurrentData(current: Float) {
        originalListener.onCurrentData(current)
    }

    override fun onHeadingData(heading: Float) {
        decodedHeading = heading;
        originalListener.onHeadingData(heading)
    }

    override fun onRSSIData(rssi: Int) {
        originalListener.onRSSIData(rssi)
    }

    override fun onUpLqData(lq: Int) {
        originalListener.onUpLqData(lq)
    }

    override fun onDnLqData(lq: Int) {
        originalListener.onDnLqData(lq)
    }

    override fun onElrsModeModeData(rf: Int) {
        originalListener.onElrsModeModeData(rf)
    }

    override fun onAntData(activeAntena: Int) {
        originalListener.onAntData(activeAntena)
    }

    override fun onGPSData(list: List<Position>, addToEnd: Boolean) {

    }

    override fun onDisconnected() {
    }

    override fun onGPSState(satellites: Int, gpsFix: Boolean) {
        this.hasGPSFix = gpsFix
        this.satellites = satellites
        if ( fireGPSState) {
            originalListener.onGPSState(satellites, gpsFix)
        }
    }

    override fun onVSpeedData(vspeed: Float) {
        originalListener.onVSpeedData(vspeed)
    }

    override fun onThrottleData(throttle :Int) {
        originalListener.onThrottleData(throttle)
    }

    override fun onAltitudeData(altitude: Float) {
        decodedAltitude = altitude;
        originalListener.onAltitudeData(altitude)
    }

    override fun onHomeData(latitude: Double, longitude: Double, altitudeMsl: Float) {
        // forwarded, not defaulted away: a replayed LTM log must prove its
        // altitude from the same home the live flight did
        originalListener.onHomeData(latitude, longitude, altitudeMsl)
    }

    override fun onGPSAltitudeData(altitude: Float) {
        originalListener.onGPSAltitudeData(altitude)
        if ( launchPointMSLAltitude == 0 && altitude != 0.0f) {
            launchPointMSLAltitude = Math.ceil(altitude.toDouble()).toInt();
        }
    }

    override fun onDistanceData(distance: Int) {
        originalListener.onDistanceData(distance)
    }

    override fun onRollData(rollAngle: Float) {
        originalListener.onRollData(rollAngle)
    }

    override fun onAirSpeedData(speed: Float) {
        originalListener.onAirSpeedData(speed)
    }

    override fun onPitchData(pitchAngle: Float) {
        originalListener.onPitchData(pitchAngle)
    }

    override fun onGSpeedData(speed: Float) {
        decodedSpeed = speed;
        originalListener.onGSpeedData(speed)
    }

    override fun onRCChannels(rcChannels:IntArray) {
        originalListener.onRCChannels(rcChannels)
    }

    override fun onStatusText(message : String) {
        this.statusTextExpire = statusTextHoldMs;
        originalListener.onStatusText(message)
    }


    /**
     * How long a status text stands, in milliseconds of the flight.
     *
     * It used to count the fixes that went by, ten of them, which is ten
     * seconds on a link reporting once a second and one second on a link
     * reporting ten times — so the same message that stood throughout a live
     * flight blinked its way through the replay of it. The live screen holds
     * one for ten seconds without a newer one; a replay of that flight should
     * do the same, in the flight's own time rather than the room's.
     */
    fun expireStatusText(elapsedMs: Int) {
        if (this.statusTextExpire > 0) {
            this.statusTextExpire -= elapsedMs;
            if (this.statusTextExpire <= 0) {
                this.statusTextExpire = 0;
                originalListener.onStatusText("")
            }
        }
    }

    /** As long as the live screen holds one: [SensorTimeoutManager.DEFAULT_TIMEOUT_MS]. */
    private val statusTextHoldMs = 10_000

    /**
     * How much of the flight a run of packets covers, evenly paced. The
     * recording carries no clock of its own beyond its total length, and an
     * even pace is right enough for deciding when a message has gone stale.
     */
    private fun flightMsAcross(packets: Int): Int {
        val total = cachedData.size
        if (total <= 0 || packets <= 0) return 0
        val durationMs = (dataReadyListener?.getTotalPlaybackDurationSec() ?: 0) * 1000L
        if (durationMs <= 0L) return 0
        return (durationMs * packets / total).toInt()
    }

    override fun onDNSNRData(snr: Int) {
        originalListener.onDNSNRData(snr)
    }

    override fun onUPSNRData(snr: Int) {
        originalListener.onUPSNRData(snr)
    }

    override fun onPowerData(power: Int) {
        originalListener.onPowerData(power)
    }

    override fun onRssiDbm1Data(rssi: Int) {
        originalListener.onRssiDbm1Data(rssi)
    }

    override fun onRssiDbm2Data(rssi: Int) {
        originalListener.onRssiDbm2Data(rssi)
    }

    override fun onRssiDbmdData(rssi: Int) {
        originalListener.onRssiDbmdData(rssi)
    }


    override fun onTelemetryByte(){
        originalListener.onTelemetryByte()
    }

    override fun onSuccessDecode() {
        originalListener.onSuccessDecode()
    }

    override fun onDecoderRestart() {
        originalListener.onDecoderRestart()
    }

    override fun onProtocolDetected( protocolName: String) {
        // What the decoder says while the log is being played, as against what
        // the load worked out from its first bytes: the two agree about the
        // link, and only this one knows an autopilot is talking over it.
        dataReadyListener?.onProtocolDetected(protocolName)
    }

    override fun commit() {
    }

    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) {
        originalListener.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
    }

    fun addGPXHeader(fileWriter : PrintWriter )
    {
        fileWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<gpx\n" +
                "  version=\"1.0\"\n" +
                "  creator=\"telemetryViewer\"\n" +
                "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "  xmlns=\"http://www.topografix.com/GPX/1/0\"\n" +
                "  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/0 http://www.topografix.com/GPX/1/0/gpx.xsd\">\n" +
                "<trk>\n" +
                "<trkseg>")
    }

    fun addGPXFooter(fileWriter : PrintWriter )
    {
        fileWriter.write("</trkseg>\n" +
                "</trk>\n" +
                "</gpx>")
    }

    //https://github.com/Parrot-Developers/mavlink/blob/master/pymavlink/tools/mavtogpx.py
    fun exportGPX(fileName: String, homePointAltitudeMSL: Float)
    {
        val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
        dir.mkdirs()
        val file = File(dir, fileName)

        var fileWriter = file.printWriter()
        addGPXHeader( fileWriter );

        seek(0);

        decodedAltitude = -10000f;
        decodedSpeed = 0f;
        decodedHeading = 0f;

        var lastLon : Double = 0.0;
        var lastLat : Double = 0.0;

        var startTime = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

        for (i in 0 until cachedData.size) {
                protocol.dataDecoder.decodeData(cachedData[i])

                var output = false;
                if (decodedCoordinates.size > 0)
                {
                    if ( decodedCoordinates[0].lon != lastLon)
                    {
                        lastLon = decodedCoordinates[0].lon;
                        output= true;
                    }
                    if ( decodedCoordinates[0].lat != lastLat)
                    {
                        lastLat = decodedCoordinates[0].lat;
                        output= true;
                    }
                    decodedCoordinates.clear();
                }

                if ( output && (decodedAltitude != - 10000f) )
                {
                    var t = startTime + i * 1800000L / cachedData.size;
                    var s = "<trkpt lat=\"" + lastLat.toString() + "\" lon=\"" + lastLon.toString()  + "\">\n" +
                            "  <ele>" +  ((decodedAltitude + homePointAltitudeMSL) ).toString()  + "</ele>\n" +
                            //"  <time>%s</time>\n" +
                            "  <course>" + decodedHeading.toString()  + "</course>\n" +
                            "  <speed>" + decodedSpeed.toString()  + "</speed>\n" +
                            "  <fix>3d</fix>\n" +
                            "  <time>" + sdf.format( Date(t) ) + "</time>\n" +
                            "</trkpt>";
                    fileWriter.write(s);
                }
        }

        addGPXFooter(fileWriter);

        fileWriter.flush();
        fileWriter.close()
    }

    fun addKMLHeader(fileWriter : PrintWriter, altitudeMode: String )
    {
        val s = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"  xmlns:gx="http://www.google.com/kml/ext/2.2" xmlns:kml="http://www.opengis.net/kml/2.2"    
     xmlns:atom="http://www.w3.org/2005/Atom">
    <Document>
        <visibility>1</visibility>
        <open>1</open>
        <Style id="red">
            <LineStyle>
            <color>C81400FF</color>
            <width>4</width>
            </LineStyle>
        </Style>
        <Folder>
            <name>Tracks</name>
            <description>Track 1</description>
            <visibility>1</visibility>            
            <open>0</open>
                                                            
                <Placemark>
                    <visibility>1</visibility>            
                    <open>0</open> 
                    <styleUrl>#red</styleUrl>
                    <name>Track no. 1</name>
                    <description>No info available</description>
                    <LineString>
                        <extrude>true</extrude>
                        <tessellate>true</tessellate>
                        <altitudeMode>$altitudeMode</altitudeMode> 
                        <coordinates>
"""
        fileWriter.write(s)
    }

    fun addKMLFooter(fileWriter : PrintWriter, altitudeMode: String, lookAtLon : Double, lookAtLat: Double, lookAtAlt: Float, homePointAltitudeMSL: Float )
    {
        val lon = lookAtLon.toString()
        val lat = lookAtLat.toString()
        val alt = (lookAtAlt + homePointAltitudeMSL)
        val s = """                        </coordinates>
                    </LineString>
                </Placemark>
                                        
        </Folder>
                
        <LookAt>
            <longitude>$lon</longitude>            
            <latitude>$lat</latitude>             
            <altitude>$alt</altitude>               
            <heading>0</heading>               
            <tilt>45</tilt>
            <range>226</range>                    
            <altitudeMode>$altitudeMode</altitudeMode> 
        </LookAt>
    </Document>
</kml>
"""
        fileWriter.write(s)
    }

    fun exportKML(fileName: String, homePointAltitudeMSL: Float, altitudeMode: String)
    {
        val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
        dir.mkdirs()
        val file = File(dir, fileName)

        var fileWriter = file.printWriter()
        addKMLHeader( fileWriter, altitudeMode );

        seek(0);

        decodedAltitude = -10000f;
        decodedSpeed = 0f;
        decodedHeading = 0f;

        var lastLon : Double = 0.0;
        var lastLat : Double = 0.0;

        var firstLon : Double = 0.0;
        var firstLat : Double = 0.0;
        var firstAlt : Float = 0.0f;

        var s = "                            ";

        for (i in 0 until cachedData.size) {
            protocol.dataDecoder.decodeData(cachedData[i])

            var output = false;
            if (decodedCoordinates.size > 0)
            {
                if ( decodedCoordinates[0].lon != lastLon)
                {
                    lastLon = decodedCoordinates[0].lon;
                    output= true;
                }
                if ( decodedCoordinates[0].lat != lastLat)
                {
                    lastLat = decodedCoordinates[0].lat;
                    output= true;
                }

                if ( firstLon == 0.0 ) {
                    firstLon = decodedCoordinates[0].lon
                }
                if ( firstLat == 0.0 ) {
                    firstLat = decodedCoordinates[0].lat
                }
                if ( (firstAlt == 0.0f) && (decodedAltitude != - 10000f) ) {
                    firstAlt = decodedAltitude.toFloat()
                }

                decodedCoordinates.clear();
            }

            if ( output && (decodedAltitude != - 10000f) )
            {
                s += lastLon.toString() + "," + lastLat.toString() + "," + ((decodedAltitude + homePointAltitudeMSL) ).toString() + " "
            }
        }

        fileWriter.write(s)
        fileWriter.write("\n")

        addKMLFooter(fileWriter, altitudeMode, firstLon, firstLat, firstAlt, homePointAltitudeMSL)

        fileWriter.flush();
        fileWriter.close()
    }

    interface DataReadyListener {
        fun onUpdate(percent: Int)
        fun onDataReady(size: Int)
        fun onPlaybackPositionChange(prevPosition: Int, nextPosition: Int)
        fun onPlaybackStateChange( isPlaying : Boolean)
        fun getTotalPlaybackDurationSec() : Int
        fun getPlaybackAutostart() : Boolean
        fun onProtocolDetected( protocolName: String)
    }
}
