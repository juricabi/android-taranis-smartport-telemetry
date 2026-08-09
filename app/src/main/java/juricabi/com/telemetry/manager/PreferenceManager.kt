package juricabi.com.telemetry.manager

import android.content.Context
import juricabi.com.telemetry.R
import juricabi.com.telemetry.maps.maplibre.MapLibreStyles

class PreferenceManager(context: Context) {

    private val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val defaultHeadlineColor = context.resources.getColor(R.color.colorHeadline)
    private val defaultPlaneColor = context.resources.getColor(R.color.colorPlane)
    private val defaultRouteColor = context.resources.getColor(R.color.colorRoute)
    private val defaultPosArrowColor = context.resources.getColor(R.color.colorPosArrow)
    private val defaultPosArrowLoggedColor =
        context.resources.getColor(R.color.colorPosArrowLogged)

    init {
        // The operator line inherits the old home line's replay role, so it
        // inherits that switch as its default — but the settings screen's
        // checkbox cannot express "inherit", so the inherited value is
        // written out once and both read the same stored answer.
        if (!sharedPreferences.contains("show_operator_line")) {
            sharedPreferences.edit().putBoolean(
                "show_operator_line",
                sharedPreferences.getBoolean("show_home_line", true)
            ).apply()
        }
    }

    companion object {
        val sensors = setOf(
            SensorSetting("Satellites", 1),
            SensorSetting("Battery", 2),
            SensorSetting("Voltage", 3),
            SensorSetting("Amperage", 4),
            SensorSetting("Speed", 0, "bottom"),
            SensorSetting("Distance", 1, "bottom"),
            SensorSetting("TraveledDistance", 2, "bottom"),
            SensorSetting("Altitude", 3, "bottom"),
            SensorSetting("Phone Battery", 5),
            SensorSetting("RC Channels", 4, "bottom", false),
            SensorSetting("Rssi", 0 ),
            SensorSetting("Uplink SNR", 6, "top", false ),
            SensorSetting("Downlink SNR", 7, "top", false ),
            SensorSetting("Uplink LQ", 8, "top", false ),
            SensorSetting("Downlink LQ", 9, "top", false ),
            // "CRSF Rate", not ELRS: the rf_mode field belongs to CRSF and is
            // sent by ExpressLRS, Crossfire and Tracer alike. Renaming does
            // reset this one sensor's saved position and visibility, since the
            // name is also its preference key.
            SensorSetting("CRSF Rate", 10, "top", false ),
            SensorSetting("Active Antena", 11, "top", false ),
            SensorSetting("Uplink Power", 12, "top", false ),
            SensorSetting("Uplink Antena 1 RSSI, dbm", 13, "top", false ),
            SensorSetting("Uplink Antena 2 RSSI, dbm", 14, "top", false ),
            SensorSetting("Downlink Antena RSSI, dbm", 15, "top", false ),
            SensorSetting("AirSpeed", 5, "bottom", false),
            SensorSetting("Vertical Speed", 6, "bottom", false),
            // shown, because with "voltage reported by telemetry" set to Cell
            // this is the only battery reading there is
            SensorSetting("Cell Voltage", 16, "top", true ),
            SensorSetting("Altitude above MSL", 7, "bottom", false),
            SensorSetting("Throttle", 8, "bottom", false),
            SensorSetting("Telemetry rate", 17, "top", false ),
            // Last, and last for good: the screen maps these to their views by
            // position in this set, so anything inserted above renames every
            // sensor after it and hands each one the neighbour's saved place.
            SensorSetting("Protocol", 9, "bottom", false )
        )
    }

    fun isLoggingEnabled(): Boolean {
        return sharedPreferences.getBoolean("logging_enabled", true)
    }

    fun isCSVLoggingEnabled(): Boolean {
        return sharedPreferences.getBoolean("csv_logging_enabled", true)
    }

    fun isLoggingSet(): Boolean {
        return sharedPreferences.contains("logging_enabled")
    }

    fun setLoggingEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("logging_enabled", enabled)
            .apply()
    }

    fun getLiveArrowColor(): Int {
        return sharedPreferences.getInt("pos_arrow_color", defaultPosArrowColor)
    }

    fun getLoggedArrowColor(): Int {
        return sharedPreferences.getInt("pos_arrow_logged_color", defaultPosArrowLoggedColor)
    }

    fun isDisarmedHeightIgnored(): Boolean {
        return sharedPreferences.getBoolean("ignore_disarmed_height", false)
    }

    fun isLiveShownInReplay(): Boolean {
        return sharedPreferences.getBoolean("show_live_in_replay", true)
    }

    fun setLiveShownInReplay(on: Boolean) {
        sharedPreferences.edit().putBoolean("show_live_in_replay", on).apply()
    }

    fun isMyPositionLoggingEnabled(): Boolean {
        return sharedPreferences.getBoolean("log_my_position", true)
    }

    fun isBackgroundCompassEnabled(): Boolean {
        return sharedPreferences.getBoolean("background_compass", true)
    }

    fun isClockEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_clock", true)
    }

    fun isHeadingLineEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_heading_line", true)
    }

    fun getHeadLineColor(): Int {
        return sharedPreferences.getInt("headline_color", defaultHeadlineColor)
    }

    /** "quad", "plane" or "heli": what the model is, on the map and in 3D. */
    fun getModelType(): String {
        return sharedPreferences.getString("model_type", "quad") ?: "quad"
    }

    fun getPlaneColor(): Int {
        return sharedPreferences.getInt("drone_color", defaultPlaneColor)
    }

    fun isYoutubeChannelShown(): Boolean {
        return sharedPreferences.getBoolean("youtube_shown", false)
    }

    fun setYoutubeShown() {
        sharedPreferences.edit().putBoolean("youtube_shown", true).apply()
    }

    fun getBatteryUnits(): String {
        return sharedPreferences.getString("battery_units", "mAh") ?: "mAh"
    }

    fun getReportVoltage(): String {
        return sharedPreferences.getString("report_voltage", "Battery") ?: "Battery"
    }

    // "auto", or a fixed cell count as a string
    fun getBatteryCells(): String {
        return sharedPreferences.getString("battery_cells", "auto") ?: "auto"
    }

    fun showArtificialHorizonView(): Boolean {
        return sharedPreferences.getBoolean("show_artificial_horizon", true)
    }

    fun getMapType(): Int {
        return sharedPreferences.getInt("map_type", MapLibreStyles.MAP_TYPE_DEFAULT)
    }

    fun setMapType(mapType: Int) {
        sharedPreferences.edit().putInt("map_type", mapType).apply()
    }

    /**
     * The 3D ground is chosen from the same list as the map types, so it is
     * remembered the same way. It is not one of them, though — the map keeps
     * its own type underneath, for coming back to.
     */
    fun is3DMapChosen(): Boolean {
        return sharedPreferences.getBoolean("map_3d", false)
    }

    fun set3DMapChosen(chosen: Boolean) {
        sharedPreferences.edit().putBoolean("map_3d", chosen).apply()
    }

    fun getRouteColor(): Int {
        return sharedPreferences.getInt("route_color", defaultRouteColor)
    }

    /**
     * The storage key for a sensor, which is its name unless it has been
     * renamed.
     *
     * "ELRS Rate" became "CRSF Rate", and the name doubles as the key — so the
     * rename quietly threw away everyone's saved position and visibility for
     * it. Because that sensor is hidden by default, it did not fall back to
     * something sensible, it disappeared off the screen. Keeping the original
     * key makes a rename only a rename.
     */
    private fun keyFor(name: String): String {
        return if (name == "CRSF Rate") "ELRS Rate" else name
    }

    fun getSensorsSettings(): List<SensorSetting> {
        return sensors.map {
            val key = keyFor(it.name)
            SensorSetting(
                it.name,
                sharedPreferences.getInt(key + "_index", it.index),
                sharedPreferences.getString(key + "_position", it.position) ?: it.position,
                sharedPreferences.getBoolean(key + "_shown", it.shown)
            )
        }
    }

    fun setSensorsSettings(data: List<SensorSetting>) {
        val commit = sharedPreferences.edit()
        data.forEach {
            val key = keyFor(it.name)
            commit.putInt(key + "_index", it.index)
            commit.putString(key + "_position", it.position)
            commit.putBoolean(key + "_shown", it.shown)
        }
        commit.apply()
    }

    fun getUsbSerialBaudrate() : Int {
       return sharedPreferences.getString("usb_serial_baudrate", "57600")?.toInt() ?: 57600
    }

	data class SensorSetting(
        val name: String,
        val index: Int,
        val position: String = "top",
        val shown: Boolean = true
    )

    fun isFullscreenWindow(): Boolean {
        return sharedPreferences.getBoolean("fullscreen_window", false)
    }

    fun setFullscreenWindow( state: Boolean ) {
        sharedPreferences.edit().putBoolean("fullscreen_window", state).apply()
    }

    fun getScreenOrientationLock() : String {
        return sharedPreferences.getString("screen_orientation_lock", "No lock") ?: "No lock"
    }

    fun getReconnectionEnabled(): Boolean {
        return sharedPreferences.getBoolean("connection_reconnect", true)
    }

    /**
     * Kept separate from the Bluetooth setting on purpose: a network drop and a
     * Bluetooth drop are not the same event, and someone may well want the
     * radio link retried but not a Wi-Fi one that is retrying against an access
     * point they have deliberately walked away from.
     */
    fun getNetworkReconnectionEnabled(): Boolean {
        return sharedPreferences.getBoolean("network_reconnect", true)
    }

    fun getConnectionVoiceMessagesEnabled(): Boolean {
        return sharedPreferences.getBoolean("connection_voice_messages", true)
    }

    fun getPlaybackAutostart() : Boolean {
        return sharedPreferences.getBoolean("playback_autostart", true)
    }

    /** 1 is the flight at the pace it was flown; above is faster, below slower. */
    fun getPlaybackSpeed() : Float {
        return sharedPreferences.getFloat("playback_speed", 1f).coerceIn(1f / 3f, 10f)
    }

    fun setPlaybackSpeed(speed: Float) {
        sharedPreferences.edit().putFloat("playback_speed", speed).apply()
    }

    fun getLastSelectedDataPooler() : String {
        return sharedPreferences.getString("last_data_pooler", "") ?: ""
    }

    fun setLastSelectedDataPooler(pooler: String) {
        sharedPreferences.edit().putString("last_data_pooler", pooler).apply()
    }

    fun getLastSelectedBluetoothDeviceAddress() : String {
        return sharedPreferences.getString("last_bt_address", "") ?: ""
    }

    fun setLastSelectedBluetoothDeviceAddress(pooler: String) {
        sharedPreferences.edit().putString("last_bt_address", pooler).apply()
    }

    fun getLastSelectedBLEDeviceAddress() : String {
        return sharedPreferences.getString("last_ble_address", "") ?: ""
    }

    fun setLastSelectedBLEDeviceAddress(pooler: String) {
        sharedPreferences.edit().putString("last_ble_address", pooler).apply()
    }

    // Network telemetry.
    //
    // Deliberately not exposed in res/xml/preferences.xml: these are chosen in
    // the connection dialog at the moment of connecting, and a port kept here
    // as an Int would collide with an EditTextPreference of the same key, which
    // stores everything as a String and would throw on read.

    /**
     * True for TCP — a TBS Crossfire WiFi module is a server you connect to.
     * False for a UDP listen, which is what an ExpressLRS backpack needs since
     * it broadcasts and there is nothing to address.
     */
    fun getNetworkUseTcp(): Boolean {
        return sharedPreferences.getBoolean("network_use_tcp", false)
    }

    fun setNetworkUseTcp(useTcp: Boolean) {
        sharedPreferences.edit().putBoolean("network_use_tcp", useTcp).apply()
    }

    fun getNetworkHost(): String {
        return sharedPreferences.getString("network_host", "") ?: ""
    }

    fun setNetworkHost(host: String) {
        sharedPreferences.edit().putString("network_host", host).apply()
    }

    /**
     * The address last used on this Wi-Fi network, for this preset.
     *
     * An address only means anything on the network it was reached over: a
     * module is 10.0.0.1 on its own access point and something else entirely on
     * a home network, so one remembered address was wrong every time you moved
     * between them. Falls back to whatever was used on this network for another
     * preset, then to the last address used anywhere.
     */
    fun getNetworkHostFor(network: String, preset: Int, fallback: String): String {
        val exact = sharedPreferences.getString(hostKey(network, preset), null)
        if (!exact.isNullOrEmpty()) return exact
        val sameNetwork = sharedPreferences.getString(hostKey(network, -1), null)
        if (!sameNetwork.isNullOrEmpty()) return sameNetwork
        return fallback
    }

    fun setNetworkHostFor(network: String, preset: Int, host: String) {
        sharedPreferences.edit()
            .putString(hostKey(network, preset), host)
            .putString(hostKey(network, -1), host)
            .apply()
    }

    private fun hostKey(network: String, preset: Int): String {
        // an unknown network still gets a slot of its own rather than none
        return "network_host_" + network + "|" + preset
    }

    /** 14550 is where an ExpressLRS backpack sends. TBS defaults to 8888. */
    fun getNetworkPort(): Int {
        return sharedPreferences.getInt("network_port", 14550)
    }

    fun setNetworkPort(port: Int) {
        sharedPreferences.edit().putInt("network_port", port).apply()
    }

    /**
     * Whether to pin the telemetry socket to the Wi-Fi network.
     *
     * Right when the phone has joined a module's access point, and wrong when
     * the module has joined this phone's hotspot: a hotspot client is reached
     * over the local subnet, and pinning to a joined Wi-Fi network would send
     * the traffic somewhere it can never arrive.
     */
    fun getNetworkPinWifi(): Boolean {
        return sharedPreferences.getBoolean("network_pin_wifi", true)
    }

    fun setNetworkPinWifi(pin: Boolean) {
        sharedPreferences.edit().putBoolean("network_pin_wifi", pin).apply()
    }

    /**
     * The port for one preset, remembered separately from the others.
     *
     * The documented defaults are only defaults: a TBS WiFi module lets you
     * change its port and ships from some vendors on something else entirely,
     * so a port typed once should still be there next time that preset is
     * picked — without overwriting what the other presets use.
     */
    /** 0 udp listen, 1 tcp client, 2 tcp server. */
    fun getNetworkMode(): Int {
        return sharedPreferences.getInt("network_mode", if (getNetworkUseTcp()) 1 else 0)
    }

    fun setNetworkMode(mode: Int) {
        sharedPreferences.edit().putInt("network_mode", mode).apply()
    }

    fun getNetworkPortFor(preset: Int, fallback: Int): Int {
        return sharedPreferences.getInt("network_port_" + preset, fallback)
    }

    fun setNetworkPortFor(preset: Int, port: Int) {
        sharedPreferences.edit().putInt("network_port_" + preset, port).apply()
    }

    /** Forget the override so the preset's own default comes back. */
    fun clearNetworkPortFor(preset: Int) {
        sharedPreferences.edit().remove("network_port_" + preset).apply()
    }

    fun getNetworkPreset(): Int {
        return sharedPreferences.getInt("network_preset", 0)
    }

    fun setNetworkPreset(preset: Int) {
        sharedPreferences.edit().putInt("network_preset", preset).apply()
    }

    fun isFlightPlansEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_flight_plans", true)
    }


    fun isLoadingGridShown(): Boolean {
        return sharedPreferences.getBoolean("show_loading_grid", true)
    }

    fun isHomeLineEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_home_line", true)
    }

    /**
     * The home line: drone to where this phone is now, live and playback
     * alike. Its default follows the arrow it points at — recolour the
     * arrow and the line comes along, unless the line was recoloured
     * itself. Two blues that drifted apart read as two different things.
     */
    fun getHomeLineColor(): Int {
        return sharedPreferences.getInt("home_line_color", getLiveArrowColor())
    }

    /** The recorded operator, whole: arrow, ring and line together. */
    fun isRecordedOperatorShown(): Boolean {
        return sharedPreferences.getBoolean("show_recorded_operator", true)
    }

    /** The operator line, playback only: to where they stood then. */
    fun isOperatorLineEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_operator_line", true)
    }

    fun getOperatorLineColor(): Int {
        // the recorded arrow's own colour, whatever it has been made:
        // the line and the arrow agree
        return sharedPreferences.getInt("operator_line_color", getLoggedArrowColor())
    }

    fun isFr24Enabled(): Boolean {
        return sharedPreferences.getBoolean("fr24_enabled", false)
    }

    fun getFr24RadiusKm(): Int {
        return sharedPreferences.getString("fr24_radius_km", "50")?.toIntOrNull() ?: 50
    }

    fun getFr24DisplayAltCeilingM(): Int {
        return sharedPreferences.getString("fr24_display_alt_ceiling", "3000")?.toIntOrNull() ?: 3000
    }

    fun getFr24WarningAltCeilingM(): Int {
        return sharedPreferences.getString("fr24_warning_alt_ceiling", "1500")?.toIntOrNull() ?: 1500
    }

    fun getFr24WarningDistanceM(): Int {
        return sharedPreferences.getString("fr24_warning_distance", "5000")?.toIntOrNull() ?: 5000
    }

    fun getFr24PollIntervalSec(): Int {
        return sharedPreferences.getString("fr24_poll_interval", "10")?.toIntOrNull() ?: 10
    }

}
