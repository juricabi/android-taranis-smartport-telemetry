package juricabi.com.telemetry.ui

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.AsyncTask
import android.os.Build
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialProber
import juricabi.com.telemetry.R
import juricabi.com.telemetry.manager.PreferenceManager
import juricabi.com.telemetry.protocol.pollers.NetworkDataPoller
import juricabi.com.telemetry.utils.LocalNetworks
import juricabi.com.telemetry.utils.NetworkBinder
import uk.co.deanwild.materialshowcaseview.IShowcaseListener
import uk.co.deanwild.materialshowcaseview.MaterialShowcaseView

/**
 * Choosing a link, whole: the four-way chooser, the Bluetooth and BLE device
 * lists with pairing, the USB probe and its permission ask, and the network
 * dialog with its presets, interfaces and subnet Find. Connecting stays the
 * activity's — this module ends where a device, an address or a port has
 * been chosen and hands it to the host's connectTo* — as does the reconnect
 * policy, whose state rides the activity's bundle.
 */
class ConnectFlow(
    private val host: MapsActivity,
    private val preferenceManager: PreferenceManager
) {

    // which roads were taken last, for the bold rows and the scroll-to
    private var lastSelectedDataPooler = preferenceManager.getLastSelectedDataPooler()
    private var lastSelectedBluetoothDeviceAddress =
        preferenceManager.getLastSelectedBluetoothDeviceAddress()
    private var lastSelectedBLEDeviceAddress =
        preferenceManager.getLastSelectedBLEDeviceAddress()

    /** The chooser; the activity resets its reconnect state before opening. */
    fun open() {
        val showcaseView = MaterialShowcaseView.Builder(host)
            .renderOverNavigationBar()
            .setTarget(host.replayButton)
            .setMaskColour(Color.argb(230, 0, 0, 0))
            .setDismissText("GOT IT")
            .setContentText("You can replay your logged flights by clicking this button")
            .setListener(
                object : IShowcaseListener {
                    override fun onShowcaseDismissed(showcaseView: MaterialShowcaseView?) {
                        host.connect();
                    }
                    override fun onShowcaseDisplayed(showcaseView: MaterialShowcaseView?) {
                    }
                })
            .singleUse("replay_guide").build()

        var items = arrayOf(
            "Bluetooth",
            "Bluetooth LE",
            "USB Serial",
            host.getString(R.string.network)
        )

        if (showcaseView.hasFired()) {
            host.showDialog(AlertDialog.Builder(host)
                .setAdapter(
                    ArrayAdapter(
                        host,
                        android.R.layout.simple_list_item_1,
                        items.map { i ->
                            if (i == lastSelectedDataPooler) {
                                val boldOption = SpannableString(i)
                                boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, i.length, 0)
                                boldOption
                            } else {
                                i
                            }
                        })
                ) { dialogInterface, i ->
                    lastSelectedDataPooler = items[i]
                    preferenceManager.setLastSelectedDataPooler(lastSelectedDataPooler)
                    when (i) {
                        0 -> connectBluetooth()
                        1 -> connectBluetoothLE()
                        2 -> connectUSB()
                        3 -> connectNetwork()
                    }
                }
                .setTitle("Choose connection method")
                .create())
        } else {
            showcaseView.show(host)
        }
    }

    /** Whether anything serial is attached at all — the reconnect's quiet probe. */
    fun hasSerialDevice(): Boolean {
        val usbManager = host.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).isNotEmpty()
    }

    /** [newSession] false continues a dropped link's flight and log. */
    internal fun connectUSB(newSession: Boolean = true) {
        val usbManager = host.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull()
        if (driver == null) {
            // Three different problems used to share one message. Naming what is
            // actually attached says which one it is: nothing plugged in at all,
            // or a radio sitting in Joystick or Storage mode instead of serial.
            val attached = usbManager.deviceList.values
            val message = if (attached.isEmpty()) {
                "No USB device attached. Check the cable supports data, and that the " +
                    "phone is not also plugged into a computer."
            } else {
                val names = StringBuilder()
                for (device in attached) {
                    if (names.isNotEmpty()) names.append(", ")
                    names.append(String.format("%04x:%04x", device.vendorId, device.productId))
                }
                "Attached (" + names + ") but not a serial port. On EdgeTX choose " +
                    "USB Serial (VCP) rather than Joystick or Storage."
            }
            host.showDialog(
                AlertDialog.Builder(host)
                    .setTitle("No serial device")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .create()
            )
        } else {
            val connection = usbManager.openDevice(driver.device)
            if (connection != null) {
                val port = driver.ports.firstOrNull()
                if (port == null) {
                    Toast.makeText(host, "No valid usb port has been found", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    host.connectToUSBDevice(port, connection, newSession)
                }
            } else {
                val pendingIntent =
                    PendingIntent.getBroadcast(
                        host,
                        0,
                        Intent(MapsActivity.ACTION_USB_DEVICE).setPackage(host.packageName),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                host.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (MapsActivity.ACTION_USB_DEVICE == intent?.action) {
                            synchronized(this) {
                                val device: UsbDevice? =
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                                if (intent.getBooleanExtra(
                                        UsbManager.EXTRA_PERMISSION_GRANTED,
                                        false
                                    )
                                ) {
                                    device?.apply {
                                        // A grant can land after the retry it
                                        // was asked for has died — timed out
                                        // behind this very dialog. The tap
                                        // still deserves its connect, but as
                                        // the fresh link it was announced to
                                        // be, not a continuation of a flight
                                        // the wait no longer owns.
                                        connectUSB(newSession || !host.usbReconnectArmed())
                                    }
                                } else {
                                    Toast.makeText(
                                        host,
                                        "You need to allow permission in order to connect with a usb",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }

                        host.unregisterReceiver(this)
                    }
                }, IntentFilter(MapsActivity.ACTION_USB_DEVICE))
                usbManager.requestPermission(driver.device, pendingIntent)
            }
        }
    }

    internal fun connectBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            host.showDialog(
                AlertDialog.Builder(host)
                    .setMessage("It seems like your phone does not have bluetooth, or it does not supported")
                    .setPositiveButton("OK", null)
                    .create()
            )
            return
        }

        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            host.startActivityForResult(enableBtIntent, MapsActivity.REQUEST_ENABLE_BT)
            return
        }
        if (preferenceManager.isLoggingEnabled()) {
            if (!host.requestWritePermission(MapsActivity.RequestWritePermissionSequenceType.CONNECT)) return;
        }

        val devices = ArrayList<BluetoothDevice>(adapter.bondedDevices)
        var deviceNames = ArrayList<String>(devices.map {
            var result = it.name
            if (result == null) {
                result = it.address
            }
            if (result == null) {
                result = "*noname*"
            }
            result
        })

        deviceNames = augmentNonUniqueDiviceNames(deviceNames, devices.map { i-> i.address })

        var deviceNames1 = deviceNames.mapIndexed { index, i ->
            if ( devices[index].address == lastSelectedBluetoothDeviceAddress ) {
                val boldOption = SpannableString(i)
                boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, i.length, 0)
                boldOption
            } else {
                i
            }
        }.toMutableList()

        val deviceAdapter = ArrayAdapter( host, android.R.layout.simple_list_item_1, deviceNames1)

        var dialog = AlertDialog.Builder(host).setOnDismissListener {
        } .setNeutralButton(R.string.pair_new_device) { dialog, which ->
            showPairDeviceDialog()
        }.setAdapter(deviceAdapter) { _, i ->
            lastSelectedBluetoothDeviceAddress = devices[i].address;
            preferenceManager.setLastSelectedBluetoothDeviceAddress(lastSelectedBluetoothDeviceAddress)
            host.runOnUiThread {
                host.connectToBluetoothDevice(devices[i], false)
            }
        }.create()

        dialog.setOnShowListener {
            val alertDialog = it as AlertDialog
            var index = devices.indexOfFirst {i -> i.address == lastSelectedBluetoothDeviceAddress}
            if ( index != -1) {
                val centerY = alertDialog.listView.height / 2 // Calculate the center position vertically
                alertDialog.listView.smoothScrollToPositionFromTop(index, centerY)
            }
        }

        host.showDialog(dialog)
    }

    private fun augmentNonUniqueDiviceNames(deviceNames : ArrayList<String>, deviceAddr : List<String>) : ArrayList<String>
    {
        return ArrayList(deviceNames.mapIndexed { index, i ->
            var i1 = deviceNames.indexOf(i)
            var i2 = deviceNames.lastIndexOf(i)
            if (i1 != i2) {
                "${deviceNames[index]} (${deviceAddr[index]})"
            } else {
                i
            }
        })
    }

    private fun showPairDeviceDialog() {
        val devices = ArrayList<BluetoothDevice>()
        val deviceNames = ArrayList<String>()
        val deviceAdapter =
            ArrayAdapter<String>(host, android.R.layout.simple_list_item_1, deviceNames)
        AlertDialog.Builder(host)
            .setAdapter(deviceAdapter) { _, i ->
                BluetoothAdapter.getDefaultAdapter().cancelDiscovery()
                pairDevice(devices[i])
            }.show()
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        host.unregisterReceiver(this)
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        val device =
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)!!
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                            ?: device.address
                        if (!deviceNames.contains(name) && device.bondState == BluetoothDevice.BOND_NONE) {
                            devices.add(device)
                            deviceNames.add(name)
                            deviceAdapter.notifyDataSetChanged()
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {

                    }
                }
            }
        }
        host.registerReceiver(listener, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
        })
        BluetoothAdapter.getDefaultAdapter().startDiscovery()
    }

    private fun pairDevice(bluetoothDevice: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (!bluetoothDevice.createBond()) {
                Toast.makeText(host, "Failed to pair bluetooth device", Toast.LENGTH_LONG).show()
            } else {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            val newBondState: Int =
                                intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE,
                                    BluetoothDevice.BOND_NONE
                                )
                            if (newBondState == BluetoothDevice.BOND_BONDED) {
                                device?.let { host.connectToBluetoothDevice(it, false) }
                                host.unregisterReceiver(this)
                            } else if (newBondState == BluetoothDevice.BOND_NONE) {
                                Toast.makeText(
                                    host,
                                    "Failed to pair new device",
                                    Toast.LENGTH_LONG
                                ).show()
                                host.unregisterReceiver(this)
                            }
                        }
                    }
                }

                host.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            }
        } else {
            AlertDialog.Builder(host)
                .setMessage(host.getString(R.string.pair_not_supported_message))
                .show()
        }
    }

    private fun connectBluetoothLE() {
        if (!bleCheck()) {
            Toast.makeText(
                host,
                "Bluetooth LE is not supported or application does not have needed permissions",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            host.showDialog(
                AlertDialog.Builder(host)
                    .setMessage("It seems like your phone does not have bluetooth, or it does not supported")
                    .setPositiveButton("OK", null)
                    .create()
            )
            return
        }

        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            host.startActivityForResult(enableBtIntent, MapsActivity.REQUEST_ENABLE_BT)
            return
        }
        if (preferenceManager.isLoggingEnabled()) {
            if (!host.requestWritePermission(MapsActivity.RequestWritePermissionSequenceType.CONNECT)) return;
        }

        val devices = ArrayList<BluetoothDevice>(adapter.bondedDevices)
        var deviceNames = ArrayList<String>(devices.map {
            var result = it.name
            if (result == null) {
                result = it.address
            }
            if (result == null) {
                result = "*noname*"
            }
            result
        })

        deviceNames = augmentNonUniqueDiviceNames(deviceNames, devices.map {i -> i.address})

        var deviceNames1 = deviceNames.mapIndexed { index, i ->
            if ( devices[index].address == lastSelectedBLEDeviceAddress ) {
                val boldOption = SpannableString(i)
                boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, i.length, 0)
                boldOption
            } else {
                i
            }
        }.toMutableList()

        val deviceAdapter = ArrayAdapter(host, android.R.layout.simple_list_item_1, deviceNames1)

        var scrolled = false;
        var dialog: AlertDialog? = null;

        val callback = BluetoothAdapter.LeScanCallback { bluetoothDevice, i, bytes ->
            if (!devices.contains(bluetoothDevice) && bluetoothDevice.name != null) {
                devices.add(bluetoothDevice)
                var name1 = bluetoothDevice.name
                if ( deviceNames.indexOf( name1) >= 0 ) {
                    name1 = "${bluetoothDevice.name} (${bluetoothDevice.address})"
                }
                if ( lastSelectedBLEDeviceAddress == bluetoothDevice.address) {
                    val boldOption = SpannableString(name1)
                    boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, name1.length, 0)
                    deviceNames1.add(boldOption)

                    if ( dialog is AlertDialog && scrolled) {
                        host.runOnUiThread {
                            var index = devices.indexOfFirst {i -> i.address == lastSelectedBLEDeviceAddress}
                            if ( index != -1) {
                                val alertDialog = dialog as AlertDialog
                                if ( alertDialog != null ) {
                                    val centerY =
                                        alertDialog.listView.height / 2 // Calculate the center position vertically
                                    alertDialog.listView.smoothScrollToPositionFromTop(
                                        index,
                                        centerY
                                    )
                                }
                            }
                        }
                    }
                }
                else {
                    deviceNames1.add(name1)
                }
                deviceAdapter.notifyDataSetChanged()
            }
        }

        if (bleCheck()) {
            adapter.startLeScan(callback)
        }

        dialog = AlertDialog.Builder(host).setOnDismissListener {
            if (bleCheck()) {
                adapter.stopLeScan(callback)
            }
        }.setAdapter(deviceAdapter) { _, i ->
            lastSelectedBLEDeviceAddress = devices[i].address;
            preferenceManager.setLastSelectedBLEDeviceAddress(lastSelectedBLEDeviceAddress)
            if (bleCheck()) {
                adapter.stopLeScan(callback)
            }
            host.runOnUiThread {
                host.connectToBluetoothDevice(devices[i], true)
            }
        }.create()

        dialog.setOnShowListener {
            val alertDialog = it as AlertDialog
            var index = devices.indexOfFirst {i -> i.address == lastSelectedBLEDeviceAddress}
            if ( index != -1) {
                val centerY = alertDialog.listView.height / 2 // Calculate the center position vertically
                alertDialog.listView.smoothScrollToPositionFromTop(index, centerY)
            }
            scrolled = true;
        }

        host.showDialog(dialog)
    }

    private fun bleCheck() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && ContextCompat.checkSelfPermission(
            host,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Where the telemetry is coming from, for the presets.
     *
     * The two that matter are ExpressLRS, which broadcasts MAVLink to UDP 14550
     * so there is nothing to address, and TBS Crossfire, whose WiFi module is a
     * server on TCP 8888 and can be switched to UDP on the module itself. Both
     * ports are configurable at their end, so nothing here is fixed — a preset
     * only fills the fields in.
     */
    private class NetworkPreset(
        val label: String,
        /**
         * The stored identity: remembered ports, hosts and the last-used
         * preset are saved under this number, never under the list position —
         * so presets can be ordered for the eye without handing anyone's
         * settings to a neighbour. Keys match the positions of the releases
         * that stored them; a new preset takes the next unused number,
         * wherever it sits in the list.
         */
        val key: Int,
        val useTcp: Boolean,
        val port: Int,
        val useGateway: Boolean,
        /** a fixed address, where the preset knows it */
        val host: String? = null,
        /** transport, matching the order of the transport spinner */
        val mode: Int = if (useTcp) NetworkDataPoller.MODE_TCP_CLIENT else NetworkDataPoller.MODE_UDP,
        /** MAVLink High Latency: pin the protocol and send the enable command */
        val highLatency: Boolean = false
    )

    // The transport stays in the name because it is the thing that decides
    // whether an address is needed at all. The port does not: it lands in the
    // port field the moment the preset is picked.
    private val networkPresets = listOf(
        NetworkPreset("ExpressLRS backpack (UDP)", 0, false, 14550, false),
        NetworkPreset("TBS Crossfire / Tracer (TCP)", 1, true, 8888, true),
        NetworkPreset("TBS Crossfire / Tracer (UDP)", 2, false, 8888, false),
        NetworkPreset("MAVLink router / ground station (UDP)", 3, false, 14550, false),
        // A satellite- or LoRa-class link: one HIGH_LATENCY2 message per five
        // seconds. The autopilot boots with that stream off, so this preset
        // also sends the command that turns it on — to the typed address, and
        // to whoever speaks to us.
        NetworkPreset("MAVLink High Latency (UDP)", 7, false, 14550, false,
            highLatency = true),
        NetworkPreset("Serial to Wi-Fi bridge (TCP)", 4, true, 23, true),
        // The one path into a Crossfire WiFi module that every firmware
        // serves: its own phone app uses MQTT, which needs a broker in the app
        // and is broken on the newest firmware, while this carries plain CRSF.
        NetworkPreset(
            "TBS Crossfire WiFi (WebSocket)", 5, true, 80, true,
            mode = NetworkDataPoller.MODE_WEBSOCKET
        ),
        NetworkPreset("Custom", 6, false, 14550, false)
    )

    private fun connectNetwork() {
        // as the Bluetooth and BLE paths do: without it a network session
        // silently records nothing while both logging switches say "on"
        if (preferenceManager.isLoggingEnabled()) {
            if (!host.requestWritePermission(MapsActivity.RequestWritePermissionSequenceType.CONNECT)) return
        }

        val binder = NetworkBinder(host)
        val view = host.layoutInflater.inflate(R.layout.dialog_network, null)
        var dialogOpen = true

        val presetSpinner = view.findViewById<Spinner>(R.id.network_preset)
        val transportSpinner = view.findViewById<Spinner>(R.id.network_transport)
        val hostField = view.findViewById<EditText>(R.id.network_host)
        val hostLabel = view.findViewById<TextView>(R.id.network_host_label)
        val portField = view.findViewById<EditText>(R.id.network_port)
        val wifiStatus = view.findViewById<TextView>(R.id.network_wifi_status)
        val hint = view.findViewById<TextView>(R.id.network_hint)
        val interfaceSpinner = view.findViewById<Spinner>(R.id.network_interface)
        val findButton = view.findViewById<Button>(R.id.network_find)
        val portDefaultButton = view.findViewById<Button>(R.id.network_port_default)

        val transports = arrayOf(
            "UDP listen", "TCP client", "TCP server (wait)", "TBS WebSocket"
        )

        presetSpinner.adapter = ArrayAdapter(
            host, android.R.layout.simple_spinner_dropdown_item,
            networkPresets.map { it.label })
        transportSpinner.adapter = ArrayAdapter(
            host, android.R.layout.simple_spinner_dropdown_item, transports)

        // Which network to work on. The phone can be on two at once — mobile
        // data plus a hotspot that the module has joined — and in that case the
        // module is a client of this phone, so the gateway is the phone itself
        // and tells us nothing about where the module is.
        var interfaces = LocalNetworks.list(binder.cellularInterfaceNames())
        fun interfaceLabels(): ArrayList<String> {
            val labels = ArrayList<String>()
            labels.add(host.getString(R.string.network_interface_auto))
            interfaces.forEach { labels.add(it.label()) }
            return labels
        }
        interfaceSpinner.adapter = ArrayAdapter(
            host, android.R.layout.simple_spinner_dropdown_item, interfaceLabels())

        // Whether we are on Wi-Fi and what it is called are two different
        // questions on a modern Android: the network is visible through
        // ConnectivityManager, but the SSID needs location permission and
        // location switched on. Treating an unreadable name as "no Wi-Fi"
        // would put a wrong warning in front of someone whose setup is fine.
        fun networkStanding(): String {
            val ssid = binder.ssid()
            val hotspot = interfaces.firstOrNull { it.likelyHotspot }
            // a wired way in — a USB-ethernet adapter — is as good as Wi-Fi now
            // that the pin routes by target, so it must not be told to "join
            // Wi-Fi first" over a link that already works
            val wired = interfaces.firstOrNull {
                !it.loopback && !it.likelyHotspot && !it.name.startsWith("wlan")
            }
            return when {
                // A hotspot is not a Network as far as ConnectivityManager is
                // concerned, so asking it whether we are "on Wi-Fi" says no even
                // though the module is happily connected to this phone.
                hotspot != null -> "Sharing a hotspot on " + hotspot.address
                binder.hasWifi() && ssid != null -> host.getString(R.string.network_on_wifi, ssid)
                binder.hasWifi() -> host.getString(R.string.network_on_wifi_unknown)
                wired != null -> "On " + wired.label()
                else -> host.getString(R.string.network_no_wifi)
            }
        }
        // The streams pin themselves to the network that reaches the module,
        // so the phone's per-app network preference is free for the maps —
        // said here, where whoever stands on an internet-less module Wi-Fi
        // wonders how the tiles will load.
        wifiStatus.text = networkStanding() + "\n" + host.getString(R.string.network_pin_tip)

        // A UDP listen binds a local port and never needs the module's address,
        // which is the whole reason the UDP presets are offered first.
        fun updateHostEnabled() {
            // whatever dials out needs somewhere to dial: a TCP client and a
            // WebSocket both do, a UDP listen and a TCP server do not
            val chosen = transportSpinner.selectedItemPosition
            val tcp = chosen == NetworkDataPoller.MODE_TCP_CLIENT ||
                chosen == NetworkDataPoller.MODE_WEBSOCKET
            // The high-latency preset needs somewhere to send its enable
            // command even on a UDP listen: an autopilot with the stream off
            // sends nothing, so there is no sender to learn an address from.
            val highLatency = networkPresets.getOrNull(
                presetSpinner.selectedItemPosition)?.highLatency == true
            hostField.isEnabled = tcp || highLatency
            hostLabel.isEnabled = tcp || highLatency
            // Greying the field out on its own only raises the question "why
            // can I not type here" — so the label answers it.
            hostLabel.text = when {
                tcp -> host.getString(R.string.network_host)
                highLatency -> host.getString(R.string.network_host_hl)
                else -> host.getString(R.string.network_host_unused)
            }
            hostField.hint = when {
                tcp -> host.getString(R.string.network_host_hint)
                highLatency -> host.getString(R.string.network_host_hint_hl)
                else -> host.getString(R.string.network_host_hint_udp)
            }
            // nothing to find on loopback: it is a single address, this device
            findButton.isEnabled = tcp && !hostField.text.toString().trim().startsWith("127.")
            hint.text = when {
                tcp -> host.getString(R.string.network_hint_tcp)
                highLatency -> host.getString(R.string.network_hint_hl)
                else -> host.getString(R.string.network_hint_udp)
            }
        }

        fun applyPreset(index: Int) {
            val preset = networkPresets[index]
            transportSpinner.setSelection(preset.mode)
            // the port this preset was last used with, not the documented one:
            // modules do get moved off their default
            portField.setText(
                preferenceManager.getNetworkPortFor(preset.key, preset.port).toString())
            if (preset.host != null) {
                hostField.setText(preset.host)
            } else if (preset.useGateway) {
                val gateway = binder.gatewayAddress()
                if (gateway != null) hostField.setText(gateway)
            }
            updateHostEnabled()
        }

        // restore what was used last; the saved value is a preset key, which
        // by construction equals the list position it had when it was stored
        val savedPreset = preferenceManager.getNetworkPreset()
        transportSpinner.setSelection(preferenceManager.getNetworkMode())
        // Reopening is restoring the last session, so the fallback is the port
        // that session used — not the preset's documented default. Falling back
        // to the default threw away a port that had been typed and connected
        // with, which is exactly the one worth keeping. (Switching preset is a
        // different question, and applyPreset answers it differently.)
        portField.setText(
            preferenceManager.getNetworkPortFor(
                savedPreset, preferenceManager.getNetworkPort()
            ).toString())
        // The address is remembered per network, the same way the port is
        // remembered per preset: a module is 10.0.0.1 on its own access point
        // and something else on a home network, so a single remembered address
        // was wrong every time you moved between the two.
        val network = binder.ssid() ?: ""
        val savedHost = preferenceManager.getNetworkHostFor(
            network, savedPreset, preferenceManager.getNetworkHost()
        )
        hostField.setText(if (savedHost.isEmpty()) (binder.gatewayAddress() ?: "") else savedHost)
        val savedPosition = networkPresets.indexOfFirst { it.key == savedPreset }
        if (savedPosition >= 0) presetSpinner.setSelection(savedPosition)
        updateHostEnabled()

        // A Spinner delivers its current selection to a newly attached listener,
        // and whether that happens at all depends on the layout pass — so a
        // one-shot "ignore the first callback" flag either swallows the user's
        // first real choice or lets the initial one through. Comparing against
        // what was set programmatically is not timing dependent: the echo has
        // the same position and does nothing, a real change does not.
        // Picking a network says where to look on it. On a hotspot the phone is
        // the gateway, so the module is a client somewhere in the subnet and the
        // useful thing to offer is the subnet itself, ready for the last octet
        // or for Find. On a joined network the gateway is usually the module.
        fun applyInterface(pos: Int) {
            val idx = pos - 1
            if (idx < 0 || idx >= interfaces.size) return
            val iface = interfaces[idx]
            val fill = when {
                // this device is a single address, not a subnet to search
                iface.loopback -> iface.address
                iface.likelyHotspot -> iface.subnet24()
                else -> binder.gatewayAddress() ?: iface.subnet24()
            }
            hostField.setText(fill)
            hostField.setSelection(hostField.text.length)
        }

        var appliedIface = interfaceSpinner.selectedItemPosition
        interfaceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == appliedIface) return
                appliedIface = pos
                applyInterface(pos)
            }
        }

        // The dialog is exactly where somebody stands while joining the
        // module's Wi-Fi or plugging an adapter in, and it used to describe
        // the networks of the moment it opened until closed and reopened.
        // Refreshed on a slow tick instead: the status line always, the
        // interface list only when it truly changed — swapping the spinner
        // under a finger for nothing is worse than being a breath late —
        // and back to Automatic then, since the old choice named a list
        // that is gone.
        val refreshNetworks = object : Runnable {
            override fun run() {
                if (!dialogOpen || host.isFinishing) return
                // Asleep behind another app, this asks the system for every
                // interface every two seconds for nothing; the tick comes back
                // with the screen, and the first one then refreshes it anyway.
                if (!host.lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.RESUMED)
                ) {
                    view.postDelayed(this, 2000)
                    return
                }
                val fresh = LocalNetworks.list(binder.cellularInterfaceNames())
                if (fresh.map { it.label() } != interfaces.map { it.label() }) {
                    // an explicit choice survives the list changing around it —
                    // an adapter plugged in must not snap a picked hotspot back
                    // to Automatic; only a vanished choice falls back there
                    val kept = interfaceSpinner.selectedItemPosition.let { pos ->
                        if (pos <= 0) null else interfaces.getOrNull(pos - 1)?.label()
                    }
                    interfaces = fresh
                    interfaceSpinner.adapter = ArrayAdapter(
                        host,
                        android.R.layout.simple_spinner_dropdown_item, interfaceLabels()
                    )
                    val at = kept?.let { label ->
                        interfaces.indexOfFirst { it.label() == label }
                    } ?: -1
                    appliedIface = if (at >= 0) at + 1 else 0
                    interfaceSpinner.setSelection(appliedIface)
                }
                wifiStatus.text =
                    networkStanding() + "\n" + host.getString(R.string.network_pin_tip)
                view.postDelayed(this, 2000)
            }
        }
        view.postDelayed(refreshNetworks, 2000)

        var appliedPreset = presetSpinner.selectedItemPosition
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == appliedPreset) return
                appliedPreset = pos
                applyPreset(pos)
            }
        }
        transportSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateHostEnabled()
            }
        }

        portDefaultButton.setOnClickListener {
            val index = presetSpinner.selectedItemPosition
            val preset = networkPresets.getOrNull(index)
            if (preset == null) return@setOnClickListener
            // by the preset's KEY: ports are stored under it, and clearing by
            // list position deleted a neighbouring preset's remembered port
            // once the ordering and the keys diverged
            preferenceManager.clearNetworkPortFor(preset.key)
            portField.setText(preset.port.toString())
            hint.text = host.getString(R.string.network_port_reset, preset.port)
        }

        // Finding a module that joined this phone's hotspot: there is no
        // gateway to ask, so ask every address on the subnet whether it is
        // serving telemetry on the chosen port.
        findButton.setOnClickListener {
            val chosen = interfaceSpinner.selectedItemPosition - 1
            val iface = if (chosen >= 0 && chosen < interfaces.size) {
                interfaces[chosen]
            } else {
                interfaces.firstOrNull { it.likelyHotspot }
                    ?: interfaces.firstOrNull { it.name.startsWith("wlan") }
                    ?: interfaces.firstOrNull()
            }
            val port = portField.text.toString().trim().toIntOrNull() ?: 0
            if (iface == null || port !in 1..65535) {
                Toast.makeText(host, "Pick a network and a valid port first", Toast.LENGTH_LONG)
                    .show()
                return@setOnClickListener
            }
            val findLabel = findButton.text
            findButton.isEnabled = false

            findButton.text = host.getString(R.string.network_searching_short)
            hint.text = host.getString(R.string.network_searching, iface.subnet24() + "x")

            // the probes ride the searched network the way the connect will,
            // resolved once — every target in the /24 routes the same way, and
            // a link the system reaches by itself (this phone's hotspot) comes
            // back unpinned from here, as the connect will find it too
            val road = binder.networkTo(iface.address)
            AsyncTask.execute {
                LocalNetworks.scan(iface, port, 300, { probe ->
                    try {
                        road?.bindSocket(probe)
                    } catch (e: Exception) {
                        // an unbound probe still searches the default road
                    }
                }, { done, total ->
                    host.runOnUiThread {
                        if (!dialogOpen || host.isFinishing) return@runOnUiThread
                        hint.text = host.getString(
                            R.string.network_searching_progress,
                            iface.subnet24() + "x", done, total
                        )
                    }
                }) { hits ->
                    host.runOnUiThread {
                        // the scan outlives the dialog, so a late result must
                        // not put a chooser up over the map
                        if (!dialogOpen || host.isFinishing) return@runOnUiThread
                        // through the one place that knows whether Find belongs
                        // to the transport now chosen — it may have changed to
                        // a UDP listen while the scan ran
                        updateHostEnabled()
                        findButton.text = findLabel
                        when {
                            hits.isEmpty() -> hint.text = host.getString(R.string.network_found_none)
                            hits.size == 1 -> {
                                hostField.setText(hits[0])
                                hint.text = host.getString(R.string.network_found_one, hits[0])
                            }
                            else -> {
                                // more than one thing is listening on that port,
                                // so say so and let the user choose rather than
                                // silently picking one
                                hint.text = host.getString(R.string.network_found_many, hits.size)
                                host.showDialog(
                                    AlertDialog.Builder(host)
                                        .setTitle(R.string.network_found_title)
                                        .setItems(hits.toTypedArray()) { d, which ->
                                            hostField.setText(hits[which])
                                            hint.text =
                                                host.getString(R.string.network_found_one, hits[which])
                                            d.dismiss()
                                        }
                                        .create()
                                )
                            }
                        }
                    }
                }
            }
        }

        host.showDialog(
            AlertDialog.Builder(host)
                .setOnDismissListener { dialogOpen = false }
                .setTitle(R.string.network_title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.network_connect) { _, _ ->
                    val mode = transportSpinner.selectedItemPosition
                    val useTcp = mode == NetworkDataPoller.MODE_TCP_CLIENT ||
                        mode == NetworkDataPoller.MODE_WEBSOCKET
                    val port = portField.text.toString().trim().toIntOrNull() ?: 0
                    val address = hostField.text.toString().trim()

                    if (port !in 1..65535) {
                        Toast.makeText(host, "Port must be between 1 and 65535", Toast.LENGTH_LONG)
                            .show()
                        return@setPositiveButton
                    }
                    if (useTcp && address.isEmpty()) {
                        Toast.makeText(host, "TCP needs the module's address", Toast.LENGTH_LONG)
                            .show()
                        return@setPositiveButton
                    }

                    // stored under the preset's key, not its list position:
                    // the list is ordered for the eye and may be reordered
                    val chosenPreset = networkPresets[presetSpinner.selectedItemPosition]
                    preferenceManager.setNetworkPreset(chosenPreset.key)
                    preferenceManager.setNetworkUseTcp(useTcp)
                    preferenceManager.setNetworkMode(mode)
                    preferenceManager.setNetworkHost(address)
                    preferenceManager.setNetworkHostFor(
                        binder.ssid() ?: "", chosenPreset.key, address)
                    preferenceManager.setNetworkPort(port)
                    preferenceManager.setNetworkPortFor(chosenPreset.key, port)

                    // Only from the preset that means it, and only over UDP —
                    // switching the transport away from what the preset set is
                    // choosing a different thing.
                    val highLatency = chosenPreset.highLatency &&
                        mode == NetworkDataPoller.MODE_UDP
                    host.connectToNetwork(address, port, mode, highLatency)
                }
                .create())
    }
}
