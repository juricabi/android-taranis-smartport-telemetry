package crazydude.com.telemetry.ui

import android.app.Activity
import android.content.*
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

    private fun showFlightPlanOptionsDialog(plan: FlightPlanManager.FlightPlan) {
        val visibilityLabel = if (plan.visible) "Hide" else "Show"
        val options = arrayOf(visibilityLabel, "Delete")
        AlertDialog.Builder(context!!)
            .setTitle(plan.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        flightPlanManager.updatePlanVisibility(plan.id, !plan.visible)
                        Toast.makeText(context, if (!plan.visible) "Plan shown" else "Plan hidden", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
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

    private fun updateSummary() {
        // The cell count is only used to divide a pack voltage, so it means
        // nothing when the flight controller already reports a single cell.
        val reportsPack =
            preferenceManager.sharedPreferences.getString("report_voltage", "Battery") == "Battery"
        findPreference<ListPreference>("battery_cells")?.let {
            it.isEnabled = reportsPack
        }
    }
}
