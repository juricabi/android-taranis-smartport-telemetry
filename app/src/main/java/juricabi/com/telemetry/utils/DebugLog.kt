package juricabi.com.telemetry.utils

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The terrain diagnostics, in a file the person testing can send.
 *
 * adb only reaches a phone that is on the same network as the desk. Away
 * from it, everything this app says about itself — the pager's counters,
 * the frame costs, a crash's stack — was locked inside logcat. It goes to
 * TelemetryLogs/terrain-debug.txt as well, beside the flight logs the
 * tester already knows how to find and share, capped small and rotated so
 * it can be attached from anywhere without a second thought.
 */
object DebugLog {

    private const val MOST_BYTES = 1024L * 1024

    private var file: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init() {
        if (file != null) return
        try {
            val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
            if (!dir.isDirectory && !dir.mkdirs()) return
            // One file per app start, named by the moment it began, so a
            // report is always one run and never a braid of several. Only
            // the newest few are kept — the folder must not silt up.
            dir.listFiles { f -> f.name.startsWith("terrain-debug") }
                ?.sortedBy { it.lastModified() }
                ?.dropLast(4)
                ?.forEach { it.delete() }
            val born = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
            file = File(dir, "terrain-debug-$born.txt")
            // A crash belongs in it more than anything else does: "it
            // crashed, don't know why" should answer itself from the file.
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, e ->
                note("CRASH", Log.getStackTraceString(e))
                previous?.uncaughtException(thread, e)
            }
            note("DebugLog", "session start")
        } catch (e: Exception) {
            // no file means no notes; logcat still carries everything
        }
    }

    /** Says it to logcat and to the file, in that order of importance. */
    @Synchronized
    fun note(tag: String, message: String) {
        Log.i(tag, message)
        val f = file ?: return
        try {
            if (f.length() > MOST_BYTES) {
                // roll within the run; the prefix prune above reaps these too
                val old = File(f.parentFile, f.name.removeSuffix(".txt") + ".old.txt")
                old.delete()
                f.renameTo(old)
            }
            f.appendText("${stamp.format(Date())} $tag: $message\n")
        } catch (e: Exception) {
            // best effort, every time
        }
    }
}
