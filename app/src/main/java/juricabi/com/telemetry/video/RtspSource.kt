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
import juricabi.com.telemetry.utils.DebugLog

/**
 * Network video — an OpenIPC or OpenHD ground station, a WiFi VRX box —
 * spoken over RTSP.
 *
 * ExoPlayer left to its defaults buffers seconds of stream for smoothness's
 * sake. This is a pilot's view, not a film: the buffers are pinned to 150ms,
 * which lands around a third of a second glass to glass. Lower still means
 * raw RTP into a MediaCodec with no buffer at all — that exists, as the
 * udp:// source — this stays the smooth road.
 *
 * The picture starts alone and starts at once. Playback waits for every
 * selected track, and cameras routinely advertise an audio track they never
 * feed — selected, it holds the first frame forever: one picture, then
 * stone. Sound is therefore joined only when asked for (the speaker button),
 * and if the stream cannot start with it, it is dropped again and said so —
 * the picture always wins. Stalls and errors rejoin the live stream, running
 * forgives, and only three failures in a row give up. The stream rides TCP
 * from the first frame — see mediaSource for what UDP cost.
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

    // wall clock minus playback position when this READY run began; 0 while
    // there is no run to measure against
    private var liveBase = 0L

    private val starving = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState == Player.STATE_READY) {
                // Latency creep: every stall leaves the picture a little
                // further behind the camera, and an RTSP session never
                // announces a live edge for the player to chase on its own.
                // Drift is measured against the wall clock — the playback
                // position falls behind real time by exactly the sum of the
                // stalls — because the player's own totalBufferedDuration
                // lied outright in the field: hours of "buffer" on a stream
                // seconds old. More than a second and a half adrift is not
                // buffering — jump to live.
                val lag = System.currentTimeMillis() - p.currentPosition
                if (liveBase == 0L) {
                    // A fresh run starts wherever the server hands it — the
                    // Orqa's broadcast endpoint handed sessions born seconds
                    // behind its camera, and this guard only ever measured
                    // growth from that inherited backlog: a whole afternoon
                    // of goggle flying never fired it once. Jump to the
                    // newest the server has first; measure from there.
                    DebugLog.note("Video", "rtsp new run, catching up to the newest")
                    p.seekToDefaultPosition()
                    liveBase = -1L
                } else if (liveBase == -1L) {
                    liveBase = lag
                } else if (lag - liveBase > 1500) {
                    DebugLog.note("Video", "rtsp ${lag - liveBase}ms behind the camera, catching up")
                    p.seekToDefaultPosition()
                    liveBase = -1L
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
                        rejoin("no pictures arriving")
                    }
                    else -> lastRendered = rendered
                }
            }
            handler.postDelayed(this, 4000)
        }
    }

    // Always TCP, interleaved on the RTSP connection itself. UDP was tried
    // first for latency's sake and cost a smeared first second on every
    // address it had not yet failed on: a torn FU-A fragment decodes as a
    // garbage NAL type instead of raising an error — the field logs read
    // "packetization mode [25..31] not supported", a different number every
    // second, from a camera that only ever sends FU-A — and the player's own
    // UDP-to-TCP fallback needs silence, which torn packets are not. The
    // transport carries the same frames either way; the latency lives in the
    // buffers, not here.
    private fun mediaSource() = RtspMediaSource.Factory()
        .setForceUseRtpTcp(true)
        .createMediaSource(MediaItem.fromUri(url))

    /**
     * A live stream has no history to pick up; the recovery is a fresh
     * session.
     */
    private fun rejoin(why: String) {
        val p = player ?: return
        DebugLog.note("Video", "rtsp rejoin ($why), recoveries=$recoveries")
        if (!playedSinceChange && audioOn) {
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
                    // Start on 150ms — a pilot's compromise: half the
                    // standing latency, still enough to soak one jitter
                    // spike. After a rebuffer the link has proven it stalls,
                    // and restarting on the same thin cushion thrashed; that
                    // case alone waits for 300ms. The first number must be at
                    // least the last — the player enforces it at runtime, and
                    // 150 there crashed the field; it only says when loading
                    // may rest, so it does not set the latency floor.
                    .setBufferDurationsMs(300, 1000, 150, 300)
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
                // whatever run was being measured is over; the next READY
                // tick starts a fresh baseline
                if (playbackState != Player.STATE_READY) liveBase = 0L
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
                rejoin(error.cause?.message ?: error.errorCodeName)
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
