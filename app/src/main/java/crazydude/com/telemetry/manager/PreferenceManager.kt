package crazydude.com.telemetry.manager

import android.content.Context
import crazydude.com.telemetry.R
import crazydude.com.telemetry.maps.osm.OsmMapWrapper

class PreferenceManager(context: Context) {

    private val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val defaultHeadlineColor = context.resources.getColor(R.color.colorHeadline)
    private val defaultPlaneColor = context.resources.getColor(R.color.colorPlane)
    private val defaultRouteColor = context.resources.getColor(R.color.colorRoute)
    private val defaultFlightPlanColor = context.resources.getColor(R.color.colorFlightPlan)
    private val defaultHomeLineColor = context.resources.getColor(R.color.colorHomeLine)

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
            SensorSetting("ELRS Rate", 10, "top", false ),
            SensorSetting("Active Antena", 11, "top", false ),
            SensorSetting("Uplink Power", 12, "top", false ),
            SensorSetting("Uplink Antena 1 RSSI, dbm", 13, "top", false ),
            SensorSetting("Uplink Antena 2 RSSI, dbm", 14, "top", false ),
            SensorSetting("Downlink Antena RSSI, dbm", 15, "top", false ),
            SensorSetting("AirSpeed", 5, "bottom", false),
            SensorSetting("Vertical Speed", 6, "bottom", false),
            SensorSetting("Cell Voltage", 16, "top", false ),
            SensorSetting("Altitude above MSL", 7, "bottom", false),
            SensorSetting("Throttle", 8, "bottom", false),
            SensorSetting("Telemetry rate", 17, "top", false )
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

    fun isHeadingLineEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_heading_line", true)
    }

    fun getHeadLineColor(): Int {
        return sharedPreferences.getInt("headline_color", defaultHeadlineColor)
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
        return sharedPreferences.getInt("map_type", OsmMapWrapper.MAP_TYPE_DEFAULT)
    }

    fun setMapType(mapType: Int) {
        sharedPreferences.edit().putInt("map_type", mapType).apply()
    }

    fun getRouteColor(): Int {
        return sharedPreferences.getInt("route_color", defaultRouteColor)
    }

    fun getSensorsSettings(): List<SensorSetting> {
        return sensors.map {
            SensorSetting(
                it.name,
                sharedPreferences.getInt(it.name + "_index", it.index),
                sharedPreferences.getString(it.name + "_position", it.position),
                sharedPreferences.getBoolean(it.name + "_shown", it.shown)
            )
        }
    }

    fun setSensorsSettings(data: List<SensorSetting>) {
        val commit = sharedPreferences.edit()
        data.forEach {
            commit.putInt(it.name + "_index", it.index)
            commit.putString(it.name + "_position", it.position)
            commit.putBoolean(it.name + "_shown", it.shown)
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

    fun getConnectionVoiceMessagesEnabled(): Boolean {
        return sharedPreferences.getBoolean("connection_voice_messages", true)
    }

    fun getMaxRoutePoints() : Int {
        return sharedPreferences.getString("route_max_points", "-1")?.toInt() ?: -1
    }

    fun getPlaybackAutostart() : Boolean {
        return sharedPreferences.getBoolean("playback_autostart", true)
    }

    fun getPlaybackDuration() : Int {
        return sharedPreferences.getString("playback_duration", "30")?.toInt() ?: 30
    }

    fun setPlaybackDuration( v : Int)  {
        sharedPreferences.edit().putString("playback_duration", v.toString()).apply();
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

    fun isFlightPlansEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_flight_plans", true)
    }

    fun getFlightPlanColor(): Int {
        return sharedPreferences.getInt("flight_plan_color", defaultFlightPlanColor)
    }

    fun isHomeLineEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_home_line", true)
    }

    fun getHomeLineColor(): Int {
        return sharedPreferences.getInt("home_line_color", defaultHomeLineColor)
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