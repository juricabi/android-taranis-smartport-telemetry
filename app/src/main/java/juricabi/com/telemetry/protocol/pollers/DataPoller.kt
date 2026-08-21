package juricabi.com.telemetry.protocol.pollers

/**
 * A live telemetry connection. Constructing one starts it at once — a
 * synchronous failure can therefore arrive before the constructor returns,
 * which is why DataService installs a poller only after checking its
 * generation. The rest of the contract every transport keeps is the
 * chassis's — see PollerChassis. BluetoothLeDataPoller carries its own copy
 * of that machinery, because its bytes arrive as a race between GATT
 * characteristics rather than as one stream.
 */
interface DataPoller {
    fun disconnect()
}
