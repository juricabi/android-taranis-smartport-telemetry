package juricabi.com.telemetry.ui

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import juricabi.com.telemetry.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.ceil

/**
 * The log manager. One list, two modes in place: browse — a tap opens a flight
 * — and select, where the same rows grow checkboxes to be marked for deleting or
 * exporting. No second screen for the delete: the list you are already looking at
 * is the one that changes. Rows sit under a heading for the day they were flown,
 * and a drag down the list marks a run of them at once. Below: import a backup
 * back in, or zip these out to one.
 *
 * Lifted out of [MapsActivity] into its own file; it leans on the activity —
 * [host] — for the few things that are the screen's, not the list's: opening a
 * replay, the replay state, the storage permission, and the fullscreen-aware
 * dialog show.
 */
class LogManager(private val host: MapsActivity) {

    companion object {
        const val REQUEST_IMPORT_LOGS = 6
        const val REQUEST_CREATE_BACKUP = 7
        private const val KEY_PENDING_BACKUP = "log_pending_backup"
    }

    /** A row in the log manager: a day's heading, or one recording under it. */
    private sealed class LogItem {
        class Day(val label: String, val files: MutableList<File>) : LogItem()
        class Log(val file: File) : LogItem()
    }

    /** Held so import/backup/delete can refresh or close it. */
    private var dialog: AlertDialog? = null

    /** Rebuilds the open list in place — no flicker — when the files change. */
    private var reload: (() -> Unit)? = null

    /** Held between choosing a place to save the backup and the writing of it. */
    private var pendingBackup: List<File>? = null

    fun open() {
        val files = currentLogFiles()
        if (files.isEmpty()) {
            Toast.makeText(host, "No logs available", Toast.LENGTH_SHORT).show()
            return
        }
        // Never stack a second manager over a first — a permission-grant resume
        // can route back through here while one is already up.
        dialog?.dismiss()
        val inflater = LayoutInflater.from(host)
        val root = inflater.inflate(R.layout.dialog_log_manager, null)
        val listView = root.findViewById<ListView>(R.id.log_list)
        val summary = root.findViewById<TextView>(R.id.log_summary)
        val browseBar = root.findViewById<View>(R.id.log_browse_bar)
        val selectBar = root.findViewById<View>(R.id.log_select_bar)
        val selectedView = root.findViewById<TextView>(R.id.log_selected)
        val allBtn = root.findViewById<Button>(R.id.log_all)
        val renameBtn = root.findViewById<Button>(R.id.log_rename)
        val exportBtn = root.findViewById<Button>(R.id.log_export)
        val deleteBtn = root.findViewById<Button>(R.id.log_delete)

        // The set the list is built on, kept in a var so a delete or import can
        // swap it under the same dialog without reopening.
        var currentFiles = files
        val items = groupByDay(currentFiles)

        val selected = linkedSetOf<File>()
        var selecting = false
        var painting = false
        var paintValue = true
        var dragAnchor = -1
        val dragOriginal = linkedSetOf<File>()

        fun updateSummary() {
            val mb = currentFiles.sumOf { it.length().toDouble() } / (1024 * 1024)
            summary.text = "${currentFiles.size} logs · ${"%.1f".format(mb)} MB"
        }
        updateSummary()

        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int): Any = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(p: Int) = if (items[p] is LogItem.Day) 0 else 1
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val item = items[p]
                if (item is LogItem.Day) {
                    val v = cv ?: inflater.inflate(R.layout.log_manager_header, parent, false)
                    val check = v.findViewById<CheckBox>(R.id.day_check)
                    v.findViewById<TextView>(R.id.day_label).text = item.label
                    // no rule above the first day — the summary's is right there
                    v.findViewById<View>(R.id.day_divider).visibility = if (p == 0) View.GONE else View.VISIBLE
                    check.visibility = if (selecting) View.VISIBLE else View.GONE
                    check.isChecked = selecting && item.files.all { selected.contains(it) }
                    return v
                }
                item as LogItem.Log
                val v = cv ?: inflater.inflate(R.layout.log_manager_row, parent, false)
                val check = v.findViewById<CheckBox>(R.id.log_check)
                val name = v.findViewById<TextView>(R.id.log_name)
                name.text = item.file.nameWithoutExtension
                name.setTypeface(
                    null,
                    if (!selecting && item.file.name == host.lastFileDialogSelection) Typeface.BOLD else Typeface.NORMAL
                )
                v.findViewById<TextView>(R.id.log_size).text = "${ceil(item.file.length() / 102.4) / 10} Kb"
                check.visibility = if (selecting) View.VISIBLE else View.GONE
                check.isChecked = selected.contains(item.file)
                return v
            }
        }
        listView.adapter = adapter

        // The window wraps the content: the list is measured to exactly its rows
        // (56dp) and headers (45dp), capped so a long list scrolls instead of
        // running off the screen. A short list makes a short dialog — no empty
        // space below the rows — and the fixed heights keep it from jumping.
        fun sizeWindow() {
            val m = host.resources.displayMetrics
            dialog?.window?.setLayout((m.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            var contentPx = 0f
            for (it in items) contentPx += (if (it is LogItem.Day) 45 else 56) * m.density
            listView.layoutParams = listView.layoutParams?.apply {
                height = minOf(contentPx.toInt(), (m.heightPixels * 0.72).toInt())
            }
        }

        fun refresh() = adapter.notifyDataSetChanged()
        fun updateBars() {
            browseBar.visibility = if (selecting) View.GONE else View.VISIBLE
            selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
            val n = selected.size
            selectedView.visibility = if (selecting) View.VISIBLE else View.GONE
            selectedView.text = "$n selected"
            allBtn.visibility = if (selecting) View.VISIBLE else View.GONE
            deleteBtn.isEnabled = n > 0
            exportBtn.isEnabled = n > 0
            renameBtn.visibility = if (n == 1) View.VISIBLE else View.GONE
            allBtn.text = if (n == currentFiles.size && currentFiles.isNotEmpty()) "None" else "All"
        }
        fun enterSelect() { if (!selecting) { selecting = true; updateBars(); refresh() } }
        fun exitSelect() { selecting = false; selected.clear(); painting = false; updateBars(); refresh() }
        // The selection during a drag is the pre-drag set with one band laid over
        // it: the rows between where the finger went down and where it is now, all
        // set to what the row it began on was not. Rebuilt from the snapshot every
        // move, so retreating the finger un-paints the rows it leaves — drag back
        // the way you came and they deselect.
        fun paintRange(pos: Int) {
            if (pos < 0 || pos >= items.size || dragAnchor < 0) return
            selected.clear()
            selected.addAll(dragOriginal)
            for (i in minOf(dragAnchor, pos)..maxOf(dragAnchor, pos)) {
                val it = items[i]
                if (it is LogItem.Log) {
                    if (paintValue) selected.add(it.file) else selected.remove(it.file)
                }
            }
            updateBars(); refresh()
        }

        // Re-read the folder and rebuild the rows under the same dialog: a delete,
        // rename or import changes the files, not the window. Selection is dropped
        // (it may name files now gone), and an empty folder closes the manager as
        // opening on nothing would have declined to show it.
        reload = {
            val newFiles = currentLogFiles()
            if (newFiles.isEmpty()) {
                Toast.makeText(host, "No logs available", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
                dialog = null
            } else {
                currentFiles = newFiles
                items.clear()
                items.addAll(groupByDay(newFiles))
                // Stay in select mode but with nothing marked: the action (delete,
                // export, rename) has consumed the selection, and the user carries on
                // picking from the updated list.
                painting = false; selected.clear()
                updateSummary(); updateBars(); refresh(); sizeWindow()
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            when (val item = items[position]) {
                is LogItem.Log -> if (!selecting) {
                    host.updateWindowFullscreenDecoration()
                    host.lastFileDialogSelection = item.file.name
                    dialog?.dismiss()
                    host.startReplay(item.file)
                } else {
                    if (!selected.remove(item.file)) selected.add(item.file)
                    updateBars(); refresh()
                }
                is LogItem.Day -> if (selecting) {
                    if (item.files.all { selected.contains(it) }) selected.removeAll(item.files.toSet())
                    else selected.addAll(item.files)
                    updateBars(); refresh()
                }
            }
        }

        // Drag to mark a run. A long press starts it — until then the list is
        // free to scroll or a tap to open — and moving the finger paints every
        // row it crosses to whatever the row it began on was not.
        val handler = Handler(Looper.getMainLooper())
        var downPos = -1
        var downX = 0f
        var downY = 0f
        val slop = ViewConfiguration.get(host).scaledTouchSlop
        val startPaint = Runnable {
            val item = items.getOrNull(downPos)
            if (item is LogItem.Log) {
                enterSelect()
                dragAnchor = downPos
                dragOriginal.clear()
                dragOriginal.addAll(selected)
                paintValue = !selected.contains(item.file)
                painting = true
                paintRange(downPos)
            }
        }
        listView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    downPos = listView.pointToPosition(ev.x.toInt(), ev.y.toInt())
                    painting = false; dragAnchor = -1
                    handler.postDelayed(startPaint, ViewConfiguration.getLongPressTimeout().toLong())
                    false
                }
                MotionEvent.ACTION_MOVE -> if (painting) {
                    listView.parent?.requestDisallowInterceptTouchEvent(true)
                    val pos = listView.pointToPosition(ev.x.toInt(), ev.y.toInt())
                    if (pos != AdapterView.INVALID_POSITION) paintRange(pos)
                    true
                } else {
                    if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) {
                        handler.removeCallbacks(startPaint)
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(startPaint)
                    val wasPainting = painting
                    painting = false
                    wasPainting
                }
                else -> false
            }
        }

        root.findViewById<Button>(R.id.log_import).setOnClickListener { importLogs() }
        root.findViewById<Button>(R.id.log_select).setOnClickListener { enterSelect() }
        root.findViewById<Button>(R.id.log_done).setOnClickListener { exitSelect() }
        allBtn.setOnClickListener {
            if (selected.size == currentFiles.size) selected.clear()
            else { selected.clear(); selected.addAll(currentFiles) }
            updateBars(); refresh()
        }
        renameBtn.setOnClickListener {
            // The manager stays up behind the rename box; renameLog reopens it on
            // the new name, and a cancel simply leaves it standing.
            val one = selected.firstOrNull() ?: return@setOnClickListener
            host.showRenameLogDialog(one.name)
        }
        exportBtn.setOnClickListener { if (selected.isNotEmpty()) backupLogs(selected.toList()) }
        deleteBtn.setOnClickListener { if (selected.isNotEmpty()) confirmDeleteLogs(selected.toList()) }

        updateBars()

        val built = AlertDialog.Builder(host).setTitle("Logs").setView(root).create()
        // Back leaves select mode before it leaves the manager.
        built.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && selecting) {
                exitSelect(); true
            } else false
        }
        dialog = built
        built.setOnDismissListener {
            // Let go of the view tree once the manager closes — do not keep the
            // dismissed dialog and its rows alive through a long replay.
            if (dialog === built) { dialog = null; reload = null }
        }
        built.setOnShowListener {
            sizeWindow()
            val idx = items.indexOfFirst { it is LogItem.Log && it.file.name == host.lastFileDialogSelection }
            // After layout, not now: in the show listener the list has no height
            // yet, so a scroll asked here goes nowhere. Posted, it centres the
            // last-opened flight the way the old picker did.
            if (idx >= 0) listView.post { listView.setSelectionFromTop(idx, listView.height / 2) }
        }
        host.showDialog(built)
    }

    /** When the log now happened, from its name if that is a stamp, else its file time. */
    private fun logTime(f: File): Long = try {
        SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).parse(f.nameWithoutExtension)?.time
            ?: f.lastModified()
    } catch (e: Exception) {
        f.lastModified()
    }

    private fun logDayKey(f: File): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(logTime(f)))

    private fun logDayLabel(f: File): String {
        val t = logTime(f)
        val day = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val sameDay = { a: Calendar, b: Calendar ->
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
        val date = SimpleDateFormat("EEE, d MMM yyyy", Locale.US).format(Date(t))
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(day, today) -> "Today · $date"
            sameDay(day, yesterday) -> "Yesterday · $date"
            else -> date
        }
    }

    /** The recordings, newest first — the same set the picker opens on. */
    private fun currentLogFiles(): List<File> {
        val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
        val listed = if (dir.exists()) {
            dir.listFiles { f -> (f.extension == "log" || f.extension == "tlm") && f.length() > 0 }
        } else null
        return listed?.sorted()?.reversed() ?: emptyList()
    }

    /** Flatten the flights into day-headed rows, newest day and flight first. */
    private fun groupByDay(files: List<File>): ArrayList<LogItem> {
        val ordered = files.sortedByDescending { logTime(it) }
        val items = ArrayList<LogItem>()
        var lastKey: String? = null
        var dayFiles: MutableList<File>? = null
        for (f in ordered) {
            val key = logDayKey(f)
            if (key != lastKey) {
                dayFiles = ArrayList()
                items.add(LogItem.Day(logDayLabel(f), dayFiles))
                lastKey = key
            }
            dayFiles!!.add(f)
            items.add(LogItem.Log(f))
        }
        return items
    }

    /** The files on disk changed — rebuild the open manager in place. A no-op if
     *  the manager is not up, as when a rename fires from the replay screen. */
    fun filesChanged() {
        if (dialog?.isShowing == true) reload?.invoke()
    }

    /**
     * Carry the files chosen for export across a recreation while the save-picker
     * is up — a rotation rebuilds the activity, and this instance with it, before
     * the picker returns. Without it the export would come back to a null list and
     * quietly write nothing.
     */
    fun onSaveInstanceState(out: Bundle) {
        pendingBackup?.let { out.putStringArray(KEY_PENDING_BACKUP, it.map { f -> f.path }.toTypedArray()) }
    }

    fun onRestoreInstanceState(saved: Bundle) {
        saved.getStringArray(KEY_PENDING_BACKUP)?.let { paths -> pendingBackup = paths.map { File(it) } }
    }

    /** Route the import/backup picker results back here; true if it was ours. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQUEST_IMPORT_LOGS -> {
                if (resultCode == Activity.RESULT_OK) data?.data?.let { importLogsFrom(it) }
                return true
            }
            REQUEST_CREATE_BACKUP -> {
                if (resultCode == Activity.RESULT_OK) data?.data?.let { writeBackupTo(it) }
                return true
            }
        }
        return false
    }

    /**
     * Export the recordings — each .tlm and the .csv beside it — as one zip, to
     * a place the person picks. The picker grants that place, so no storage
     * permission is asked and any folder, USB stick or Drive the phone reaches
     * will do. Imported back the same way.
     */
    private fun backupLogs(files: List<File>) {
        if (files.isEmpty()) {
            Toast.makeText(host, "Nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        pendingBackup = files
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "telemetry-backup-$stamp.zip")
        }
        try {
            host.startActivityForResult(intent, REQUEST_CREATE_BACKUP)
        } catch (e: Exception) {
            pendingBackup = null
            Toast.makeText(host, "No place to save to", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeBackupTo(uri: Uri) {
        val files = pendingBackup ?: return
        pendingBackup = null
        val progress = ProgressDialog(host)
        progress.setMessage("Exporting ${files.size} logs…")
        progress.setCancelable(false)
        progress.show()
        Thread {
            var ok = 0
            try {
                (host.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("could not open the chosen file")).use { out ->
                    ZipOutputStream(BufferedOutputStream(out)).use { zos ->
                        val buf = ByteArray(64 * 1024)
                        for (f in files) {
                            val csv = File(f.parentFile, host.replaceExtension(f.name, ".csv"))
                            var wrote = false
                            for (part in listOf(f, csv)) {
                                if (!part.exists()) continue
                                zos.putNextEntry(ZipEntry(part.name))
                                FileInputStream(part).use { ins ->
                                    var n = ins.read(buf)
                                    while (n >= 0) { zos.write(buf, 0, n); n = ins.read(buf) }
                                }
                                zos.closeEntry()
                                wrote = true
                            }
                            // only a log that actually put something in the zip counts
                            if (wrote) ok++
                        }
                    }
                }
                host.runOnUiThread {
                    // Dismiss first so the spinner cannot leak if we then bail on a
                    // torn-down activity; the window may already be gone, hence the catch.
                    try { progress.dismiss() } catch (e: Exception) {}
                    if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                    Toast.makeText(host, "Exported $ok logs", Toast.LENGTH_LONG).show()
                    // Export done — clear the selection but stay in select mode.
                    filesChanged()
                }
            } catch (e: Exception) {
                host.runOnUiThread {
                    try { progress.dismiss() } catch (ignored: Exception) {}
                    if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                    Toast.makeText(host, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Pick a backup zip (or a loose log) to bring back into the log folder. */
    private fun importLogs() {
        if (!host.requestWritePermission(MapsActivity.RequestWritePermissionSequenceType.LOG_PICKER)) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            host.startActivityForResult(intent, REQUEST_IMPORT_LOGS)
        } catch (e: Exception) {
            Toast.makeText(host, "No file picker available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isLogPart(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.endsWith(".tlm") || n.endsWith(".log") || n.endsWith(".csv")
    }

    private fun importLogsFrom(uri: Uri) {
        val name = queryDisplayName(uri) ?: ""
        val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
        dir.mkdirs()
        val progress = ProgressDialog(host)
        progress.setMessage("Importing…")
        progress.setCancelable(false)
        progress.show()
        Thread {
            var restored = 0
            val tmp = File(host.cacheDir, "log_import")
            try {
                tmp.deleteRecursively(); tmp.mkdirs()
                // Unpack to a scratch dir first — a whole backup zip, or a single
                // loose log — then reconcile against the folder, so a name clash
                // renames and an identical file is left alone (see reconcileImport).
                // A loose log only when the name clearly says so; anything else — a
                // .zip, or a display name the provider never gave — is read as a zip.
                if (isLogPart(name) && !name.endsWith(".zip", true)) {
                    host.contentResolver.openInputStream(uri)?.use { ins ->
                        FileOutputStream(File(tmp, File(name).name)).use { ins.copyTo(it) }
                    }
                } else {
                    host.contentResolver.openInputStream(uri)?.use { raw ->
                        ZipInputStream(BufferedInputStream(raw)).use { zis ->
                            val buf = ByteArray(64 * 1024)
                            var entry = zis.nextEntry
                            while (entry != null) {
                                // Only the name, never a path an entry might carry.
                                val base = File(entry.name).name
                                if (!entry.isDirectory && isLogPart(base)) {
                                    FileOutputStream(File(tmp, base)).use { fos ->
                                        var n = zis.read(buf)
                                        while (n >= 0) { fos.write(buf, 0, n); n = zis.read(buf) }
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                }
                restored = reconcileImport(tmp, dir)
                host.runOnUiThread {
                    try { progress.dismiss() } catch (ignored: Exception) {}
                    if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                    Toast.makeText(host, "Imported $restored logs", Toast.LENGTH_LONG).show()
                    filesChanged()
                }
            } catch (e: Exception) {
                host.runOnUiThread {
                    try { progress.dismiss() } catch (ignored: Exception) {}
                    if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                    Toast.makeText(host, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                tmp.deleteRecursively()
            }
        }.start()
    }

    /**
     * Move the unpacked flights from [tmp] into [dir], each .tlm kept on one stem
     * with its .csv. A flight whose .tlm already sits in [dir] byte-for-byte is left
     * alone — re-importing the same backup adds nothing — while one whose name
     * clashes with a different recording is taken in under "<name> (n)" rather than
     * overwriting it. Returns how many flights were actually added.
     */
    private fun reconcileImport(tmp: File, dir: File): Int {
        val parts = tmp.listFiles()?.toList() ?: return 0
        var added = 0
        for (stem in parts.map { it.nameWithoutExtension }.distinct()) {
            val mine = parts.filter { it.nameWithoutExtension == stem }
            val tlm = mine.firstOrNull { it.extension == "tlm" || it.extension == "log" }
            val csv = mine.firstOrNull { it.extension == "csv" }
            val primary = tlm ?: csv ?: continue
            val existing = File(dir, primary.name)
            val target = when {
                !existing.exists() -> stem
                sameBytes(primary, existing) -> continue      // identical — leave it
                else -> freeStem(dir, stem, "." + primary.extension)
            }
            tlm?.copyTo(File(dir, "$target.${tlm.extension}"), overwrite = true)
            csv?.copyTo(File(dir, "$target.csv"), overwrite = true)
            if (tlm != null) added++
        }
        return added
    }

    /** The first "<stem> (n)<ext>" that no recording in [dir] already holds. */
    private fun freeStem(dir: File, stem: String, ext: String): String {
        var n = 1
        while (File(dir, "$stem ($n)$ext").exists()) n++
        return "$stem ($n)"
    }

    private fun sameBytes(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { ai ->
            b.inputStream().buffered().use { bi ->
                while (true) {
                    val x = ai.read()
                    if (x != bi.read()) return false
                    if (x == -1) return true
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        host.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun confirmDeleteLogs(files: List<File>) {
        // Deleting the app's own files under legacy storage needs the write
        // permission, as the old single/all delete paths asked for it too.
        if (!host.requestWritePermission(MapsActivity.RequestWritePermissionSequenceType.LOG_PICKER)) return
        val megabytes = files.sumOf { it.length().toDouble() } / (1024 * 1024)
        val confirm = AlertDialog.Builder(host)
            .setTitle(if (files.size == 1) "Delete log" else "Delete logs")
            .setMessage(
                "Delete " + files.size + (if (files.size == 1) " log (" else " logs (") +
                    "%.1f".format(megabytes) +
                    " MB)?" + 10.toChar() + 10.toChar() +
                    "This cannot be undone. Any CSV files recorded alongside them go too."
            )
            .setPositiveButton("Delete") { d, _ ->
                deleteLogs(files)
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()
        // the destructive button in the same warn amber as the Delete in the bar
        confirm.setOnShowListener {
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(host.getColor(R.color.colorWarning))
        }
        host.showDialog(confirm)
    }

    private fun deleteLogs(files: List<File>) {
        var deleted = 0
        var replayedWasDeleted = false
        for (file in files) {
            if (file.delete()) {
                deleted++
                // the CSV recorded alongside it, as deleting one log does
                File(file.parentFile, host.replaceExtension(file.name, ".csv")).delete()
                if (file.name == host.replayFileString) replayedWasDeleted = true
            }
        }
        val failed = files.size - deleted
        val noun = if (deleted == 1) "log" else "logs"
        val message = if (failed == 0) {
            "Deleted $deleted $noun"
        } else {
            "Deleted $deleted $noun, $failed could not be removed"
        }
        Toast.makeText(host, message, Toast.LENGTH_LONG).show()

        // Drop the bold last-opened mark only if that flight was among the deleted;
        // a partial delete that spares it leaves it marked (as single-delete does).
        if (files.any { it.name == host.lastFileDialogSelection }) host.lastFileDialogSelection = ""
        // Only when the log now playing is one of the deleted: a partial delete
        // that spares it leaves the replay running. closeReplay first, by the
        // same doctrine the close button follows: everything the idle switch
        // asks — is this still a replay? — must be answered truthfully by the
        // time it is asked, or startFr24 declines and the sky stays empty until
        // the next pause.
        if (replayedWasDeleted) {
            host.closeReplay()
            host.switchToIdleState()
        }
        // The manager is still open behind the confirmation, now showing rows
        // for files that are gone. Refresh it in place on the survivors.
        filesChanged()
    }
}
