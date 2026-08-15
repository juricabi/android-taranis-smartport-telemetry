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
 * forgives, and only three failures in a row drop back to the map.
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
    private val refit = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val size = player?.videoSize ?: return@OnLayoutChangeListener
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
                            events.onTrouble("the stream stopped sending pictures")
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

    /** A live stream has no history to pick up; the recovery is a fresh session. */
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
            events.onTrouble(why)
            return
        }
        p.stop()
        p.prepare()
    }

    override fun start(view: TextureView) {
        DebugLog.note("Video", "rtsp start $said")
        this.view = view
        view.addOnLayoutChangeListener(refit)
        val player = ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(300, 1000, 300, 300)
                    .build()
            )
            .build()
        this.player = player
        applyAudio(player)
        player.setVideoTextureView(view)
        player.setMediaSource(
            RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(url))
        )
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
        view?.removeOnLayoutChangeListener(refit)
        view = null
        player?.release()
        player = null
    }
}
