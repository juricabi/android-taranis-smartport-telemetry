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
 * forgives, and only three failures in a row give up. The RTP rides TCP or
 * UDP by the flyer's choice ([useUdp]) — see mediaSource for what each costs.
 */
@OptIn(UnstableApi::class)
class RtspSource(
    private val context: Context,
    private val url: String,
    private val useUdp: Boolean,
    // The network that reaches the camera, when one specifically does — a
    // goggle's Wi-Fi loses the default route to preferred mobile data, and
    // every connect then leaves over cellular and hangs (see NetworkBinder).
    // Asked afresh on every player build: a recovery may be the moment the
    // right network finally exists, or the one the last answer named is dead.
    private val socketFactory: () -> javax.net.SocketFactory?,
    private val events: VideoSource.Events
) : VideoSource {

    private var player: ExoPlayer? = null
    private var view: SurfaceView? = null
    private var recoveries = 0
    private var audioOn = false
    // Two played-flags with two jobs. playedSinceChange: has anything played
    // since the last DELIBERATE change (start, a turn, the sound switch) — the
    // phantom-audio drop keys on it, so a recovery rebuild must not reset it or
    // a plain outage with sound on reads as "the audio track froze the start"
    // and mutes the stream with the wrong toast. playedThisSession: has THIS
    // player played — the stall watchdog keys on it, so every fresh session,
    // recovery rebuilds included, gets the long connect grace.
    private var playedSinceChange = false
    private var playedThisSession = false
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
        if (player == null) return // before start: remembered, applied in openPlayer
        // Rebuild the player, don't re-prepare the kept one. Re-preparing it to
        // change the audio-track selection leaked exactly as a turn did — each
        // toggle slower than the last (measured ~2s climbing past 6s). A fresh
        // player takes up the new audioOn through applyAudio in openPlayer,
        // still set before prepare so a phantom audio track cannot freeze the
        // first frame — the reason the switch re-prepares at all. A cover hides
        // the gap; onRenderedFirstFrame lifts it.
        events.onCovered(true)
        handler.removeCallbacks(starving)
        handler.removeCallbacks(stalled)
        // nulled before the release, as in rejoin — a hung release's late
        // error must find no player, not re-enter rejoin against this one
        val dying = player
        player = null
        dying?.release()
        openPlayer()
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
                // nulled before the release, as in rejoin
                val dying = player
                player = null
                dying?.release()
                val (w, h) = turnedSides(videoWidth, videoHeight, rotation)
                v.fitPicture(w, h)
                openPlayer()
                return
            }
        }
        val (w, h) = turnedSides(videoWidth, videoHeight, rotation)
        v.fitPicture(w, h)
    }

    // Still buffering when this fires is a dead session, not a hiccup —
    // ExoPlayer climbs out of a real rebuffer on its own, back to READY, which
    // cancels this. The wait before it fires is set where it is posted: short
    // once the stream has played, a longer grace for a first connect that has
    // not shown a frame yet.
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
                            events.onTrouble("$said stopped sending pictures")
                            return
                        }
                        // the rebuild reopen schedules re-posts this watchdog;
                        // returning here so the fall-through below does not post
                        // a second, a duplicate 4s poll leaked per starve-recover
                        rejoin("no pictures arriving")
                        return
                    }
                    else -> lastRendered = rendered
                }
            }
            handler.postDelayed(this, 4000)
        }
    }

    // TCP or UDP for the RTP, the flyer's choice (the Video settings switch),
    // TCP the default. TCP interleaves the video on the RTSP connection itself:
    // reliable — never a torn frame — but the video shares the one pipe with
    // the control channel, and a server that mishandles a mid-stream request
    // stalls on its own keep-alive (the Orqa goggle drops its video ~0.2s after
    // every OPTIONS media3 sends; cracked from the protocol log). UDP keeps the
    // video off that pipe, so the keep-alive never touches it — the goggle then
    // plays smooth as its own app — but a lost packet tears an FU-A fragment
    // into a garbage NAL that decodes as a smear ("packetization mode [25..31]
    // not supported" in the field logs). A clean direct link wants UDP; a lossy
    // one wants TCP. The frames are the same either way; the latency lives in
    // the buffers, not here.
    private fun mediaSource() = RtspMediaSource.Factory()
        .setForceUseRtpTcp(!useUdp)
        // The pinned road to the camera, when there is one. It carries the
        // RTSP connection and the interleaved TCP video; UDP mode's RTP
        // sockets are the player's own and stay on the default route, so on
        // preferred mobile data the UDP switch may still need Wi-Fi preferred.
        .apply { socketFactory()?.let { setSocketFactory(it) } }
        .createMediaSource(MediaItem.fromUri(url))

    /**
     * A live stream has no history to pick up; the recovery is a fresh
     * session.
     */
    private fun rejoin(why: String) {
        if (player == null) return
        DebugLog.note("Video", "rtsp rejoin ($why), recoveries=$recoveries")
        if (!playedSinceChange && audioOn) {
            // It cannot start with the sound it was just asked for — the
            // likeliest trap is an advertised audio track nothing feeds. The
            // picture wins; the fresh player below starts without it (openPlayer
            // applies the selection), and the screen is told so its button agrees.
            audioOn = false
            DebugLog.note("Video", "rtsp dropped audio to start at all")
            events.onAudioLost()
        } else if (++recoveries > 3) {
            // the whole address as tried, so a typo in the path shows in the
            // toast — an error's own words name the host at best
            events.onTrouble("$said — $why")
            return
        }
        // Recover the way Orqa's own app does: recreate the player whole, not
        // re-prepare the kept one — the last place a re-prepare reused a player.
        // Released now, rebuilt after a short wait, so a blink-length outage
        // costs one rebuild rather than the four-in-60ms storm that burned the
        // whole recovery budget and flashed the trouble card on a blip. The
        // recovery tally is kept (resetRecoveries=false) so the give-up budget
        // still counts down across the paced attempts.
        handler.removeCallbacks(starving)
        handler.removeCallbacks(stalled)
        handler.removeCallbacks(reopen)
        // Cleared before the release, not after: a hung socket makes release
        // time out and fire a late error at this same player's listener, which
        // would re-enter rejoin and count the recovery twice (the field log's
        // paired "stream stalled" then "Player release timed out"). With the
        // field already null, that re-entrant rejoin finds no player and returns.
        val dying = player
        player = null
        dying?.release()
        handler.postDelayed(reopen, 1000L)
    }

    // the paced recovery rebuild rejoin schedules; keeps the recovery tally
    private val reopen = Runnable { openPlayer(resetRecoveries = false) }

    override fun start(view: SurfaceView) {
        DebugLog.note("Video", "rtsp start $said")
        this.view = view
        view.addOnLayoutChangeListener(refitOnLayout)
        openPlayer()
    }

    /**
     * Builds a fresh player on the view and starts it — to begin, for every
     * turn, for the sound switch, and for a recovery. Everything that once
     * re-prepared the kept player now rebuilds instead: re-preparing slowed
     * with each call until it stalled (see refit), and rebuilding is how Orqa's
     * own app recovers too.
     */
    private fun openPlayer(resetRecoveries: Boolean = true) {
        val view = this.view ?: return
        // this build supersedes any recovery rebuild the last drop had queued
        handler.removeCallbacks(reopen)
        // a fresh player is a fresh run: forget the last one's frame counts —
        // lastRendered always, the decoder counter being per-player. BOTH
        // give-up tallies are kept for a recovery rebuild so their budgets
        // count down across attempts (each rebuild renders anew, so a tally
        // reset by its own recovery could never reach its ceiling — a camera
        // sending one frame then freezing looped forever on exactly that); a
        // deliberate open — start, a turn, the sound switch — clears them.
        if (resetRecoveries) {
            recoveries = 0
            frozenRejoins = 0
            playedSinceChange = false
        }
        playedThisSession = false
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
            // A dead RTSP socket makes ExoPlayer wait out its release timeout
            // on this (the app) thread — half a second of frozen UI on every
            // rejoin, rotate or sound rebuild over a hung session (the field
            // log's "Player release timed out"). A healthy release is a few
            // ms, so capping it only shortens the stuck one.
            .setReleaseTimeoutMs(200)
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
                        playedThisSession = true
                        recoveries = 0
                    }
                    // A stall mid-session is a lapsed stream — the goggle's,
                    // say, whose server never resumes — so rejoin quickly
                    // (~2.5s) instead of leaving a long freeze. A session still
                    // connecting gets twelve: on UDP the player's own
                    // UDP-to-TCP fallback runs at eight seconds, and a rejoin
                    // before that starts a fresh UDP session and keeps a
                    // TCP-only camera from ever being reached.
                    Player.STATE_BUFFERING ->
                        handler.postDelayed(stalled, if (playedThisSession) 2500 else 12000)
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
        handler.removeCallbacks(reopen)
        view?.removeOnLayoutChangeListener(refitOnLayout)
        view = null
        // nulled before the release, as in rejoin
        val dying = player
        player = null
        dying?.release()
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
