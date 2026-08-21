package juricabi.com.telemetry.ui

import android.app.Activity
import android.speech.tts.TextToSpeech
import android.widget.Toast
import juricabi.com.telemetry.manager.Fr24Manager

/**
 * A nearby aircraft, said out loud: the toast and the spoken warning, and the
 * speech engine that exists for nothing else. The distance is measured from
 * the model when one is flying and from the person otherwise, and the words
 * say which — two kilometres from the model is the model's business, two
 * kilometres from the person standing in the field is theirs, and the number
 * alone never said which.
 */
class TrafficWarnings(private val activity: Activity) {

    private var ttsReady = false
    private var tts: TextToSpeech? = TextToSpeech(activity) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
    }

    fun warn(
        airplane: Fr24Manager.AirplaneInfo,
        distanceMeters: Double,
        directionDeg: Double,
        fromModel: Boolean
    ) {
        val cardinal = bearingToCardinal(directionDeg)
        val distKm = distanceMeters / 1000.0
        val of = if (fromModel) "from the model" else "from you"
        val msg = "TRAFFIC: ${airplane.displayName} ${"%.1f".format(distKm)}km $cardinal $of, alt ${airplane.altMeters}m"
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

        if (ttsReady) {
            val spokenDir = bearingToSpoken(directionDeg)
            val speech = "Traffic, ${spokenDir}, ${"%.1f".format(distKm)} kilometers ${of}, altitude ${airplane.altMeters} meters"
            tts?.speak(speech, TextToSpeech.QUEUE_ADD, null, "fr24_warning_${airplane.flightId}")
        }
    }

    /** The screen is going; the engine goes with it. */
    fun shutdown() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun bearingToCardinal(deg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((deg + 22.5) / 45.0).toInt() % 8
        return dirs[index]
    }

    private fun bearingToSpoken(deg: Double): String {
        val dirs = arrayOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
        val index = ((deg + 22.5) / 45.0).toInt() % 8
        return dirs[index]
    }
}
