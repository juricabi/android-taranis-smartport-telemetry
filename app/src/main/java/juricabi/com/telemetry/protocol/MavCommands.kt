package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRCMAVLink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The few MAVLink frames this app writes, built byte by byte.
 *
 * There is no encoder library here and none is owed for two frames: the
 * network poller already writes one hand-built heartbeat, and this builds the
 * one command the high-latency mode needs. The sender identity matches that
 * heartbeat — system 255, component 190, a ground station — so an autopilot
 * sees one ground station speaking, not two.
 */
object MavCommands {

    private const val COMMAND_LONG = 76
    private const val MAV_CMD_CONTROL_HIGH_LATENCY = 2600

    /**
     * Ask the autopilot to start or stop its high-latency stream.
     *
     * A MAVLink 2 COMMAND_LONG carrying MAV_CMD_CONTROL_HIGH_LATENCY, param1
     * one or nought, broadcast to every system — ArduPilot accepts it on any
     * link and switches all its high-latency ports at once.
     */
    fun controlHighLatency(enable: Boolean, sequence: Int): ByteArray {
        val payload = ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN)
        payload.putFloat(if (enable) 1f else 0f)
        for (i in 2..7) payload.putFloat(0f)
        payload.putShort(MAV_CMD_CONTROL_HIGH_LATENCY.toShort())
        payload.put(0)   // target system: broadcast
        payload.put(0)   // target component: broadcast
        payload.put(0)   // confirmation
        val body = payload.array()

        // length, incompat, compat, seq, sys, comp, msgid low/mid/high
        val head = byteArrayOf(
            body.size.toByte(), 0, 0, (sequence and 0xFF).toByte(),
            0xFF.toByte(), 0xBE.toByte(),
            COMMAND_LONG.toByte(), 0, 0
        )
        val crc = CRCMAVLink()
        crc.start_checksum()
        head.forEach { crc.update_checksum(it.toInt() and 0xFF) }
        body.forEach { crc.update_checksum(it.toInt() and 0xFF) }
        crc.finish_checksum(COMMAND_LONG)
        return byteArrayOf(0xFD.toByte()) + head + body +
            byteArrayOf(crc.lsb.toByte(), crc.msb.toByte())
    }
}
