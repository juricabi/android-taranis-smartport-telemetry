package crazydude.com.telemetry.ui

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import crazydude.com.telemetry.R
import crazydude.com.telemetry.manager.FlightPlanManager
import crazydude.com.telemetry.manager.PreferenceManager
import androidx.preference.ListPreference
import crazydude.com.telemetry.utils.FileLogger
import java.io.IOException

class PrefsFragment : PreferenceFragmentCompat() {

    companion object {
        private const val REQUEST_IMPORT_FLIGHT_PLAN = 100
    }

    private lateinit var prefManager: PreferenceManager
    private lateinit var flightPlanManager: FlightPlanManager
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updateSummary()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "settings"
        setPreferencesFromResource(R.xml.preferences, rootKey)

        prefManager = PreferenceManager(context!!)
        flightPlanManager = FlightPlanManager(context!!)

        preferenceManager.sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        // Launched from code rather than from an <intent> in preferences.xml,
        // which had the release applicationId hardcoded and so could not open
        // this screen from a debug build at all.
        findPreference("sensor_display_settings").setOnPreferenceClickListener {
            startActivity(Intent(context, SensorsActivity::class.java))
            true
        }

        findPreference("background_location").setOnPreferenceClickListener {
            // Android will not offer "all the time" in a dialog for an app of
            // this vintage — it is only offered in the app's own settings — so
            // the honest thing is to take the user there rather than to ask for
            // something that cannot be granted from here.
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:" + context!!.packageName)
            startActivity(intent)
            true
        }

        findPreference("copy_debug_info").setOnPreferenceClickListener {
            context?.let {
                val clipboardManager =
                    it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                try {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText(null, FileLogger(it).copyLogFile()))
                } catch (e: IOException) {
                    Toast.makeText(it, "No log data available yet", Toast.LENGTH_SHORT).show()
                    return@let
                }
                Toast.makeText(it, "Debug data has been copied", Toast.LENGTH_SHORT).show()
            }
            return@setOnPreferenceClickListener false
        }
        findPreference("clear_debug_info").setOnPreferenceClickListener {
            context?.let {
                FileLogger(it).clearLogFile()
                Toast.makeText(it, "Debug data has been cleared", Toast.LENGTH_SHORT).show()
            }
            return@setOnPreferenceClickListener false
        }
        findPreference("import_flight_plan").setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(Intent.createChooser(intent, "Select CSV file"), REQUEST_IMPORT_FLIGHT_PLAN)
            return@setOnPreferenceClickListener true
        }
        findPreference("manage_flight_plans").setOnPreferenceClickListener {
            showManageFlightPlansDialog()
            return@setOnPreferenceClickListener true
        }
        // Validate: display altitude ceiling must be >= warning altitude ceiling
        findPreference("fr24_warning_alt_ceiling").setOnPreferenceChangeListener { _, newValue ->
            val warningCeiling = (newValue as? String)?.toIntOrNull() ?: 1500
            val displayCeiling = prefManager.getFr24DisplayAltCeilingM()
            if (warningCeiling > displayCeiling) {
                val displayPref = findPreference("fr24_display_alt_ceiling") as? ListPreference
                displayPref?.value = newValue.toString()
                Toast.makeText(context, "Display ceiling raised to match warning ceiling", Toast.LENGTH_SHORT).show()
            }
            true
        }
        findPreference("fr24_display_alt_ceiling").setOnPreferenceChangeListener { _, newValue ->
            val displayCeiling = (newValue as? String)?.toIntOrNull() ?: 3000
            val warningCeiling = prefManager.getFr24WarningAltCeilingM()
            if (displayCeiling < warningCeiling) {
                Toast.makeText(context, "Display ceiling cannot be lower than warning ceiling ($warningCeiling m)", Toast.LENGTH_LONG).show()
                false
            } else {
                true
            }
        }

        updateSummary()
        showBackgroundLocationState()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_FLIGHT_PLAN && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "flight_plan"
                    val inputStream = context!!.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val plan = flightPlanManager.importFromCsv(inputStream, fileName)
                        if (plan != null) {
                            Toast.makeText(context, "Imported \"${plan.name}\" with ${plan.waypoints.size} waypoints", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid CSV: need at least 2 waypoints (lat,lon per line)", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to import: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showManageFlightPlansDialog() {
        val plans = flightPlanManager.getPlans()
        if (plans.isEmpty()) {
            Toast.makeText(context, "No flight plans imported", Toast.LENGTH_SHORT).show()
            return
        }

        val names = plans.map { "${it.name} (${it.waypoints.size} pts)${if (!it.visible) " [hidden]" else ""}" }.toTypedArray()
        AlertDialog.Builder(context!!)
            .setTitle("Flight Plans")
            .setItems(names) { _, which ->
                showFlightPlanOptionsDialog(plans[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // Named colours rather than a picker: telling two plans apart is the whole
    // job, and these read the same on satellite imagery as on a map.
    private val planColorNames = arrayOf(
        "Blue", "Orange", "Green", "Magenta", "Yellow", "Red", "Cyan", "White"
    )
    private val planColorValues = FlightPlanManager.PALETTE

    private fun showFlightPlanColorDialog(plan: FlightPlanManager.FlightPlan) {
        val checked = planColorValues.indexOfFirst { it == plan.color }
        AlertDialog.Builder(context!!)
            .setTitle(plan.name)
            .setSingleChoiceItems(planColorNames, checked) { d, which ->
                flightPlanManager.updatePlanColor(plan.id, planColorValues[which])
                Toast.makeText(context, planColorNames[which], Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFlightPlanOptionsDialog(plan: FlightPlanManager.FlightPlan) {
        val visibilityLabel = if (plan.visible) "Hide" else "Show"
        val options = arrayOf(visibilityLabel, "Color", "Delete")
        AlertDialog.Builder(context!!)
            .setTitle(plan.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        flightPlanManager.updatePlanVisibility(plan.id, !plan.visible)
                        Toast.makeText(context, if (!plan.visible) "Plan shown" else "Plan hidden", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        showFlightPlanColorDialog(plan)
                    }
                    2 -> {
                        AlertDialog.Builder(context!!)
                            .setTitle("Delete ${plan.name}?")
                            .setPositiveButton("Delete") { _, _ ->
                                flightPlanManager.deletePlan(plan.id)
                                Toast.makeText(context, "Plan deleted", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    override fun onResume() {
        super.onResume()
        // Read again on the way back from the system settings, which is where
        // this sends people.
        showBackgroundLocationState()
    }

    /**
     * Whether the phone's position can still be heard once this app is out of
     * sight, which is what a flight watched with the phone in a pocket needs.
     */
    private fun showBackgroundLocationState() {
        val pref = findPreference("background_location") ?: return
        val ctx = context ?: return
        val fine = ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        // Both spelled out rather than named: this is built against an SDK
        // older than the one that introduced them, and an app of this vintage
        // is granted the background one implicitly when the user chooses
        // "all the time" in the system settings.
        val always = if (Build.VERSION.SDK_INT < 29) {
            fine
        } else {
            ContextCompat.checkSelfPermission(
                ctx, "android.permission.ACCESS_BACKGROUND_LOCATION"
            ) == PackageManager.PERMISSION_GRANTED
        }
        pref.summary = when {
            !fine ->
                "Location is not allowed at all. Tap to allow it, or the flight is " +
                    "recorded without where you were standing."
            always ->
                "Allowed all the time. Where you stood is recorded for the whole " +
                    "flight, including while the screen is off or the app is away."
            else ->
                "Allowed only while the app is open, so a flight watched with the " +
                    "phone in a pocket records no operator position for that stretch. " +
                    "Tap, then Permissions, then Location, then Allow all the time."
        }
    }

    private fun updateSummary() {
        // The cell count is only used to divide a pack voltage, so it means
        // nothing when the flight controller already reports a single cell.
        val reportsPack =
            preferenceManager.sharedPreferences.getString("report_voltage", "Battery") == "Battery"
        // preference 1.0.0 has the old, non generic findPreference
        findPreference("battery_cells")?.isEnabled = reportsPack
    }
}
