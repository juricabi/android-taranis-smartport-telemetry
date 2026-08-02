package crazydude.com.telemetry.protocol

import android.util.Log

import crazydude.com.telemetry.protocol.decoder.DataDecoder

class ProtocolDetector(private val callback: Callback) {

    private val hits = arrayOf(0, 0, 0, 0, 0, 0, 0)
    private val sportProtocol =
        FrSkySportProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[0]++
            }
        })
    private val crsfProtocol =
        CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[1]++
            }
        })
    private val ltmProtocol =
        LTMProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[2]++
            }
        })
    private val mavLinkProtocol =
        MAVLinkProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[3]++
            }
        })
    private val mavLink2Protocol =
        MAVLink2Protocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[4]++
            }
        })

    private val linkTestProtocol =
        LinkTestProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[5]++
            }
        })

    private val ghstProtocol =
        GhstProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[6]++
            }
        })

    private val dbgHex = StringBuilder()
    private var dbgCount = 0

    fun feedData(data: Int) {
        if (dbgCount < 512) {
            dbgHex.append(String.format("%02X ", data))
            dbgCount++
            if (dbgCount % 128 == 0) {
                Log.d("ProtoDetect", "RAW[$dbgCount]: $dbgHex")
                dbgHex.setLength(0)
            }
        }
        sportProtocol.process(data)
        crsfProtocol.process(data)
        ltmProtocol.process(data)
        mavLinkProtocol.process(data)
        mavLink2Protocol.process(data)
        linkTestProtocol.process(data)
        ghstProtocol.process(data)


        hits.forEachIndexed { index, i ->
            if (i == 1) {
                Log.d("ProtoDetect", "hit on index $index (hits=${hits.joinToString(",")})")
            }
            if (i >= 2) {
                when (index) {
                    0 -> callback.onProtocolDetected(sportProtocol)
                    1 -> callback.onProtocolDetected(crsfProtocol)
                    2 -> callback.onProtocolDetected(ltmProtocol)
                    3 -> callback.onProtocolDetected(mavLinkProtocol)
                    4 -> callback.onProtocolDetected(mavLink2Protocol)
                    5 -> callback.onProtocolDetected(linkTestProtocol)
                    6 -> callback.onProtocolDetected(ghstProtocol)
                }
            }
        }
    }

    interface Callback {
        fun onProtocolDetected(protocol: Protocol?)
    }
}