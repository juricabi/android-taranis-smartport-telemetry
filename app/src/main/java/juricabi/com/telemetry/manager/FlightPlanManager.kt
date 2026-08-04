package juricabi.com.telemetry.manager

import android.content.Context
import juricabi.com.telemetry.maps.Position
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

class FlightPlanManager(private val context: Context) {

    companion object {
        private const val PLANS_DIR = "flight_plans"
        private const val INDEX_FILE = "plans_index.json"
        /**
         * Colours handed to plans as they are imported, in turn.
         *
         * A plan gets a colour of its own at birth rather than sharing one
         * setting with every other plan: two plans on the same map have to be
         * told apart, and that is the whole reason to draw them.
         */
        val PALETTE = intArrayOf(
            0xCC2196F3.toInt(), 0xCCFF9800.toInt(), 0xCC4CAF50.toInt(),
            0xCCE040FB.toInt(), 0xCCFFEB3B.toInt(), 0xCCF44336.toInt(),
            0xCC00BCD4.toInt(), 0xCCFFFFFF.toInt()
        )

        const val DEFAULT_COLOR = 0x664488FF.toInt()
    }

    data class FlightPlan(
        val id: String,
        val name: String,
        val waypoints: List<Position>,
        var color: Int = DEFAULT_COLOR,
        var visible: Boolean = true
    )

    private fun getPlansDir(): File {
        val dir = File(context.filesDir, PLANS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getIndexFile(): File {
        return File(getPlansDir(), INDEX_FILE)
    }

    fun getPlans(): List<FlightPlan> {
        val indexFile = getIndexFile()
        if (!indexFile.exists()) return emptyList()

        val plans = mutableListOf<FlightPlan>()
        val json = JSONArray(indexFile.readText())
        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val id = obj.getString("id")
            val name = obj.getString("name")
            val color = obj.optInt("color", DEFAULT_COLOR)
            val visible = obj.optBoolean("visible", true)
            val waypointsArray = obj.getJSONArray("waypoints")
            val waypoints = mutableListOf<Position>()
            for (j in 0 until waypointsArray.length()) {
                val wp = waypointsArray.getJSONObject(j)
                waypoints.add(Position(wp.getDouble("lat"), wp.getDouble("lon")))
            }
            plans.add(FlightPlan(id, name, waypoints, color, visible))
        }
        return plans
    }

    private fun savePlans(plans: List<FlightPlan>) {
        val json = JSONArray()
        for (plan in plans) {
            val obj = JSONObject()
            obj.put("id", plan.id)
            obj.put("name", plan.name)
            obj.put("color", plan.color)
            obj.put("visible", plan.visible)
            val waypointsArray = JSONArray()
            for (wp in plan.waypoints) {
                val wpObj = JSONObject()
                wpObj.put("lat", wp.lat)
                wpObj.put("lon", wp.lon)
                waypointsArray.put(wpObj)
            }
            obj.put("waypoints", waypointsArray)
            json.put(obj)
        }
        getIndexFile().writeText(json.toString())
    }

    fun importFromCsv(inputStream: InputStream, fileName: String): FlightPlan? {
        val waypoints = mutableListOf<Position>()
        val reader = BufferedReader(InputStreamReader(inputStream))
        reader.useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val parts = trimmed.split(",")
                if (parts.size >= 2) {
                    val lat = parts[0].trim().toDoubleOrNull()
                    val lon = parts[1].trim().toDoubleOrNull()
                    if (lat != null && lon != null) {
                        waypoints.add(Position(lat, lon))
                    }
                }
            }
        }
        if (waypoints.size < 2) return null

        val name = fileName.removeSuffix(".csv").removeSuffix(".CSV")
        val id = System.currentTimeMillis().toString()
        val plan = FlightPlan(id, name, waypoints, PALETTE[getPlans().size % PALETTE.size])

        val plans = getPlans().toMutableList()
        plans.add(plan)
        savePlans(plans)
        return plan
    }

    fun deletePlan(id: String) {
        val plans = getPlans().toMutableList()
        plans.removeAll { it.id == id }
        savePlans(plans)
    }

    fun updatePlanColor(id: String, color: Int) {
        val plans = getPlans().toMutableList()
        plans.find { it.id == id }?.color = color
        savePlans(plans)
    }

    fun updatePlanVisibility(id: String, visible: Boolean) {
        val plans = getPlans().toMutableList()
        plans.find { it.id == id }?.visible = visible
        savePlans(plans)
    }
}
