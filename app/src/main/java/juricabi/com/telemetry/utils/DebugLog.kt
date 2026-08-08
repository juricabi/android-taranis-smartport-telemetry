package juricabi.com.telemetry.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The terrain diagnostics, in the file "Copy debug info" already copies.
 *
 * adb only reaches a phone that is on the same network as the desk. Away
 * from it, everything this app says about itself — the pager's counters,
 * the frame costs, a crash's stack — was locked inside logcat. It lands in
 * the file the settings screen puts on the clipboard, so sending it is a
 * feature the tester already knows; a parallel file of its own under
 * TelemetryLogs was a second place for the same thing.
 */
object DebugLog {

    private const val MOST_BYTES = 1024L * 1024

    private var file: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file != null) return
        // Debug builds only. These notes carry coordinates and tile names —
        // where somebody flies — and a release build writes nobody's
        // whereabouts down unasked.
        if (!juricabi.com.telemetry.BuildConfig.DEBUG) return
        file = File(context.filesDir, "log.txt")
        // A crash belongs in it more than anything else does: "it
        // crashed, don't know why" should answer itself from the file.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            note("CRASH", Log.getStackTraceString(e))
            previous?.uncaughtException(thread, e)
        }
        note("DebugLog", "session start")
    }

    /**
     * The writes happen here, alone, off everyone else's back. note() is
     * called from the GL thread among others, and a synchronized append
     * put the render thread behind whichever worker was mid-write — the
     * diagnostics built to measure stutter causing it.
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
                    // the newest half stays; the whole point of this file is
                    // to fit on a clipboard
                    val kept = f.readBytes().let { it.copyOfRange(it.size / 2, it.size) }
                    f.writeBytes(kept)
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
