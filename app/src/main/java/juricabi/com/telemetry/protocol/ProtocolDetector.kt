package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.decoder.DataDecoder

class ProtocolDetector(private val callback: Callback) {

    private val hits = arrayOf(0, 0, 0, 0, 0, 0, 0)
    private var detected = false
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

    fun feedData(data: Int) {
        if (detected) return
        sportProtocol.process(data)
        crsfProtocol.process(data)
        ltmProtocol.process(data)
        mavLinkProtocol.process(data)
        mavLink2Protocol.process(data)
        linkTestProtocol.process(data)
        ghstProtocol.process(data)

        for (index in hits.indices) {
            if (hits[index] < 2) continue
            detected = true
            callback.onProtocolDetected(
                when (index) {
                    0 -> sportProtocol
                    1 -> crsfProtocol
                    2 -> ltmProtocol
                    3 -> mavLinkProtocol
                    4 -> mavLink2Protocol
                    5 -> linkTestProtocol
                    else -> ghstProtocol
                }
            )
            return
        }
    }

    interface Callback {
        fun onProtocolDetected(protocol: Protocol?)
    }
}
