package juricabi.com.telemetry.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
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
import juricabi.com.telemetry.video.UdpSource
import juricabi.com.telemetry.video.UsbUvcSource
import juricabi.com.telemetry.video.VideoSource

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
    private val recordAudioPermissionCode: Int
) {

    private val videoButton: ImageView = activity.findViewById(R.id.video_button)
    private val videoSoundButton: ImageView = activity.findViewById(R.id.video_sound_button)
    private val videoRotateButton: ImageView = activity.findViewById(R.id.video_rotate_button)
    private val videoDivider: View = activity.findViewById(R.id.video_divider)
    private val videoView: SurfaceView = activity.findViewById(R.id.video_view)
    private val videoHalf: FrameLayout = activity.findViewById(R.id.video_half)
    private val videoWaiting: TextViewOutline = activity.findViewById(R.id.video_waiting)
    private val videoBlank: View = activity.findViewById(R.id.video_blank)
    private val flightPane: LinearLayout = activity.findViewById(R.id.flight_pane)
    private val mapPane: FrameLayout = activity.findViewById(R.id.map_pane)

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

    fun saveInto(outState: Bundle) {
        outState.putBoolean("video_wanted", videoWanted)
        outState.putBoolean("video_audio", videoAudioOn)
    }

    fun restoreFrom(savedInstanceState: Bundle) {
        videoWanted = savedInstanceState.getBoolean("video_wanted", false)
        videoAudioOn = savedInstanceState.getBoolean("video_audio", false)
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
                    // the picture stopped and may return; the card says so where
                    // the picture was, instead of the layout jumping about
                    if (current()) videoWaiting.visibility = View.VISIBLE
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

    private fun startVideo(retrying: Boolean = false) {
        // Each source gets a surface with no history: removing and re-adding
        // the SurfaceView destroys its surface and creates a fresh one, so a
        // decoder or a canvas from the previous source can never draw the
        // wrong picture into the next — and the dead stream's last frame is
        // retired with the old surface. Done before every branch, so the
        // camera-permission card below never stands on a stale picture.
        (videoView.parent as ViewGroup).let { parent ->
            val at = parent.indexOfChild(videoView)
            parent.removeViewAt(at)
            parent.addView(videoView, at)
        }
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
            videoDivider.visibility = View.VISIBLE
            videoSoundButton.visibility = View.GONE
            videoRotateButton.visibility = View.GONE
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
        videoDivider.visibility = View.VISIBLE
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
        videoSource?.stop()
        videoSource = null
        videoBlank.visibility = View.GONE
        videoHalf.visibility = View.GONE
        videoDivider.visibility = View.GONE
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
