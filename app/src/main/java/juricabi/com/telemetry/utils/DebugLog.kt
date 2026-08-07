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

    /**
     * The writes happen here, alone, off everyone else's back. note() is
     * called from the GL thread among others, and a synchronized append to
     * external flash put the render thread behind whichever worker was
     * mid-write — the diagnostics built to measure stutter causing it.
     */
    private val pending = java.util.concurrent.LinkedBlockingQueue<String>(1000)

    private val writer = Thread({
        android.os.Process.setThreadPriority(
            android.os.Process.THREAD_PRIORITY_BACKGROUND)
        while (true) {
            val line = try { pending.take() } catch (e: InterruptedException) { return@Thread }
            val f = file ?: continue
            try {
                if (f.length() > MOST_BYTES) {
                    // roll within the run; the prefix prune above reaps these
                    val old = File(f.parentFile, f.name.removeSuffix(".txt") + ".old.txt")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line)
            } catch (e: Exception) {
                // best effort, every time
            }
        }
    }, "debug-log").apply { isDaemon = true; start() }

    /** Says it to logcat now and queues it for the file; never blocks. */
    fun note(tag: String, message: String) {
        Log.i(tag, message)
        if (file == null) return
        // the stamp is taken now — event time, not write time — under its
        // own hair of a lock, since SimpleDateFormat cannot share threads
        val at = synchronized(stamp) { stamp.format(Date()) }
        // offer, not put: a full queue drops the note rather than blocking
        pending.offer("$at $tag: $message\n")
    }
}
