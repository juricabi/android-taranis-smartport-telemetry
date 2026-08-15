package juricabi.com.telemetry.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import juricabi.com.telemetry.manager.PreferenceManager
import juricabi.com.telemetry.utils.DebugLog

/**
 * Network video — an OpenIPC or OpenHD ground station, a WiFi VRX box —
 * spoken over RTSP.
 *
 * ExoPlayer left to its defaults buffers seconds of stream for smoothness's
 * sake. This is a pilot's view, not a film: the buffers are pinned to about a
 * third of a second, which lands around half a second glass to glass. Truly
 * low-latency FPV would need raw RTP into a MediaCodec, and is not attempted.
 *
 * The picture starts alone and starts at once. Playback waits for every
 * selected track, and cameras routinely advertise an audio track they never
 * feed — selected, it holds the first frame forever: one picture, then
 * stone. Sound is therefore joined only when asked for (the speaker button),
 * and if the stream cannot start with it, it is dropped again and said so —
 * the picture always wins. Stalls and errors rejoin the live stream, running
 * forgives, and only three failures in a row drop back to the map. A stream
 * that arrives torn or starved retires UDP and rejoins over TCP.
 */
@OptIn(UnstableApi::class)
class RtspSource(
    private val context: Context,
    private val url: String,
    private val events: VideoSource.Events
) : VideoSource {

    private var player: ExoPlayer? = null
    private var view: TextureView? = null
    private var recoveries = 0
    private var audioOn = false
    private var playedSinceChange = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * A lossy link is a property of the address, not of the session. The
     * lesson used to die with the source — every rotation and every session
     * re-ran the torn UDP second, and its smeared frames were what the field
     * reported as "blobbed" pictures. Now an address that once tore starts
     * on TCP for good; entering a different address forgets the lesson.
     */
    private var forceTcp = PreferenceManager(context).getVideoTcpUrl() == url

    private fun retireUdp() {
        forceTcp = true
        PreferenceManager(context).setVideoTcpUrl(url)
        DebugLog.note("Video", "rtsp switching to TCP transport")
    }

    // the address with any credentials kept out of the notes
    private val said = url.replace(Regex("//[^/@]+@"), "//<auth>@")

    private fun causeChain(e: Throwable): String =
        generateSequence(e) { it.cause }
            .joinToString(" << ") { it.message ?: it.javaClass.simpleName }

    override val hasAudio get() = true

    override fun setAudio(on: Boolean) {
        if (audioOn == on) return
        audioOn = on
        DebugLog.note("Video", "rtsp audio ${if (on) "on" else "off"}")
        val p = player ?: return // before start: remembered for it
        applyAudio(p)
        playedSinceChange = false
        recoveries = 0
        p.stop()
        p.prepare()
    }

    private fun applyAudio(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audioOn)
            .build()
    }

    // refits the letterbox when showing or the fullscreen toggle resizes the pane
    private val refitOnLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        refit()
    }

    override fun refit() {
        val size = player?.videoSize ?: return
        view?.fitPicture(size.width, size.height)
    }

    // Past the player's own UDP-to-TCP fallback, which runs at eight
    // seconds — a rejoin before that would start a fresh UDP session and
    // keep a TCP-only camera from ever being reached.
    private val stalled = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) rejoin("the stream stalled")
    }

    /**
     * Playing by its own clock yet sending nothing to the screen — the
     * buffering watchdog is blind to that state, so the rendered-frame count
     * is watched instead. A session that only ever brings one frame is
     * caught by frozenRejoins, which running alone does not forgive: each
     * rejoin renders one frame again, and a counter reset by that single
     * frame looped forever.
     */
    private var lastRendered = 0
    private var frozenRejoins = 0

    private val starving = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState == Player.STATE_READY) {
                // Latency creep: every stall leaves the picture a little
                // further behind the camera, and an RTSP session never
                // announces a live edge for the player to chase on its own.
                // A backlog past double the configured buffer is drift, not
                // buffering — jump it.
                val behind = p.totalBufferedDuration
                if (behind > 2000) {
                    DebugLog.note("Video", "rtsp ${behind}ms behind the camera, catching up")
                    p.seekToDefaultPosition()
                }
                val rendered = p.videoDecoderCounters?.renderedOutputBufferCount ?: -1
                val gained = rendered - lastRendered
                when {
                    rendered < lastRendered -> lastRendered = rendered // fresh session
                    gained >= 2 -> {
                        frozenRejoins = 0
                        lastRendered = rendered
                    }
                    gained == 0 -> {
                        DebugLog.note("Video", "rtsp starved: rendered stuck at $rendered")
                        if (++frozenRejoins > 3) {
                            events.onTrouble("$said stopped sending pictures")
                            return
                        }
                        // silent UDP loss starves the decoder without ever
                        // raising an error; the TCP escalation applies here too
                        if (!forceTcp) retireUdp()
                        rejoin("no pictures arriving")
                    }
                    else -> lastRendered = rendered
                }
            }
            handler.postDelayed(this, 4000)
        }
    }

    // The stream carried over UDP or, once the datagrams prove lossy, over
    // the RTSP connection itself. A torn FU-A fragment reads as a garbage
    // NAL type, and the field log showed exactly that: "packetization mode
    // [25..31] not supported", a different number every second, from a
    // camera that only ever sends FU-A. The player's own UDP-to-TCP
    // fallback never fires for this — it needs silence, and torn packets
    // are not silence.
    private fun mediaSource() = RtspMediaSource.Factory()
        .setForceUseRtpTcp(forceTcp)
        .createMediaSource(MediaItem.fromUri(url))

    /**
     * A live stream has no history to pick up; the recovery is a fresh
     * session. transportChanged spares the audio for one attempt: a failure
     * that just retired UDP was the transport's fault, not the audio trap's,
     * and dropping the asked-for sound on it silenced streams that would
     * have played it fine over TCP.
     */
    private fun rejoin(why: String, transportChanged: Boolean = false) {
        val p = player ?: return
        DebugLog.note("Video", "rtsp rejoin ($why), recoveries=$recoveries")
        if (!playedSinceChange && audioOn && !transportChanged) {
            // It cannot start with the sound it was just asked for — the
            // likeliest trap is an advertised audio track nothing feeds.
            // The picture wins; the screen is told so its button agrees.
            audioOn = false
            applyAudio(p)
            DebugLog.note("Video", "rtsp dropped audio to start at all")
            events.onAudioLost()
        } else if (++recoveries > 3) {
            // the whole address as tried, so a typo in the path shows in the
            // toast — an error's own words name the host at best
            events.onTrouble("$said — $why")
            return
        }
        p.stop()
        p.setMediaSource(mediaSource())
        p.prepare()
    }

    override fun start(view: TextureView) {
        DebugLog.note("Video", "rtsp start $said")
        this.view = view
        view.addOnLayoutChangeListener(refitOnLayout)
        val player = ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(300, 1000, 300, 300)
                    // start on time held, not bytes held — for a live
                    // picture the clock is the constraint that matters
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
        this.player = player
        applyAudio(player)
        player.setVideoTextureView(view)
        player.setMediaSource(mediaSource())
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                DebugLog.note("Video", "rtsp size ${videoSize.width}x${videoSize.height}")
                view.fitPicture(videoSize.width, videoSize.height)
            }

            override fun onRenderedFirstFrame() {
                DebugLog.note("Video", "rtsp first frame rendered")
                events.onLive()
            }

            override fun onTracksChanged(tracks: Tracks) {
                for (group in tracks.groups) {
                    for (i in 0 until group.length) {
                        val f = group.getTrackFormat(i)
                        DebugLog.note(
                            "Video",
                            "rtsp track ${f.sampleMimeType} ${f.width}x${f.height} " +
                                "${f.frameRate}fps selected=${group.isTrackSelected(i)}"
                        )
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                DebugLog.note("Video", "rtsp state $playbackState (1 idle 2 buffering 3 ready 4 ended)")
                handler.removeCallbacks(stalled)
                when (playbackState) {
                    // running is what forgives earlier recoveries
                    Player.STATE_READY -> {
                        playedSinceChange = true
                        recoveries = 0
                    }
                    Player.STATE_BUFFERING -> handler.postDelayed(stalled, 12000)
                    // a clean end-of-stream — the server restarting, say —
                    // raises no error and starves no watchdog; without this
                    // it left the last frame standing forever
                    Player.STATE_ENDED -> rejoin("the stream ended")
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                DebugLog.note(
                    "Video", "rtsp error ${error.errorCodeName}: ${causeChain(error)}"
                )
                // The first hard error retires UDP and takes the stream over
                // TCP, with fresh chances — over a lossy link every UDP
                // rejoin dies the same death within a second.
                val transportChanged = !forceTcp
                if (transportChanged) {
                    retireUdp()
                    recoveries = 0
                }
                rejoin(error.cause?.message ?: error.errorCodeName, transportChanged)
            }
        })
        player.playWhenReady = true
        player.prepare()
        handler.postDelayed(starving, 4000)
    }

    override fun stop() {
        DebugLog.note("Video", "rtsp stop")
        handler.removeCallbacks(stalled)
        handler.removeCallbacks(starving)
        view?.removeOnLayoutChangeListener(refitOnLayout)
        view = null
        player?.release()
        player = null
    }
}
