package juricabi.com.telemetry.video

import android.content.Context
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
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
 */
@OptIn(UnstableApi::class)
class RtspSource(
    private val context: Context,
    private val url: String,
    private val onTrouble: (String) -> Unit
) : VideoSource {

    private var player: ExoPlayer? = null
    private var view: TextureView? = null

    // refits the letterbox when the fullscreen toggle resizes the pane
    private val refit = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val size = player?.videoSize ?: return@OnLayoutChangeListener
        view?.fitPicture(size.width, size.height)
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

            override fun onPlayerError(error: PlaybackException) {
                onTrouble(error.cause?.message ?: error.errorCodeName)
            }
        })
        player.playWhenReady = true
        player.prepare()
    }

    override fun stop() {
        view?.removeOnLayoutChangeListener(refit)
        view = null
        player?.release()
        player = null
    }
}
