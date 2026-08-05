package juricabi.com.telemetry.protocol.pollers

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import juricabi.com.telemetry.protocol.Protocol
import juricabi.com.telemetry.protocol.ProtocolDetector
import juricabi.com.telemetry.protocol.ProtocolFactory
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.HashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class BluetoothLeDataPoller(
    context: Context,
    device: BluetoothDevice,
    private val listener: DataDecoder.Listener,
    outputStream: FileOutputStream?
) : DataPoller {

    private val log = BestEffortLog(outputStream)

    companion object {
        private const val SETUP_TIMEOUT_MS = 10000L
        private const val CRSF_MTU = 67
        private val CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    private val stateLock = Any()
    private val protocolDetectors = HashMap<BluetoothGattCharacteristic, ProtocolDetector>()
    private val descriptorQueue = ArrayDeque<BluetoothGattDescriptor>()

    private var bluetoothGatt: BluetoothGatt? = null
    private var descriptorInFlight: BluetoothGattDescriptor? = null
    private var discoveryRequested = false
    private var servicesConfigured = false
    private var timeoutArmed = false
    private var physicallyConnected = false
    private var ready = false
    private var selectedCharacteristic: BluetoothGattCharacteristic? = null
    private var selectedProtocol: Protocol? = null

    private val setupTimeout = Runnable {
        finish(connectionFailed = true, onlyBeforeReady = true)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (finished.get()) return

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(connectionFailed = true)
                        return
                    }
                    synchronized(stateLock) { physicallyConnected = true }
                    armSetupTimeout()
                    val mtuStarted = try {
                        gatt.requestMtu(CRSF_MTU)
                    } catch (_: Exception) {
                        false
                    }
                    if (!mtuStarted) startServiceDiscovery(gatt)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    finish(
                        connectionFailed = !isPhysicallyConnected(),
                        disconnectGatt = false
                    )
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (!finished.get()) startServiceDiscovery(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (finished.get()) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(connectionFailed = true)
                return
            }

            val shouldConfigure = synchronized(stateLock) {
                if (servicesConfigured) {
                    false
                } else {
                    servicesConfigured = true
                    true
                }
            }
            if (!shouldConfigure) return

            val characteristics = gatt.services
                .flatMap { it.characteristics }
                .filter {
                    it.properties and (
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE
                        ) != 0
                }

            for (characteristic in characteristics) {
                val notificationSet = try {
                    gatt.setCharacteristicNotification(characteristic, true)
                } catch (_: Exception) {
                    false
                }
                if (!notificationSet) continue
                val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                    ?: continue

                val detector = ProtocolDetector(object : ProtocolDetector.Callback {
                    override fun onProtocolDetected(protocol: Protocol?) {
                        selectProtocol(characteristic, protocol)
                    }
                })
                synchronized(stateLock) {
                    protocolDetectors[characteristic] = detector
                    descriptor.value =
                        if (
                            characteristic.properties and
                            BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                        ) {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        }
                    descriptorQueue.addLast(descriptor)
                }
            }

            if (synchronized(stateLock) { protocolDetectors.isEmpty() }) {
                finish(connectionFailed = true)
                return
            }
            writeNextDescriptor(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            synchronized(stateLock) {
                if (descriptorInFlight !== descriptor) return
                descriptorInFlight = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    protocolDetectors.remove(descriptor.characteristic)
                }
            }
            writeNextDescriptor(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (finished.get()) return
            val bytes = characteristic.value ?: return

            val live = synchronized(stateLock) {
                if (ready && selectedCharacteristic === characteristic) selectedProtocol else null
            }
            if (live != null) {
                try {
                    log.write(bytes)
                    for (byte in bytes) {
                        if (finished.get()) return
                        listener.onTelemetryByte()
                        live.process(byte.toUByte().toInt())
                    }
                    if (!finished.get()) listener.commit()
                } catch (_: Exception) {
                    finish(connectionFailed = false)
                }
                return
            }

            val detector = synchronized(stateLock) {
                if (ready) null else protocolDetectors[characteristic]
            } ?: return
            for (byte in bytes) {
                if (finished.get() || isReady()) return
                listener.onTelemetryByte()
                detector.feedData(byte.toUByte().toInt())
            }
            if (!finished.get() && !isReady()) listener.commit()
        }
    }

    init {
        bluetoothGatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            null
        }
        if (bluetoothGatt == null) finish(connectionFailed = true, disconnectGatt = false)
    }

    private fun armSetupTimeout() {
        val arm = synchronized(stateLock) {
            if (timeoutArmed) {
                false
            } else {
                timeoutArmed = true
                true
            }
        }
        if (arm) mainHandler.postDelayed(setupTimeout, SETUP_TIMEOUT_MS)
    }

    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        val start = synchronized(stateLock) {
            if (discoveryRequested || finished.get()) {
                false
            } else {
                discoveryRequested = true
                true
            }
        }
        if (!start) return

        val started = try {
            gatt.discoverServices()
        } catch (_: Exception) {
            false
        }
        if (!started) finish(connectionFailed = true)
    }

    private fun writeNextDescriptor(gatt: BluetoothGatt) {
        if (finished.get()) return

        val descriptor = synchronized(stateLock) {
            if (descriptorInFlight != null) return
            descriptorQueue.pollFirst()?.also { descriptorInFlight = it }
        }
        if (descriptor == null) {
            if (synchronized(stateLock) { !ready && protocolDetectors.isEmpty() }) {
                finish(connectionFailed = true)
            }
            return
        }

        val started = try {
            gatt.writeDescriptor(descriptor)
        } catch (_: Exception) {
            false
        }
        if (!started) {
            synchronized(stateLock) {
                if (descriptorInFlight === descriptor) descriptorInFlight = null
                protocolDetectors.remove(descriptor.characteristic)
            }
            writeNextDescriptor(gatt)
        }
    }

    private fun selectProtocol(
        characteristic: BluetoothGattCharacteristic,
        detected: Protocol?
    ) {
        val live = ProtocolFactory.create(detected, listener) ?: run {
            synchronized(stateLock) { protocolDetectors.remove(characteristic) }
            return
        }
        val selected = synchronized(stateLock) {
            if (finished.get() || ready) {
                false
            } else {
                selectedCharacteristic = characteristic
                selectedProtocol = live
                ready = true
                protocolDetectors.clear()
                descriptorQueue.clear()
                true
            }
        }
        if (!selected) return

        mainHandler.removeCallbacks(setupTimeout)
        listener.onProtocolDetected(ProtocolFactory.nameOf(live))
        mainHandler.post { listener.onConnected() }
    }

    private fun isReady(): Boolean = synchronized(stateLock) { ready }

    private fun finish(
        connectionFailed: Boolean,
        disconnectGatt: Boolean = true,
        onlyBeforeReady: Boolean = false
    ) {
        val claimed = synchronized(stateLock) {
            if (onlyBeforeReady && ready) return@synchronized false
            if (!finished.compareAndSet(false, true)) return@synchronized false
            protocolDetectors.clear()
            descriptorQueue.clear()
            descriptorInFlight = null
            physicallyConnected = false
            true
        }
        if (!claimed) return
        mainHandler.removeCallbacks(setupTimeout)

        val gatt = bluetoothGatt
        bluetoothGatt = null
        if (disconnectGatt) {
            try {
                gatt?.disconnect()
            } catch (_: Exception) {
            }
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        log.close()

        mainHandler.post {
            if (connectionFailed) {
                listener.onConnectionFailed()
            } else {
                listener.onDisconnected()
            }
        }
    }

    override fun disconnect() {
        finish(connectionFailed = !isPhysicallyConnected())
    }

    private fun isPhysicallyConnected(): Boolean =
        synchronized(stateLock) { physicallyConnected }
}
