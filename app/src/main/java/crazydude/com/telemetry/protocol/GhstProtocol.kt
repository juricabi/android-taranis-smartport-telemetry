package crazydude.com.telemetry.protocol

import crazydude.com.telemetry.protocol.crc.CRC8
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.protocol.decoder.GhstDataDecoder

// GHST (ImmersionRC Ghost) telemetry, as mirrored by EdgeTX/OpenTX.
//
// Frame layout is the same shape as CRSF: address, length, type, payload, crc8.
// Downlink frames addressed to the radio use address 0x80 and are 14 bytes
// total (length = 12, so 11 bytes of type+payload plus one crc byte).
class GhstProtocol : Protocol {

    constructor(dataListener: DataDecoder.Listener) : super(GhstDataDecoder(dataListener))
    constructor(dataDecoder: DataDecoder) : super(dataDecoder)

    private var buffer = ArrayList<Int>()
    private val crC8 = CRC8()

    companion object {

        // Kept for the whole session rather than per instance. A reconnect
        // builds a fresh protocol, and taking the launch height from the first
        // fix after one would zero the altitude of a model still in the air —
        // reading 0 at 120m and going negative on the way down. Cleared when a
        // link is started by hand, or when a log is replayed.
        @Volatile
        private var launchAltitude: Int? = null

        fun forgetLaunchAltitude() {
            launchAltitude = null
        }

        private const val RADIO_ADDRESS = 0x80

        private const val LINK_STAT = 0x21
        private const val PACK_STAT = 0x23
        private const val GPS_PRIMARY = 0x25
        private const val GPS_SECONDARY = 0x26

        private const val MAX_BUFFER_FILL_LIMIT = 128
        private const val MIN_BUFFER_FILL_LEVEL_BEFORE_LOOKING_FOR_VALID_PACKETS = 20
        // telemetry frames are 12 bytes; the module menu frame is 25
        private const val MAX_PAYLOAD_SIZE = 25
        private const val MIN_PAYLOAD_SIZE = 12

        // type + 10 payload bytes, the crc byte is stripped before processing
        private const val FRAME_PAYLOAD_LEN = 11
    }

    override fun process(data: Int) {
        buffer.add(data)
        if (buffer.size > MAX_BUFFER_FILL_LIMIT) {
            buffer.removeAt(0)
        }
        if (buffer.size > MIN_BUFFER_FILL_LEVEL_BEFORE_LOOKING_FOR_VALID_PACKETS) {
            getAndProcessValidPackets()
        }
    }

    private fun getAndProcessValidPackets() {
        var startCharPos = 0
        var pos = 0
        while (pos < buffer.size) {
            if (buffer[pos] == RADIO_ADDRESS) {
                startCharPos = pos
                if (pos + 1 < buffer.size) {
                    val packetLen = buffer[pos + 1]
                    if ((packetLen <= MAX_PAYLOAD_SIZE) && (packetLen >= MIN_PAYLOAD_SIZE)) {
                        if (pos + 1 + packetLen < buffer.size) {
                            val frameCrc = buffer[pos + 1 + packetLen]
                            val payload = buffer.subList(pos + 2, pos + 1 + packetLen)
                            crC8.reset()
                            payload.map { it.toByte() }.forEach { crC8.update(it) }
                            val calculatedCrc = crC8.value.toUByte().toInt()
                            if (frameCrc == calculatedCrc) {
                                proccessFrame(payload.map { it.toByte() }.toByteArray())
                                buffer.subList(0, pos + 2 + packetLen).clear()
                                return
                            }
                        } else {
                            break
                        }
                    }
                } else {
                    break
                }
            }
            pos++
        }
        if (startCharPos > 0) {
            buffer.subList(0, startCharPos).clear()
        }
    }

    private fun u8(d: ByteArray, i: Int): Int = d[i].toInt() and 0xFF

    private fun u16le(d: ByteArray, i: Int): Int = u8(d, i) or (u8(d, i + 1) shl 8)

    private fun u16be(d: ByteArray, i: Int): Int = (u8(d, i) shl 8) or u8(d, i + 1)

    private fun s16le(d: ByteArray, i: Int): Int {
        val v = u16le(d, i)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun s32le(d: ByteArray, i: Int): Int =
        u8(d, i) or (u8(d, i + 1) shl 8) or (u8(d, i + 2) shl 16) or (u8(d, i + 3) shl 24)

    private fun proccessFrame(data: ByteArray) {
        if (data.size != FRAME_PAYLOAD_LEN) {
            return
        }
        when (u8(data, 0)) {
            LINK_STAT -> {
                // rssi is sent as a positive number of dB below zero.
                // the firmware clamps these before use, so do the same
                val rssi = -Math.min(u8(data, 1), 120)
                val lq = Math.min(u8(data, 2), 100)
                val snr = Math.min(u8(data, 3), 100)
                val txPower = u16be(data, 4)

                dataDecoder.decodeData(Protocol.Companion.TelemetryData(RSSI, rssi))
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(CRSF_UP_LQ, lq))
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(UP_SNR, snr))
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(POWER, txPower))
                dataDecoder.decodeData(
                    Protocol.Companion.TelemetryData(ELRS_RF_MODE, u8(data, 10))
                )

                // Ghost carries no flight mode, so treat a live link as armed.
                // This gives the map a home position to measure distance from.
                processArmed(lq > 0)
            }
            PACK_STAT -> {
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(VBAT_OR_CELL, u16le(data, 1)))
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(CURRENT, u16le(data, 3)))
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(FUEL, u16le(data, 5) * 10))
            }
            GPS_PRIMARY -> {
                // Degrees times ten million, which is what CRSF sends too, and
                // what the rest of this app expects. EdgeTX divides these by
                // ten, but only to reach its own internal millionths — copying
                // that here put every Ghost fix a tenth of the way from the
                // equator to where it actually was.
                val latitude = s32le(data, 1)
                val longitude = s32le(data, 5)
                val altitude = s16le(data, 9)

                dataDecoder.decodeData(Protocol.Companion.TelemetryData(GPS_LATITUDE, latitude))
                processLatitude(latitude / 10000000.toDouble())
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(GPS_LONGITUDE, longitude))
                processLongitude(longitude / 10000000.toDouble())

                // Ghost reports height above sea level. The app also wants height
                // above the launch point, so remember the first fix and subtract
                // it; sending the sea level figure for both would be counted
                // twice when a track is exported.
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(GPS_ALTITUDE, altitude))
                if (latitude != 0 || longitude != 0) {
                    if (launchAltitude == null) {
                        launchAltitude = altitude
                    }
                    val relative = altitude - (launchAltitude ?: altitude)
                    dataDecoder.decodeData(Protocol.Companion.TelemetryData(ALTITUDE, relative))
                }
            }
            GPS_SECONDARY -> {
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(GSPEED, u16le(data, 1)))
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(HEADING, u16le(data, 3)))
                dataDecoder.decodeData(Protocol.Companion.TelemetryData(GPS_SATELLITES, u8(data, 5)))
            }
        }
    }
}
