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
 * Audio plays when the stream truly carries it — a drone camera's mic is
 * part of the feed. But cameras routinely advertise an audio track they
 * never feed, and a player told to wait for one holds the first frame
 * forever: one picture, then stone. So a stream that cannot start at all is
 * retried once without its audio before anything is counted a failure. A
 * live link also drops: stalls and errors are answered by rejoining the
 * stream, and only three failures in a row give up.
 */
@OptIn(UnstableApi::class)
class RtspSource(
    private val context: Context,
    private val url: String,
    private val onTrouble: (String) -> Unit
) : VideoSource {

    private var player: ExoPlayer? = null
    private var view: TextureView? = null
    private var recoveries = 0
    private var everPlayed = false
    private var audioDropped = false
    private val handler = Handler(Looper.getMainLooper())

    // refits the letterbox when showing or the fullscreen toggle resizes the pane
    private val refit = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val size = player?.videoSize ?: return@OnLayoutChangeListener
        view?.fitPicture(size.width, size.height)
    }

    private val stalled = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) rejoin("the stream stalled")
    }

    /** A live stream has no history to pick up; the recovery is a fresh session. */
    private fun rejoin(why: String) {
        val p = player ?: return
        if (!everPlayed && !audioDropped) {
            // Never started: the likeliest trap is an advertised audio track
            // nothing feeds. One free try without it, before failures count.
            audioDropped = true
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        } else if (++recoveries > 3) {
            onTrouble(why)
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
        player.setVideoTextureView(view)
        player.setMediaSource(
            RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(url))
        )
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                view.fitPicture(videoSize.width, videoSize.height)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                handler.removeCallbacks(stalled)
                when (playbackState) {
                    // running is what forgives earlier recoveries
                    Player.STATE_READY -> {
                        everPlayed = true
                        recoveries = 0
                    }
                    Player.STATE_BUFFERING -> handler.postDelayed(stalled, 8000)
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
