package juricabi.com.telemetry.protocol.decoder

/**
 * One reading, several ears. The targets are read through providers on every
 * delivery, because who is listening changes — the screen unbinds on
 * rotation, the CSV logger is born and retired with each connection — and a
 * provider returning null is simply nobody there.
 *
 * A subclass of ForwardingListener so there is exactly one forwarding table:
 * this used to hand-mirror all forty methods, and a callback added to one
 * mirror but not the other silently dropped a reading — the bug class these
 * classes exist to end.
 */
class MulticastListener(
    private vararg val targets: () -> DataDecoder.Listener?
) : ForwardingListener() {

    override fun relay(deliver: (DataDecoder.Listener) -> Unit) {
        for (target in targets) target()?.let(deliver)
    }
}
