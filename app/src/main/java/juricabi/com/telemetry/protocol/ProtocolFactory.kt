package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.decoder.DataDecoder

/**
 * Turning a detected protocol into a live one, in a single place rather than a
 * copy of the same `when` in each of the four pollers.
 */
object ProtocolFactory {

    /**
     * A fresh decoder of the same kind as [detected], or null if unrecognised.
     * These are all siblings, so branch order carries no meaning.
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
