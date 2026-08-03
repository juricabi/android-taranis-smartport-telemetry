package crazydude.com.telemetry.protocol

import crazydude.com.telemetry.protocol.decoder.DataDecoder

/**
 * Turning a detected protocol into a live one, in a single place.
 *
 * Every poller used to carry its own copy of the same forty line `when` — four
 * copies of it — which is four places to forget about when a protocol is added,
 * and four places for the display names to drift apart.
 *
 * The names returned here are exactly the strings the pollers have always
 * passed to [DataDecoder.Listener.onProtocolDetected], so nothing the user sees
 * changes.
 */
object ProtocolFactory {

    const val AUTO_DETECT = "Auto-detect"

    /**
     * A fresh decoder of the same kind as [detected], bound to [listener].
     * Null for anything unrecognised, which every caller treats as a failed
     * detection.
     *
     * The subclasses are all siblings of [Protocol] — MAVLink v2 does not
     * extend v1 — so the order of these branches carries no meaning.
     */
    fun create(detected: Protocol?, listener: DataDecoder.Listener): Protocol? = when (detected) {
        is FrSkySportProtocol -> FrSkySportProtocol(listener)
        is CrsfProtocol -> CrsfProtocol(listener)
        is GhstProtocol -> GhstProtocol(listener)
        is LTMProtocol -> LTMProtocol(listener)
        is MAVLinkProtocol -> MAVLinkProtocol(listener)
        is MAVLink2Protocol -> MAVLink2Protocol(listener)
        is LinkTestProtocol -> LinkTestProtocol(listener)
        else -> null
    }

    /** The name reported for a live protocol. */
    fun nameOf(protocol: Protocol): String = when (protocol) {
        is FrSkySportProtocol -> "FrSky"
        is CrsfProtocol -> "CRSF"
        is GhstProtocol -> "GHST"
        is LTMProtocol -> "LTM"
        is MAVLinkProtocol -> "Mavlink v1"
        is MAVLink2Protocol -> "Mavlink v2"
        is LinkTestProtocol -> "Link Test"
        else -> "Unknown"
    }

    /**
     * What the user may pick when they would rather name the protocol than let
     * the detector find it. Worth having on a network connection in
     * particular: a stream joined half way through a frame can mislead the
     * detector, and a TBS transmitter switches between CRSF and MAVLink on its
     * own depending on what it sees from the aircraft.
     */
    val choices: List<String> = listOf(
        AUTO_DETECT,
        "CRSF",
        "GHST",
        "Mavlink v2",
        "Mavlink v1",
        "FrSky",
        "LTM"
    )

    /** A protocol by the name used in [choices]; null means detect it instead. */
    fun createByName(name: String?, listener: DataDecoder.Listener): Protocol? = when (name) {
        "CRSF" -> CrsfProtocol(listener)
        "GHST" -> GhstProtocol(listener)
        "Mavlink v1" -> MAVLinkProtocol(listener)
        "Mavlink v2" -> MAVLink2Protocol(listener)
        "FrSky" -> FrSkySportProtocol(listener)
        "LTM" -> LTMProtocol(listener)
        else -> null
    }
}
