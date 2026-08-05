package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.decoder.CrsfDataDecoder
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.nio.ByteBuffer


class CrsfProtocol : Protocol {

    constructor(dataListener: DataDecoder.Listener) : super(CrsfDataDecoder(dataListener))
    constructor(dataDecoder: DataDecoder) : super(dataDecoder)

    private var buffer = ArrayList<Int>()
    private val crC8 = CRC8()

    /**
     * Whether this link sends the barometric altitude frame. Once it does, that
     * frame is the altitude: it always means height above the launch, while the
     * one in the GPS frame means that on Betaflight and height above the sea on
     * INAV and ArduPilot.
     */
    private var hasBarometricAltitude = false

    companion object {

        // Device or sync byte:Length:Type:Payload:CRC
        private const val RADIO_ADDRESS = 0xEA
        private const val CROSSFIRE_RX_ADDRESS = 0xEC;// R/C Receiver / Crossfire Rx
        private const val CROSSFIRE_TX_ADDRESS = 0xEE;//  R/C Transmitter Module / Crossfire Tx
        private const val SYNC_BYTE = 0xC8

        private const val GPS_TYPE = 0x02
        private const val VARIO_TYPE = 0x07
        private const val BATTERY_TYPE = 0x08
        private const val BAROALT_SENSOR = 0x09
        private const val AIRSPEED_SENSOR = 0x0A
        private const val LINK_STATS = 0x14

        /**
         * DEVICE_INFO. Answered when a device is pinged, and the only reliable
         * way to tell an ExpressLRS transmitter from a Crossfire or a Tracer —
         * they all speak CRSF and number their RF modes differently.
         */
        private const val DEVICE_INFO_TYPE = 0x29
        private const val ATTITUDE_TYPE = 0x1E
        private const val FLIGHT_MODE = 0x21
        private const val RC_CHANNELS_PACKED = 0x16

        private const val MAX_BUFFER_FILL_LIMIT = 128
        private const val MAX_PAYLOAD_SIZE = 62
        // CRSF length counts type + payload + CRC. A frame with no payload is
        // therefore four bytes on the wire; supported vario, barometer, and
        // airspeed frames are only six bytes.
        private const val MIN_PACKET_LENGTH = 2
        private const val MIN_FRAME_SIZE = 4

        //packet length + 1 byte crc
        private const val MIN_FLIGHT_MODE_PACKET_LEN = 4
        private const val GPS_PACKET_LEN = 16
        private const val ATTITUDE_PACKET_LEN = 7
        private const val AIRSPEED_PACKET_LEN = 3
        private const val BAROALT_PACKET_LEN = 3
        private const val BATTERY_PACKET_LEN = 9
        private const val VARIO_PACKET_LEN = 3
        private const val LINK_STATS_PACKET_LEN = 11
        private const val RC_CHANNELS_PACKED_PACKET_LEN = 23;
    }

    override fun process(data: Int) {
        buffer.add(data)
        if (buffer.size > MAX_BUFFER_FILL_LIMIT) {
            buffer.removeAt(0)
        }
        if (buffer.size >= MIN_FRAME_SIZE) {
            //Get and process any valid packets in the input buffer, removing data from the input buffer as we go
            getAndProcessValidPackets()
        }
    }

    private fun getAndProcessValidPackets() {
        var startCharPos = 0
        var pos = 0
        // Scan the whole input buffer
        while (pos < buffer.size) {
            // look for start characters
            if ((buffer[pos] == RADIO_ADDRESS) ||
                (buffer[pos] == CROSSFIRE_RX_ADDRESS) ||
                (buffer[pos] == CROSSFIRE_TX_ADDRESS) ||
                (buffer[pos] == SYNC_BYTE)
            )
            {
                startCharPos = pos
                // is the input buffer big enough to include a length field for this start of packet
                if (pos + 1 < buffer.size) {
                    val packetLen = buffer[pos + 1]
                    if ((packetLen <= MAX_PAYLOAD_SIZE) && (packetLen >= MIN_PACKET_LENGTH)) {
                        // is the input buffer big enough to include the whole packet (as specified by the length field)
                        if (pos + 1 + packetLen < buffer.size) {
                            // Get the CRC from the packet and check it against what we think it should be
                            val frameCrc = buffer[pos + 1 + packetLen]
                            val payload = buffer.subList(pos + 2, pos + 1 + packetLen)
                            crC8.reset()
                            payload.map { it.toByte() }.forEach { crC8.update(it) }
                            val calculatedCrc = crC8.value.toUByte().toInt()
                            if (frameCrc == calculatedCrc) {
                                //Log.d("CrsfProtocol", "Good frame $payload")
                                proccessFrame(payload.map { it.toByte() }.toByteArray())
                                buffer.subList(0, pos + 2 + packetLen).clear()
                                return
                            }
                        } else {
                            // potential valid packet, but there is not enough data in the input buffer to process it yet
                            break
                        }
                    }
                } else {
                    break
                }
            }
            pos++
        }
        // remove any data in front of the last processed start character
        if (startCharPos > 0) {
            buffer.subList(0, startCharPos).clear()
        }
    }

    private fun remapChannel(value : Int) : Int {
        //The values are CRSF channel values (0-1984).
        // CRSF 172 represents 988us, CRSF 992 represents 1500us,
        // and CRSF 1811 represents 2012us.
        // Example: All channels set to 1500us (992)
        var v = value - 992;
        v = v * (2012 - 988) / (1811-172);
        return v + 1500;
    }

    private fun proccessFrame(inputData: ByteArray) {
        if (inputData.size > 0) {
            val data = ByteBuffer.wrap(inputData)
            val type = data.get()
            when (type) {
                BATTERY_TYPE.toByte() -> {
                    if (inputData.size == BATTERY_PACKET_LEN) {
                        val voltage = data.short
                        val current = data.short
                        val capacityArray = ByteArray(4)
                        data.get(capacityArray, 1, 3)
                        val capacity = ByteBuffer.wrap(capacityArray).int

                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( VBAT_OR_CELL, voltage.toInt()))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( CURRENT, current.toInt()))
                        dataDecoder.decodeData(Protocol.Companion.TelemetryData(FUEL, capacity))
                    }
                }
                GPS_TYPE.toByte() -> {
                    if (inputData.size == GPS_PACKET_LEN) {
                        val latitude = data.int
                        val longitude = data.int
                        val groundSpeed = data.short
                        val heading = data.short
                        // sent as metres plus a thousand, so that a hundred
                        // metres below sea level still fits an unsigned field
                        val altitude = (data.short.toInt() and 0xFFFF) - 1000
                        val satellites = data.get()

                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( GPS_SATELLITES, satellites.toInt()))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( GPS_ALTITUDE, altitude))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( GPS_LATITUDE,latitude ))
                        this.processLatitude(latitude / 10000000.toDouble());
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( GPS_LONGITUDE, longitude ))
                        this.processLongitude(longitude / 10000000.toDouble());
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( GSPEED,groundSpeed.toInt()))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( HEADING, heading.toInt()))
                        // Only while nothing better has been heard. This is a
                        // height above the sea on INAV and ArduPilot and a
                        // height above the arming point on Betaflight, and the
                        // barometric frame is the one that always means the
                        // second. Sending both here made the altitude readout
                        // alternate between the two several times a second.
                        if (!hasBarometricAltitude) {
                            dataDecoder.decodeData( Protocol.Companion.TelemetryData( ALTITUDE, altitude.toInt() ))
                        }
                    }
                }
                VARIO_TYPE.toByte() -> {
                    if (inputData.size == VARIO_PACKET_LEN) {
                        val vspeed = data.short

                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( VSPEED, vspeed.toInt()))
                    }
                }
                DEVICE_INFO_TYPE.toByte() -> {
                    // type, destination, origin, then a null terminated name
                    if (inputData.size > 3) {
                        data.get()      // destination
                        data.get()      // origin
                        val name = StringBuilder()
                        while (data.hasRemaining() && name.length < 32) {
                            val c = data.get().toInt() and 0xFF
                            if (c == 0) break
                            if (c in 32..126) name.append(c.toChar())
                        }
                        if (name.isNotEmpty()) {
                            dataDecoder.decodeData(
                                Protocol.Companion.TelemetryData(
                                    Protocol.DEVICE_NAME, 0, name.toString().toByteArray()
                                )
                            )
                        }
                    }
                }
                LINK_STATS.toByte() -> {
                    if (inputData.size == LINK_STATS_PACKET_LEN) {
                        val uplinkRSSIAnt1 = -1 * Math.abs( data.get().toByte().toInt()) // dBm
                        val uplinkRSSIAnt2 = -1 * Math.abs( data.get().toByte().toInt()) // dBm
                        val uplinkLQ = data.get().toUByte().toInt()
                        val uplinkSNR = data.get().toInt()
                        val activeAntenna = data.get().toUByte().toInt()
                        val rfMode = data.get().toUByte().toInt()
                        val uplinkTxPwr = data.get().toUByte().toInt()
                        val downlinkRSSI = -1 * Math.abs( data.get().toByte().toInt() ) // dBm
                        val downlinkLQ = data.get().toUByte().toInt()
                        val downlinkSNR = data.get().toInt()
                        val rssi = (if (activeAntenna == 1) uplinkRSSIAnt2 else uplinkRSSIAnt1)

                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( RSSI, rssi, inputData ))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( CRSF_UP_LQ, uplinkLQ, inputData))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( CRSF_DN_LQ, downlinkLQ, inputData))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( ELRS_RF_MODE, rfMode, inputData ))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( DN_SNR, downlinkSNR, inputData ))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( UP_SNR, uplinkSNR, inputData ) )
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( ANT, activeAntenna, inputData ) )
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( POWER, uplinkTxPwr, inputData ))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( RSSI_DBM_1, uplinkRSSIAnt1, inputData ))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( RSSI_DBM_2, uplinkRSSIAnt2, inputData ))
                        dataDecoder.decodeData( Protocol.Companion.TelemetryData( RSSI_DBM_D, downlinkRSSI, inputData ))
                    }
                }
                FLIGHT_MODE.toByte() -> {
                    if (inputData.size >= MIN_FLIGHT_MODE_PACKET_LEN) {
                        val stringLength = inputData.indexOfFirst { it == 0x00.toByte() }
                        if ( stringLength > 0 ) {
                            dataDecoder.decodeData( Protocol.Companion.TelemetryData( FLYMODE, 0, inputData ) )

                            val flightMode = String(inputData, 1, stringLength-1 )

                            when (flightMode) {
                                "AIR", "ACRO", "MANU", "RTH", "HOLD", "HRST", "3CRS","CRUZ", "CRS", "CRSH", "WP", "ANGL", "STAB", "HOR" -> {
                                    this.processArmed(true);
                                }
                                "WAIT", "!ERR", "OK" -> {
                                    this.processArmed(false);
                                }
                            }
                        }
                    }
                }
                ATTITUDE_TYPE.toByte() -> {
                    if (inputData.size == ATTITUDE_PACKET_LEN) {
                        val pitch = data.short
                        val roll = data.short
                        val yaw = data.short
                        dataDecoder.decodeData(Protocol.Companion.TelemetryData(PITCH, pitch.toInt()))
                        dataDecoder.decodeData(Protocol.Companion.TelemetryData(ROLL, roll.toInt()))
                        dataDecoder.decodeData(Protocol.Companion.TelemetryData(YAW, yaw.toInt()))
                    }
                }
                RC_CHANNELS_PACKED.toByte() -> {
                    if (inputData.size == RC_CHANNELS_PACKED_PACKET_LEN) {
                        var bitsMerged = 0
                        var readValue: UInt = 0U;
                        var ch = 0;

                        for (n in 0 until 16) {
                            while (bitsMerged < 11) {
                                val readByte = data.get().toUByte();
                                readValue = readValue or (readByte.toUInt() shl bitsMerged)
                                bitsMerged += 8
                            }
                            dataDecoder.decodeData(Protocol.Companion.TelemetryData(RC_CHANNEL_0 + ch, this.remapChannel((readValue and 0x7ffU).toInt())))
                            readValue = readValue shr 11;
                            bitsMerged -= 11;
                            ch++;
                        }
                    }
                }
                AIRSPEED_SENSOR.toByte() -> {
                    if (inputData.size == AIRSPEED_PACKET_LEN) {
                        val airspeed = data.short // 0.1 km/h
                        dataDecoder.decodeData(Protocol.Companion.TelemetryData(ASPEED, airspeed.toInt()))
                    }
                }
                BAROALT_SENSOR.toByte() -> {
                    if (inputData.size == BAROALT_PACKET_LEN) {
                        // Metres when the top bit is set, decimetres offset by
                        // ten thousand otherwise — the two cases EdgeTX handles
                        // in crossfire.cpp. Unpacked here, where the frame is
                        // known: the GPS frame feeds the same reading in plain
                        // metres, so nothing downstream can tell them apart.
                        val raw = data.short.toInt() and 0xFFFF
                        // Tenths below eight thousand metres, whole metres above
                        // it. Rounded rather than truncated: everything down the
                        // line carries whole metres, and truncation towards zero
                        // turned both half a metre up and half a metre down into
                        // nought, which is a step across the launch height.
                        val metres = if ((raw and 0x8000) != 0) {
                            raw and 0x7FFF
                        } else {
                            Math.round((raw - 10000) / 10f)
                        }
                        hasBarometricAltitude = true
                        dataDecoder.decodeData(Protocol.Companion.TelemetryData(ALTITUDE, metres))
                    }
                }
            }
        }
    }
}
