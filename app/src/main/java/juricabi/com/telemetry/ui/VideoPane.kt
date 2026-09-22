package juricabi.com.telemetry.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import juricabi.com.telemetry.R
import juricabi.com.telemetry.manager.PreferenceManager
import juricabi.com.telemetry.utils.DebugLog
import juricabi.com.telemetry.utils.NetworkBinder
import juricabi.com.telemetry.video.MjpegSource
import juricabi.com.telemetry.video.RtspSource
import juricabi.com.telemetry.video.StreamRecorder
import juricabi.com.telemetry.video.UdpSource
import juricabi.com.telemetry.video.UsbUvcSource
import juricabi.com.telemetry.video.VideoSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The live picture: over the map, under the readouts — and everything that
 * owns it. The wish to watch, the stale-events generation, the retry that
 * keeps the card standing, the split the divider drags, and the permission
 * choreography all live here; they used to be a smear across the activity,
 * and the generation guard exists precisely because this state had no owner.
 *
 * The activity keeps its lifecycle and its one permission funnel: results
 * come back through the on*Permission methods, and the pane asks through
 * [askPermission] with the codes the activity dispatches on.
 */
class VideoPane(
    private val activity: Activity,
    private val preferenceManager: PreferenceManager,
    /** The activity's one dialog funnel. */
    private val showDialog: (AlertDialog) -> Unit,
    /** The activity's one permission funnel. */
    private val askPermission: (String, Int) -> Unit,
    private val cameraPermissionCode: Int,
    private val recordAudioPermissionCode: Int,
    /** The picture was expanded or collapsed; the window's bars follow. */
    private val onExpandedChanged: () -> Unit
) {

    private val videoButton: ImageView = activity.findViewById(R.id.video_button)
    private val videoSoundButton: ImageView = activity.findViewById(R.id.video_sound_button)
    private val videoRotateButton: ImageView = activity.findViewById(R.id.video_rotate_button)
    private val videoRecordButton: ImageView = activity.findViewById(R.id.video_record_button)
    private val videoRecordLabel: TextView = activity.findViewById(R.id.video_record_label)
    private val videoExpandButton: ImageView = activity.findViewById(R.id.video_expand_button)
    private val videoDivider: View = activity.findViewById(R.id.video_divider)
    private val videoView: SurfaceView = activity.findViewById(R.id.video_view)
    private val videoHalf: FrameLayout = activity.findViewById(R.id.video_half)
    private val videoWaiting: TextViewOutline = activity.findViewById(R.id.video_waiting)
    private val videoBlank: View = activity.findViewById(R.id.video_blank)
    private val flightPane: LinearLayout = activity.findViewById(R.id.flight_pane)
    private val mapPane: FrameLayout = activity.findViewById(R.id.map_pane)
    private val topLayout: View = activity.findViewById(R.id.top_layout)
    private val bottomLayout: View = activity.findViewById(R.id.bottom_layout)

    init {
        videoButton.setOnClickListener { toggle() }
        videoButton.setOnLongClickListener { showVideoSettings(); true }
        videoSoundButton.imageAlpha = 128
        videoSoundButton.setOnClickListener { toggleSound() }
        videoRotateButton.imageAlpha = 128
        videoRotateButton.setOnClickListener { rotate() }
        // it never lights up for being turned — it wears the turn as its own
        // angle instead; a press is the only thing that brightens it, since
        // its flat backing has no pressed state of its own
        videoRotateButton.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> (v as ImageView).imageAlpha = 255
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    (v as ImageView).imageAlpha = 128
            }
            false
        }
        videoDivider.setOnTouchListener { _, event -> dragSplit(event) }
        videoRecordButton.setOnClickListener { toggleRecording() }
        videoExpandButton.setOnClickListener { setExpanded(!expanded) }
    }

    private var videoSource: VideoSource? = null

    /**
     * The wish to watch, surviving pauses and rotations while the camera or
     * the stream itself is released with the screen and started again with it.
     */
    private var videoWanted = false

    /**
     * The stream's sound, for the sources that could carry any. Off until its
     * button is tapped, so the picture starts at once instead of waiting on
     * an audio track — the wish survives rotations but not the app.
     */
    private var videoAudioOn = false

    /**
     * Every start numbers its events, and stopping a source retires the
     * number. A stopped source's last words can still be queued on their way
     * to the UI thread, and unguarded they acted on the fresh source's
     * screen — one dead stream's parting trouble kept folding away its
     * healthy successor under quick re-taps.
     */
    private var videoGeneration = 0

    /**
     * Tries the stream again after trouble stopped the source but left the
     * pane standing. Cancelled wherever the pane closes or the screen goes.
     */
    private val videoRetry = Runnable {
        if (videoWanted && videoSource == null) startVideo(retrying = true)
    }

    /**
     * The picture over the whole screen: the map, the readouts and the
     * system bars step aside. A way of watching the picture, so it lives and
     * dies with it — survives a rotation, never outlives the picture itself.
     */
    var expanded = false
        private set

    /**
     * The recording in progress, if any: its file, and when it began on the
     * boot clock. Both outlive the recorder writing them, which goes with the
     * source's screen — a rotation closes it and the next one appends, so one
     * flight stays one file.
     */
    private var recording: File? = null
    private var recordingSince = 0L
    private var recorder: StreamRecorder? = null

    /**
     * Recorders closed because their recording ended, with why (null: asked
     * for). The toast waits for the file to be shut, since only then is it
     * known whether anything was recorded at all.
     */
    private val finishing = HashMap<StreamRecorder, String?>()

    fun saveInto(outState: Bundle) {
        outState.putBoolean("video_wanted", videoWanted)
        outState.putBoolean("video_audio", videoAudioOn)
        outState.putBoolean("video_expanded", expanded)
        outState.putString("video_recording", recording?.path)
        outState.putLong("video_recording_since", recordingSince)
    }

    fun restoreFrom(savedInstanceState: Bundle) {
        videoWanted = savedInstanceState.getBoolean("video_wanted", false)
        videoAudioOn = savedInstanceState.getBoolean("video_audio", false)
        expanded = savedInstanceState.getBoolean("video_expanded", false)
        recording = savedInstanceState.getString("video_recording")?.let(::File)
        recordingSince = savedInstanceState.getLong("video_recording_since", 0L)
    }

    private fun newVideoEvents(): VideoSource.Events {
        val generation = ++videoGeneration
        fun current() = generation == videoGeneration && videoWanted
        return object : VideoSource.Events {
            override fun onLive() {
                activity.runOnUiThread {
                    if (current()) videoWaiting.visibility = View.GONE
                }
            }

            override fun onIdle() {
                activity.runOnUiThread {
                    if (!current()) return@runOnUiThread
                    // The picture stopped and may return; the card says so
                    // where the picture was, instead of the layout jumping
                    // about. Its last frame goes: a SurfaceView takes a new
                    // size only with its next frame, and a silent stream sends
                    // none — a frame left standing through an expand, a
                    // collapse or a drag of the divider stayed at its old
                    // size, spread over the map. The card stands on an empty
                    // surface, and the stream's next frame brings it back.
                    videoWaiting.visibility = View.VISIBLE
                    freshSurface()
                }
            }

            override fun onTrouble(what: String) {
                activity.runOnUiThread {
                    if (!current()) return@runOnUiThread
                    // trouble ends any turn in progress — lift the rotate cover
                    // so the message is not read through black (a stream that
                    // died at the very moment of a turn would leave it standing)
                    videoBlank.visibility = View.GONE
                    if (preferenceManager.getVideoSource() == "network") {
                        // An unreachable stream is a waiting state, not a
                        // verdict — the server may simply not be up yet. The
                        // pane stays, like the USB half waiting for its
                        // camera: the card says what is wrong and the stream
                        // is tried again. Folding on the spot made the button
                        // read as broken — a tap opened the half for the
                        // tenth of a second four refused connections take,
                        // then it snapped shut, and the field logs are full
                        // of the re-taps. USB keeps the fold: retrying there
                        // would re-ask the USB permission at every turn.
                        videoGeneration++
                        videoSource?.stop()
                        videoSource = null
                        videoWaiting.text = "$what — trying again…"
                        videoWaiting.visibility = View.VISIBLE
                        videoWaiting.removeCallbacks(videoRetry)
                        videoWaiting.postDelayed(videoRetry, 2000)
                    } else {
                        Toast.makeText(activity, "Video: $what", Toast.LENGTH_LONG).show()
                        hide()
                    }
                }
            }

            override fun onCovered(covered: Boolean) {
                activity.runOnUiThread {
                    if (current()) videoBlank.visibility =
                        if (covered) View.VISIBLE else View.GONE
                }
            }

            override fun onAudioLost() {
                activity.runOnUiThread {
                    if (!current()) return@runOnUiThread
                    videoAudioOn = false
                    videoSoundButton.imageAlpha = 128
                    Toast.makeText(
                        activity,
                        "This stream's sound never arrived — playing the picture alone",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** The button lives only while a source is chosen, so it can never do nothing. */
    fun updateControls() {
        val configured = preferenceManager.getVideoSource() != "off"
        videoButton.visibility = if (configured) View.VISIBLE else View.GONE
        if (!configured && videoWanted) hide()
    }

    private fun toggle() {
        if (videoWanted) hide() else show()
    }

    /**
     * The source and its address, reached by a long press on the video
     * button — the switch a flying day makes over and over, between the bench
     * camera, the goggles and the ground station, without leaving the map for
     * the settings list. It writes the same "settings" store the settings
     * screen does and carries the same recent addresses, so the two ways of
     * choosing never disagree.
     */
    private fun showVideoSettings() {
        val content = activity.layoutInflater.inflate(R.layout.dialog_video_settings, null)
        val group = content.findViewById<RadioGroup>(R.id.video_settings_source)
        val usbButton = content.findViewById<RadioButton>(R.id.video_settings_usb)
        val networkButton = content.findViewById<RadioButton>(R.id.video_settings_network)
        val addressGroup = content.findViewById<View>(R.id.video_settings_address_group)
        val input = content.findViewById<EditText>(R.id.video_settings_url)
        val udpToggle = content.findViewById<CompoundButton>(R.id.video_settings_rtsp_udp)
        val recents = content.findViewById<LinearLayout>(R.id.video_settings_recents)

        when (preferenceManager.getVideoSource()) {
            "usb" -> usbButton.isChecked = true
            "network" -> networkButton.isChecked = true
            else -> content.findViewById<RadioButton>(R.id.video_settings_off).isChecked = true
        }
        udpToggle.isChecked = preferenceManager.getRtspUdp()
        input.setText(preferenceManager.getVideoStreamUrl())
        input.setSelection(input.text.length)
        // the address and the transport it rides both belong to a network
        // stream, and fold away together under the other sources
        addressGroup.visibility = if (networkButton.isChecked) View.VISIBLE else View.GONE
        group.setOnCheckedChangeListener { _, _ ->
            addressGroup.visibility = if (networkButton.isChecked) View.VISIBLE else View.GONE
        }

        val history = preferenceManager.getVideoUrlHistory()
        if (history.isEmpty()) {
            content.findViewById<TextView>(R.id.video_settings_recents_title).visibility = View.GONE
        }
        val ripple = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        val pad = (12 * activity.resources.displayMetrics.density).toInt()
        for (url in history) {
            val row = TextView(activity)
            row.text = url
            row.textSize = 16f
            row.setPadding(0, pad, 0, pad)
            row.setBackgroundResource(ripple.resourceId)
            row.setOnClickListener {
                // a remembered address is a network one by definition
                networkButton.isChecked = true
                input.setText(url)
                input.setSelection(input.text.length)
            }
            recents.addView(row)
        }

        showDialog(
            AlertDialog.Builder(activity)
                .setTitle("Video source")
                .setView(content)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val chosen = when {
                        usbButton.isChecked -> "usb"
                        networkButton.isChecked -> "network"
                        else -> "off"
                    }
                    if (chosen == "network") {
                        val typed = input.text.toString().trim()
                        preferenceManager.setVideoStreamUrl(typed)
                        if (typed.isNotBlank()) preferenceManager.rememberVideoUrl(typed)
                        preferenceManager.setRtspUdp(udpToggle.isChecked)
                    }
                    applyVideoSource(chosen)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        )
    }

    private fun applyVideoSource(source: String) {
        val wasShowing = videoWanted
        preferenceManager.setVideoSource(source)
        // shows or hides the button, and folds a live picture away if now off
        updateControls()
        if (source == "off") {
            // the button that opened this is gone with the source; say where
            // it went, or turning it back on looks impossible
            Toast.makeText(
                activity,
                "Video turned off — turn it back on under Settings ▸ Video",
                Toast.LENGTH_LONG
            ).show()
        } else if (wasShowing) {
            // a picture already up takes the new source at once, no extra tap
            hide()
            show()
        }
    }

    /**
     * The configured source, or null having said why there is nothing to
     * start. One network entry, and the address's scheme picks the decoder:
     * the scheme already states the protocol, and a separate choice to keep
     * in agreement with it would only add a way to disagree.
     */
    private fun buildVideoSource(): VideoSource? {
        val events = newVideoEvents()
        // rotation crosses the seam as a question the source asks back —
        // the setting stays the host's, and video/ stops reading manager/
        val turn = { preferenceManager.getVideoRotation() }
        return when (preferenceManager.getVideoSource()) {
            "usb" -> UsbUvcSource(activity, turn, events)
            "network" -> {
                // trimmed, because a keyboard's autocomplete space made a
                // right address fail with a toast insisting it was wrong
                val url = preferenceManager.getVideoStreamUrl().trim()
                // The road to the camera, chosen by who routes to it — the
                // goggle's Wi-Fi, a USB adapter — so preferred mobile data
                // cannot swallow the stream while the maps keep riding it.
                // The same pin the telemetry link has. RTSP resolves it anew
                // through the lambda on every rebuild, because a recovery may
                // be the moment the right network finally exists — the pane
                // opened before the goggle's Wi-Fi was joined, or a Wi-Fi
                // blip mid-flight replaced the network the factory was born
                // on. MJPEG re-resolves by rebuilding the source per retry.
                val binder = NetworkBinder(activity)
                val streamHost = android.net.Uri.parse(url).host ?: ""
                val trouble = when {
                    url.isBlank() -> "No stream address set — enter one under Settings, Video"
                    url.startsWith("rtsp://", ignoreCase = true) ->
                        return RtspSource(
                            activity, url, preferenceManager.getRtspUdp(),
                            { binder.networkTo(streamHost)?.socketFactory }, turn, events
                        )
                    url.startsWith("http://", ignoreCase = true) ||
                        url.startsWith("https://", ignoreCase = true) ->
                        return MjpegSource(url, binder.networkTo(streamHost), turn, events)
                    url.startsWith("udp://", ignoreCase = true) -> {
                        // a pushed stream has no address to dial, only the
                        // port here to listen on — udp://5600 and
                        // udp://0.0.0.0:5600 both name it
                        val port = url.substring(6).trim('/')
                            .substringAfterLast(':').toIntOrNull()
                        if (port != null && port in 1..65535)
                            return UdpSource(port, turn, events)
                        "udp:// needs the port the stream is pushed to, like udp://5600"
                    }
                    else -> "The stream address must start rtsp:// (RTSP), " +
                        "http(s):// (MJPEG) or udp:// (a pushed RTP stream)"
                }
                Toast.makeText(activity, trouble, Toast.LENGTH_LONG).show()
                null
            }
            else -> null
        }
    }

    private fun show() {
        videoWanted = true
        startVideo()
    }

    /**
     * Along the axis the screen has more of: the picture left of the map
     * held landscape, above it held upright. The picture takes the share
     * the divider was last dragged to, remembered for each orientation, and
     * the map takes the rest. A rotation rebuilds the screen, so this is
     * decided fresh each time video starts.
     */
    private fun arrangeFlightPane() {
        val landscape =
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        flightPane.orientation =
            if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        // coerced here too, so a split saved under looser old bounds obeys
        val share = preferenceManager.getVideoSplit(landscape).coerceIn(0.3f, 0.5f)
        // The grab band costs no screen: its margins pull it back exactly
        // its own thickness, so the halves meet edge to edge and the band
        // floats over the seam, half on each. Raised so it draws — and is
        // touched — above both.
        val grab = (20 * activity.resources.displayMetrics.density).toInt()
        val divider = LinearLayout.LayoutParams(
            if (landscape) grab else match, if (landscape) match else grab
        )
        if (landscape) {
            divider.leftMargin = -grab / 2
            divider.rightMargin = -grab / 2
        } else {
            divider.topMargin = -grab / 2
            divider.bottomMargin = -grab / 2
        }
        videoDivider.layoutParams = divider
        videoDivider.elevation = 2 * activity.resources.displayMetrics.density
        for ((half, weight) in listOf(videoHalf to share, mapPane to 1f - share)) {
            half.layoutParams = LinearLayout.LayoutParams(
                if (landscape) 0 else match, if (landscape) match else 0, weight
            )
        }
    }

    /**
     * The divider under a finger: the split follows the touch, and the
     * sources refit their pictures on the layout changes this causes. The
     * picture may shrink to 30% but never grow past half — the map is the
     * flight, and a video allowed to crowd it out got dragged there by
     * accident more than by wish. The landing place is written down only
     * when the finger lifts.
     */
    private fun dragSplit(event: MotionEvent): Boolean {
        val landscape =
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val paneOnScreen = IntArray(2)
        flightPane.getLocationOnScreen(paneOnScreen)
        val total = (if (landscape) flightPane.width else flightPane.height).toFloat()
        if (total <= 0) return true
        val at = if (landscape) event.rawX - paneOnScreen[0] else event.rawY - paneOnScreen[1]
        val share = (at / total).coerceIn(0.3f, 0.5f)
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        for ((half, weight) in listOf(videoHalf to share, mapPane to 1f - share)) {
            half.layoutParams = LinearLayout.LayoutParams(
                if (landscape) 0 else match, if (landscape) match else 0, weight
            )
        }
        // a drag the system cancels still moved the split; save that too,
        // or the next layout snaps it back to a place the finger left
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            preferenceManager.setVideoSplit(landscape, share)
            videoDivider.performClick()
        }
        return true
    }

    /**
     * A surface with no history: removing and re-adding the SurfaceView
     * destroys its surface and creates a fresh one, and whatever it last
     * showed goes with the old.
     */
    private fun freshSurface() {
        (videoView.parent as ViewGroup).let { parent ->
            val at = parent.indexOfChild(videoView)
            parent.removeViewAt(at)
            parent.addView(videoView, at)
        }
    }

    private fun startVideo(retrying: Boolean = false) {
        // Each source gets a surface with no history, so a decoder or a
        // canvas from the previous source can never draw the wrong picture
        // into the next — and the dead stream's last frame is retired with
        // the old surface. Done before every branch, so the camera-permission
        // card below never stands on a stale picture.
        freshSurface()
        // Android hands a camera-class USB device only to a holder of the
        // camera permission; without it the USB ask is refused instantly and
        // silently, which the field read as a "no" that could never be taken
        // back. Asked here, where the wish to watch was just expressed —
        // granting resumes below, refusing folds the half away and says why.
        if (preferenceManager.getVideoSource() == "usb" &&
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // the half opens on the ask, its card saying what is being
            // waited for — left untouched, whatever happened to be on screen
            // stood over the dialog and read as broken
            videoWaiting.text = "Waiting for the camera permission…"
            videoWaiting.visibility = View.VISIBLE
            arrangeFlightPane()
            videoHalf.visibility = View.VISIBLE
            applyExpanded()
            videoSoundButton.visibility = View.GONE
            videoRotateButton.visibility = View.GONE
            videoRecordButton.visibility = View.GONE
            DebugLog.note("Video", "camera permission asked")
            askPermission(android.Manifest.permission.CAMERA, cameraPermissionCode)
            return
        }
        val source = buildVideoSource()
        if (source == null) {
            hide()
            return
        }
        videoSource = source
        // The card says what is being waited for, and the first real frame
        // replaces it. A retry keeps the trouble message standing instead:
        // flipping to "Connecting…" for the split second a refused
        // connection takes made the card flicker every two seconds.
        if (!retrying) videoWaiting.text = if (preferenceManager.getVideoSource() == "usb")
            "Waiting for the USB camera…" else "Connecting to the stream…"
        videoWaiting.visibility = View.VISIBLE
        arrangeFlightPane()
        videoHalf.visibility = View.VISIBLE
        applyExpanded()
        // the speaker only where there could be sound; remembered before
        // start so the choice needs no second session
        videoSoundButton.visibility = if (source.hasAudio) View.VISIBLE else View.GONE
        videoSoundButton.imageAlpha = if (videoAudioOn) 255 else 128
        // remembered on, so bring it up now — asking for the mic permission
        // here if the source's sound needs it, the same nice ask as the tap
        if (videoAudioOn) enableSound()
        // the remembered turn, for a camera mounted sideways; the button
        // wears the same angle so it shows which turn is on
        videoRotateButton.visibility = View.VISIBLE
        videoRotateButton.rotation = preferenceManager.getVideoRotation().toFloat()
        videoRecordButton.visibility = View.VISIBLE
        showRecording()
        // a recording running when the source was rebuilt — a retry, a
        // rotation, coming back to the screen — carries on into the same file
        if (recording != null) {
            if (recorder == null) openRecorder(append = true)
            source.record(recorder)
        }
        source.start(videoView)
    }

    /** A quarter-turn per tap, for a camera mounted sideways; 0 comes back around. */
    private fun rotate() {
        val degrees = (preferenceManager.getVideoRotation() + 90) % 360
        preferenceManager.setVideoRotation(degrees)
        // the button turns with the picture, so its icon shows which of the
        // four turns is on — a brightness that only said "not zero" did not
        videoRotateButton.rotation = degrees.toFloat()
        // The turn is the source's now, not the view's — a SurfaceView will
        // not rotate its surface content. refit carries the new turn to the
        // source, which takes it up in place: the decoder rebuilds with it,
        // no reconnect, a keyframe's worth of black at most.
        videoSource?.refit()
    }

    private fun toggleSound() {
        if (videoAudioOn) {
            // turning the sound off never needs anything
            videoAudioOn = false
            videoSoundButton.imageAlpha = 128
            videoSource?.setAudio(false)
        } else {
            enableSound()
        }
    }

    /**
     * Turn the picture's sound on, first asking for the microphone permission
     * if this source's sound needs it — the USB path, whose audio is a USB
     * input device the OS guards. The ask is raised on the speaker tap or at
     * start, where the wish is plain and cannot get buried, and granting comes
     * back through onRecordAudioPermission to finish the job; a refusal
     * leaves the picture playing on, silent.
     */
    private fun enableSound() {
        val source = videoSource ?: return
        if (source.needsRecordAudio &&
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission(android.Manifest.permission.RECORD_AUDIO, recordAudioPermissionCode)
            return
        }
        videoAudioOn = true
        videoSoundButton.imageAlpha = 255
        source.setAudio(true)
    }

    private fun hide() {
        videoWanted = false
        videoGeneration++ // whatever the stopped source still says is stale
        videoWaiting.removeCallbacks(videoRetry)
        stopRecording(null)
        videoSource?.stop()
        videoSource = null
        videoBlank.visibility = View.GONE
        videoHalf.visibility = View.GONE
        setExpanded(false)
    }

    // ---- the picture over the whole screen ---------------------------------

    private fun setExpanded(on: Boolean) {
        val changed = expanded != on
        expanded = on
        applyExpanded()
        if (changed) {
            // written down: hiding the map takes its surfaces away, and a
            // field log must be able to tell that from anything else doing so
            DebugLog.note("Video", if (on) "picture expanded" else "picture collapsed")
            onExpandedChanged()
        }
    }

    /** Back collapses an expanded picture before it does anything else. */
    fun collapse() = setExpanded(false)

    /**
     * Lays the screen out for the state: expanded, everything but the picture
     * steps aside, and the picture — the only weighted child of the flight
     * pane left — takes it all; otherwise the split stands as dragged. The map
     * gone is the map not drawn, so an expanded picture has the GPU to itself.
     */
    private fun applyExpanded() {
        val showing = videoHalf.visibility == View.VISIBLE
        val over = expanded && showing
        val rest = if (over) View.GONE else View.VISIBLE
        topLayout.visibility = rest
        bottomLayout.visibility = rest
        mapPane.visibility = rest
        videoDivider.visibility = if (showing && !over) View.VISIBLE else View.GONE
        videoExpandButton.setImageResource(if (over) R.drawable.ic_collapse else R.drawable.ic_expand)
        videoExpandButton.contentDescription =
            if (over) "Back to the map" else "Expand the picture"
    }

    // ---- recording -----------------------------------------------------------

    private fun toggleRecording() {
        if (recording != null) stopRecording(null) else startRecording()
    }

    /**
     * Recordings go to Movies/Telemetry, beside the phone's other videos, and
     * not in TelemetryLogs: that folder is synced off the phone, and a flight
     * of video is gigabytes. Named by the same stamp as the flight logs, so
     * the two pair up by eye without being tied together.
     */
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                activity, "Recording needs the storage permission — allow it in the app's settings",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Telemetry"
        )
        dir.mkdirs()
        if (dir.usableSpace < 500L shl 20) {
            Toast.makeText(activity, "Not enough free storage to record", Toast.LENGTH_LONG).show()
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())
        // two recordings in one second must not overwrite each other: the
        // name is claimed here, at once, not when the writer thread gets to it
        val file = try {
            generateSequence(1) { it + 1 }
                .map { File(dir, if (it == 1) "$stamp.ts" else "$stamp ($it).ts") }
                .first { it.createNewFile() }
        } catch (e: java.io.IOException) {
            Toast.makeText(activity, "Cannot record: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        recording = file
        recordingSince = SystemClock.elapsedRealtime()
        openRecorder(append = false)
        // No running source is fine — a network stream between retries: the
        // recording opens now and the next source joins it as it starts.
        videoSource?.record(recorder)
        DebugLog.note("Video", "record start ${file.name}")
        Toast.makeText(
            activity, "Recording the picture, without sound, to Movies/Telemetry",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openRecorder(append: Boolean) {
        val file = recording ?: return
        recorder = StreamRecorder(file, append, recordingSince * 1000, onClosed = { closed, endedBy ->
            // into the gallery, every time it closes — a rotation included, so
            // a recording the app never gets to finish is still found there
            if (closed.file.exists()) MediaScannerConnection.scanFile(
                activity.applicationContext, arrayOf(closed.file.path), arrayOf("video/mp2t"), null
            )
            activity.runOnUiThread {
                // ended by itself — storage, a write error, the encoder
                if (recorder === closed) stopRecording(endedBy ?: "the recording stopped")
                if (finishing.containsKey(closed)) announce(closed.file, finishing.remove(closed))
            }
        })
        showRecording()
    }

    private fun announce(file: File, why: String?) {
        val text = when {
            // nothing written — and when something stopped it, that is the
            // news, not a picture that never came
            !file.exists() ->
                if (why == null) "Nothing was recorded — no picture arrived"
                else "Recording stopped — $why. Nothing was saved"
            why == null -> "Recording saved as Movies/Telemetry/${file.name}"
            else -> "Recording stopped — $why. Saved as Movies/Telemetry/${file.name}"
        }
        // the application's context: this can land after the screen is gone
        Toast.makeText(activity.applicationContext, text, Toast.LENGTH_LONG).show()
    }

    /**
     * Takes the recorder from the source and closes it. Whether the recording
     * goes on is the caller's: the screen going away keeps it, a stop ends it.
     */
    private fun closeRecorder() {
        videoSource?.record(null)
        recorder?.close()
        recorder = null
    }

    /**
     * Ends the recording — asked for when [why] is null, or forced by what
     * [why] says. The toast follows once the file is shut (see [finishing]).
     */
    private fun stopRecording(why: String?) {
        val file = recording ?: return
        val writing = recorder
        if (writing != null) {
            finishing[writing] = why
        } else {
            // no writer is open to judge the file — the screen went away
            // mid-recording and the video was then turned off in Settings
            if (file.length() == 0L) file.delete()
            announce(file, why)
        }
        closeRecorder()
        recording = null
        showRecording()
        DebugLog.note("Video", "record stop ${file.name}" + (why?.let { ": $it" } ?: ""))
    }

    /** The button and the label say whether a recording runs, and the label how long and how much. */
    private fun showRecording() {
        val on = recording != null
        videoRecordButton.imageAlpha = if (on) 255 else 128
        videoRecordLabel.visibility = if (on) View.VISIBLE else View.GONE
        videoRecordLabel.removeCallbacks(recordTicker)
        if (on) recordTicker.run()
    }

    private val recordTicker = object : Runnable {
        override fun run() {
            val file = recording ?: return
            val seconds = (SystemClock.elapsedRealtime() - recordingSince) / 1000
            videoRecordLabel.text = "REC %d:%02d · %d MB".format(
                seconds / 60, seconds % 60, file.length() shr 20
            )
            videoRecordLabel.postDelayed(this, 1000)
        }
    }

    /**
     * Coming back to the screen: the wish to watch survived, the source did
     * not. If neither the button nor a rotation restarted it, do so now —
     * onStop releases the source under every system dialog too, so a run of
     * these in the log means something keeps pausing the screen.
     */
    fun restartIfWanted() {
        if (videoWanted && videoSource == null) {
            DebugLog.note("Video", "restart on resume")
            startVideo()
        }
    }

    /**
     * The camera or the stream goes with the screen — this screen, truly
     * gone, not merely paused. Released in onPause it died under every
     * system dialog, and the USB permission ask is itself such a dialog:
     * releasing cancelled the pending request, the restart asked again,
     * and the field watched dialogs churn at three a second. videoWanted
     * stays, and coming back starts it again.
     */
    fun releaseForStop() {
        videoGeneration++
        videoWaiting.removeCallbacks(videoRetry)
        videoRecordLabel.removeCallbacks(recordTicker)
        // a screen that is finishing — Back on older Androids — takes the
        // recording with it, announced like every other ending; any other
        // stop only pauses it
        if (activity.isFinishing) stopRecording(null) else closeRecorder()
        videoSource?.stop()
        videoSource = null
    }

    /** An interrupted ask delivers no result; the waiting half must not outlive it. */
    fun onCameraAskInterrupted() {
        DebugLog.note("Video", "camera permission ask interrupted")
        hide()
    }

    /** True resumes the start the ask paused; false folds the half away. */
    fun onCameraPermission(granted: Boolean): Boolean {
        if (granted) {
            DebugLog.note("Video", "camera permission granted")
            if (videoWanted && videoSource == null) startVideo()
        } else {
            DebugLog.note("Video", "camera permission denied")
            hide()
        }
        return granted
    }

    /** Finish what the speaker tap or the start began, on the source now running. */
    fun onRecordAudioPermission(granted: Boolean): Boolean {
        if (granted) {
            DebugLog.note("Video", "record-audio permission granted")
            val source = videoSource
            if (source != null && source.hasAudio) {
                videoAudioOn = true
                videoSoundButton.imageAlpha = 255
                source.setAudio(true)
            }
        } else {
            DebugLog.note("Video", "record-audio permission denied")
            videoAudioOn = false
            videoSoundButton.imageAlpha = 128
        }
        return granted
    }
}
