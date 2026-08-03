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
}
