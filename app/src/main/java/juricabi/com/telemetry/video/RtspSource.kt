package juricabi.com.telemetry.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
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
    private var view: SurfaceView? = null
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

    // the video's own size, from the selected track's format — the player's
    // videoSize could read 0×0, and then the surface was never fitted and
    // the picture ran on over the map
    private var videoWidth = 0
    private var videoHeight = 0

    // The turn the codec is configured for; a SurfaceView will not turn at
    // the view, and the player's decoder is walled off inside ExoPlayer, so
    // a custom renderer sets KEY_ROTATION on it — read here at configure.
    // The key binds at configure alone, so a change re-prepares the player,
    // the reconnect the sound switch already costs.
    @Volatile private var rotation = 0

    override fun refit() {
        val v = view ?: return
        if (videoWidth <= 0 || videoHeight <= 0) return
        val wanted = juricabi.com.telemetry.manager.PreferenceManager(v.context)
            .getVideoRotation()
        if (wanted != rotation) {
            rotation = wanted
            if (player != null) {
                // Turning rebuilds the player from scratch, not re-prepares
                // the running one: KEY_ROTATION binds only at the codec's
                // configure, and reaching it by re-preparing a kept player
                // ran slower every turn — measured climbing ~0.3s a turn
                // until the re-negotiation stalled and only the 12s watchdog
                // freed it. A fresh player each turn stays as quick as the
                // first, the way turning off and on already did. A cover
                // hides the gap behind black until the turned picture lands
                // and lifts it (onRenderedFirstFrame).
                events.onCovered(true)
                handler.removeCallbacks(starving)
                handler.removeCallbacks(stalled)
                player?.release()
                player = null
                val (w, h) = turnedSides(videoWidth, videoHeight, rotation)
                v.fitPicture(w, h)
                openPlayer()
                return
            }
        }
        val (w, h) = turnedSides(videoWidth, videoHeight, rotation)
        v.fitPicture(w, h)
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
                // No chasing the live edge here any more. Seeking a live RTSP
                // broadcast to "the newest" dropped the Orqa feed into a
                // buffering it never climbed out of — twelve seconds frozen
                // until the stalled watchdog rejoined it, then another
                // catch-up, a cycle that ate a whole goggle flight (the field
                // log was unmistakable). Orqa's own app does not chase it
                // either; it plays and wears the odd stutter. What is left is
                // only the frozen-picture check the comment above describes.
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

    override fun start(view: SurfaceView) {
        DebugLog.note("Video", "rtsp start $said")
        this.view = view
        view.addOnLayoutChangeListener(refitOnLayout)
        openPlayer()
    }

    /**
     * Builds a fresh player on the view and starts it — to begin, and again
     * for every turn. A rotate releases the running player and calls this
     * rather than re-preparing the old one, which slowed with each re-prepare
     * until it stalled outright (see refit).
     */
    private fun openPlayer() {
        val view = this.view ?: return
        // a fresh player is a fresh run: forget the last one's recovery tally,
        // or the watchdogs misjudge the new session
        recoveries = 0
        playedSinceChange = false
        frozenRejoins = 0
        lastRendered = 0
        val player = ExoPlayer.Builder(context, RotatingRenderersFactory(context) { rotation })
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // Start on 100ms — the number Orqa's own app starts its
                    // goggle stream on, read from its decompiled player: same
                    // ExoPlayer, same RTP-over-TCP, and this is where it lets
                    // the picture begin. After a rebuffer the link has proven
                    // it stalls, and restarting on that thin a cushion
                    // thrashed in the field — Orqa dodges it by recreating the
                    // player whole, we keep a thicker 300ms recovery instead.
                    // The first number must be at least the last, enforced at
                    // runtime; it only says when loading may rest, so it does
                    // not set the latency floor.
                    .setBufferDurationsMs(300, 1000, 100, 300)
                    // start on time held, not bytes held — for a live
                    // picture the clock is the constraint that matters
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
        this.player = player
        applyAudio(player)
        player.setVideoSurfaceView(view)
        player.setMediaSource(mediaSource())
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                DebugLog.note("Video", "rtsp size ${videoSize.width}x${videoSize.height}")
                refit()
            }

            override fun onRenderedFirstFrame() {
                DebugLog.note("Video", "rtsp first frame rendered")
                // the turned picture is here; lift the cover the rotate laid
                events.onCovered(false)
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
                        // the selected picture track's own size, for fitting
                        // the half — the effects path hides it from videoSize
                        if (group.isTrackSelected(i) && f.width > 0 &&
                            f.sampleMimeType?.startsWith("video/") == true
                        ) {
                            videoWidth = f.width
                            videoHeight = f.height
                            view?.let { it.post { refit() } }
                        }
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

/**
 * A player whose one video renderer sets KEY_ROTATION on its codec, from
 * [rotation] read at each configure — the way to turn a SurfaceView's
 * picture, since the view cannot and the player's decoder is otherwise
 * sealed. The turn changes only across a re-prepare, which rebuilds the
 * codec and reads the value afresh.
 */
@OptIn(UnstableApi::class)
private class RotatingRenderersFactory(
    context: Context,
    private val rotation: () -> Int,
) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            object : MediaCodecVideoRenderer(
                context, mediaCodecSelector, allowedVideoJoiningTimeMs,
                eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            ) {
                override fun getMediaCodecConfiguration(
                    codecInfo: MediaCodecInfo,
                    format: androidx.media3.common.Format,
                    crypto: android.media.MediaCrypto?,
                    codecOperatingRate: Float,
                ): MediaCodecAdapter.Configuration {
                    val config = super.getMediaCodecConfiguration(
                        codecInfo, format, crypto, codecOperatingRate
                    )
                    val turn = rotation()
                    if (turn != 0) {
                        config.mediaFormat.setInteger(android.media.MediaFormat.KEY_ROTATION, turn)
                    }
                    return config
                }
            }
        )
    }
}
