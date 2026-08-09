package juricabi.com.telemetry.utils

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The terrain diagnostics, in one file both roads read: "Copy debug info"
 * puts it on the clipboard, and it lives in TelemetryLogs beside the
 * flight recordings, which the phone already syncs off-device. adb only
 * reaches a phone that is on the same network as the desk; the sync
 * reaches the field. One file, a fixed name — sessions are told apart by
 * their "session start" lines, not by a folder of dated copies.
 */
object DebugLog {

    private const val MOST_BYTES = 1024L * 1024

    private var file: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init() {
        if (file != null) return
        // Debug builds only. These notes carry coordinates and tile names —
        // where somebody flies — and a release build writes nobody's
        // whereabouts down unasked.
        if (!juricabi.com.telemetry.BuildConfig.DEBUG) return
        // Until the storage permission is granted — first launch, before the
        // dialog — appends fail and drop, like every other touch of it.
        file = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"),
            "debug-log.txt").also { it.parentFile?.mkdirs() }
        // A crash belongs in it more than anything else does: "it
        // crashed, don't know why" should answer itself from the file.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            note("CRASH", Log.getStackTraceString(e))
            previous?.uncaughtException(thread, e)
        }
        // The path and whether it can be written, so a copied log always
        // begins with proof of where it lives — "nothing writes" is then a
        // one-line diagnosis instead of an evening.
        val dir = file?.parentFile
        note("DebugLog", "session start, writing ${file?.absolutePath}" +
            " (dir exists=${dir?.isDirectory} writable=${dir?.canWrite()})")
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
            } catch (e: Throwable) {
                // Best effort, every time — and Throwable, not Exception: an
                // Error thrown here once killed this thread silently, and a
                // long-lived process then ran for hours writing nothing.
            }
        }
    }, "debug-log").apply { isDaemon = true; start() }

    /** Everything noted so far, for the clipboard; null outside debug builds. */
    fun copyText(): String? = file?.takeIf { it.exists() }?.readText()

    fun clear() {
        try {
            file?.writeText("")
        } catch (e: Exception) {
            // best effort, like every other touch of this file
        }
    }

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
