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
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

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

    override val hasAudio get() = true

    override fun setAudio(on: Boolean) {
        if (audioOn == on) return
        audioOn = on
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

    /** A live stream has no history to pick up; the recovery is a fresh session. */
    private fun rejoin(why: String) {
        val p = player ?: return
        if (!playedSinceChange && audioOn) {
            // It cannot start with the sound it was just asked for — the
            // likeliest trap is an advertised audio track nothing feeds.
            // The picture wins; the screen is told so its button agrees.
            audioOn = false
            applyAudio(p)
            events.onAudioLost()
        } else if (++recoveries > 3) {
            events.onTrouble(why)
            return
        }
        p.stop()
        p.prepare()
    }

    override fun start(view: TextureView) {
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
                view.fitPicture(videoSize.width, videoSize.height)
            }

            override fun onRenderedFirstFrame() {
                events.onLive()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
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
                rejoin(error.cause?.message ?: error.errorCodeName)
            }
        })
        player.playWhenReady = true
        player.prepare()
    }

    override fun stop() {
        handler.removeCallbacks(stalled)
        view?.removeOnLayoutChangeListener(refit)
        view = null
        player?.release()
        player = null
    }
}
