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

    fun init(context: android.content.Context) {
        if (file != null) return
        // Debug builds only. These notes carry coordinates and tile names —
        // where somebody flies — and a release build writes nobody's
        // whereabouts down unasked.
        if (!juricabi.com.telemetry.BuildConfig.DEBUG) return
        // Which build wrote this log: the install time is the only mark
        // that survives onto the phone — every debug build is "2.3.1",
        // and a fix tested one build too early reads as a fix that failed.
        val installed = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            synchronized(stamp) { stamp.format(Date(info.lastUpdateTime)) }
        } catch (e: Exception) {
            "?"
        }
        // Until the storage permission is granted — first launch, before the
        // dialog — appends fail and drop, like every other touch of it.
        file = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"),
            "debug-log.txt").also { it.parentFile?.mkdirs() }
        // A crash belongs in it more than anything else does: "it
        // crashed, don't know why" should answer itself from the file.
        // Straight to disk, not through the queue — the process is about
        // to die and the writer thread dies with the note unwritten; a
        // crash was the one thing this log never managed to catch.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                file?.appendText(synchronized(stamp) { stamp.format(Date()) } +
                    " CRASH on ${thread.name}: ${Log.getStackTraceString(e)}\n")
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
        // The path and whether it can be written, so a copied log always
        // begins with proof of where it lives — "nothing writes" is then a
        // one-line diagnosis instead of an evening.
        val dir = file?.parentFile
        note("DebugLog", "session start, installed $installed, writing " +
            "${file?.absolutePath} (dir exists=${dir?.isDirectory} " +
            "writable=${dir?.canWrite()})")
        // How the previous process died, from the system's own memory: a
        // native crash in the map's C++ never reaches a Java handler, and
        // one killed a 2D session leaving this log empty-handed. Reasons:
        // 4 crash, 5 native crash, 3 low-memory kill, 10 user, 11 dependency.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
                // eight, not two: on a testing day every reinstall is itself an
                // exit (reason 16), and two of those buried the crash record
                for (exit in am.getHistoricalProcessExitReasons(context.packageName, 0, 8)) {
                    note("DebugLog", "previous exit: reason=${exit.reason} " +
                        "status=${exit.status} " +
                        "at=${synchronized(stamp) { stamp.format(Date(exit.timestamp)) }} " +
                        "desc=${exit.description}")
                }
            } catch (e: Exception) {
                // history is a favour, not a dependency
            }
        }
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
