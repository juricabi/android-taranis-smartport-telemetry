package juricabi.com.telemetry.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import com.serenegiant.utils.UVCUtils
import juricabi.com.telemetry.utils.DebugLog

/**
 * A USB (UVC) camera over OTG: an analog receiver dongle — ROTG02 and kin —
 * or goggles that present themselves as a webcam, which DJI, Walksnail and
 * Orqa all do.
 *
 * The helper does the courtship: it watches for a device, asks Android's USB
 * permission for it, opens it and runs the preview. This class only answers
 * its calls and hands over the view's surface once both sides exist, so the
 * camera may be plugged in before or after the video is switched on.
 */
class UsbUvcSource(
    context: Context,
    // the picture's extra quarter-turn, asked of the host at fit time — the
    // host owns the setting, the source only wears it
    private val turn: () -> Int,
    private val events: VideoSource.Events
) : VideoSource {

    private val appContext = context.applicationContext
    private var live = false
    private var sawSurfaceDraw = false

    /**
     * One real camera frame is the proof a picture exists. The renderer also
     * paints a surface the moment it is added — a clear, not a picture — and
     * a split trusting surface updates showed an empty half for it.
     */
    private val firstFrame = IFrameCallback { frame ->
        if (!live) {
            live = true
            DebugLog.note("Video", "uvc first frame, ${frame?.remaining() ?: 0} bytes")
            events.onLive()
        }
        // heard once; the per-frame copy is not worth carrying after that
        view?.post { helper?.setFrameCallback(null, 0) }
    }

    init {
        // The library digs its application context out by reflection unless
        // it is told one; telling it is its documented front door.
        UVCUtils.init(appContext)
    }

    private var helper: ICameraHelper? = null
    private var view: SurfaceView? = null
    // the holder's own Surface — held to hand the camera, never released
    // here, since the SurfaceView owns it and frees it with its window
    private var surface: Surface? = null

    private val refitOnLayout = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        fitToCamera()
    }

    private fun fitToCamera() {
        val size = helper?.previewSize ?: return
        val v = view ?: return
        val rotation = turn()
        applyRotation(rotation)
        // the library turns the preview, so the half is fitted to the sides
        // as they land — swapped for a quarter-turn
        val (w, h) = turnedSides(size.width, size.height, rotation)
        v.fitPicture(w, h)
    }

    /** The turn for a sideways camera, done by the library on its preview. */
    private fun applyRotation(rotation: Int) {
        val h = helper ?: return
        try {
            val config = h.previewConfig
            if (config.rotation != rotation) {
                h.previewConfig = config.setRotation(rotation)
            }
        } catch (e: Exception) {
            // an older library build without the setting; the picture plays
            // upright, which is every forward camera
        }
    }

    override fun refit() = fitToCamera()

    private fun said(device: UsbDevice): String =
        "${device.productName ?: device.deviceName} " +
            "%04x:%04x".format(device.vendorId, device.productId)

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            val interfaces = (0 until device.interfaceCount).joinToString {
                device.getInterface(it).run { "$interfaceClass/$interfaceSubclass" }
            }
            DebugLog.note("Video", "uvc attach ${said(device)} interfaces=[$interfaces]")
            // Only an identity that carries a video interface (class 14) is a
            // camera. A mode-switching camera announces transitional USB
            // identities with none, and courting those burned the field in
            // permission dialogs for devices that could never show a picture.
            if ((0 until device.interfaceCount).none {
                    device.getInterface(it).interfaceClass == 14
                }) {
                DebugLog.note("Video", "uvc ${said(device)} carries no video interface, ignored")
                return
            }
            // the first camera wins; one arriving while another is open waits
            // for the next start
            val h = helper ?: return
            if (!h.isCameraOpened) h.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            DebugLog.note("Video", "uvc device open ${said(device)} first=$isFirstOpen")
            helper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            val sizes = try {
                helper?.supportedSizeList?.joinToString { "${it.width}x${it.height}" }
            } catch (e: Exception) {
                "? (${e.message})"
            }
            DebugLog.note(
                "Video", "uvc camera open ${said(device)}, " +
                    "preview=${helper?.previewSize}, supports=$sizes"
            )
            helper?.setFrameCallback(firstFrame, UVCCamera.PIXEL_FORMAT_RAW)
            helper?.startPreview()
            fitToCamera()
            attachSurfaceIfReady()
        }

        override fun onCameraClose(device: UsbDevice) {
            DebugLog.note("Video", "uvc camera close ${said(device)}")
            // The surface must go with the camera: the view outlives a
            // replug, so a kept surface made attachSurfaceIfReady believe
            // the reopened camera was already served — frames flowed to the
            // callback, onLive took the card down, and the half stayed black.
            // Dropped, not released: the holder owns it and frees it itself.
            surface?.let { helper?.removeSurface(it) }
            surface = null
            // the half folds away until frames flow again — a replugged
            // camera re-earns it through the next first frame
            live = false
            events.onIdle()
        }

        override fun onDeviceClose(device: UsbDevice) {
            DebugLog.note("Video", "uvc device close ${said(device)}")
        }

        override fun onDetach(device: UsbDevice) {
            DebugLog.note("Video", "uvc detach ${said(device)}")
        }

        override fun onCancel(device: UsbDevice) {
            DebugLog.note("Video", "uvc permission refused for ${said(device)}")
            // A "no" to the USB dialog is remembered: Android answers every
            // later ask itself, instantly, showing nothing, until the camera
            // is replugged. The toast must say that remedy — a retry alone
            // just re-earns the same silent no.
            events.onTrouble(
                "Android refused USB access to ${said(device)} and will keep " +
                    "refusing quietly — unplug the camera, plug it back in, then allow it"
            )
        }

        override fun onError(device: UsbDevice, e: CameraException) {
            // The library's own answer is a toast reading "unknown error";
            // written down and folded away properly instead.
            DebugLog.note(
                "Video", "uvc error ${said(device)} code=${e.code}: ${e.message}"
            )
            events.onTrouble("the camera failed to open (${e.message ?: "code " + e.code})")
        }
    }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (!sawSurfaceDraw) {
                sawSurfaceDraw = true
                DebugLog.note("Video", "uvc surface up")
            }
            attachSurfaceIfReady()
        }

        override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surface?.let { helper?.removeSurface(it) }
            surface = null
        }
    }

    override fun start(view: SurfaceView) {
        DebugLog.note("Video", "uvc start")
        this.view = view
        view.addOnLayoutChangeListener(refitOnLayout)
        view.holder.addCallback(holderCallback)
        // addCallback does not replay surfaceCreated, so a surface already
        // standing when a camera is plugged into a running pane is taken now
        if (view.holder.surface?.isValid == true) attachSurfaceIfReady()
        val helper = CameraHelper()
        this.helper = helper
        helper.setStateCallback(stateCallback)
        // a camera already plugged in is announced through onAttach as the
        // callback registers; nothing more to do here either way
    }

    /**
     * The camera and the surface arrive in either order — the device on the
     * lead of someone plugging in, the surface when the view first lays out.
     * Whichever is second calls this.
     */
    private fun attachSurfaceIfReady() {
        val h = helper ?: return
        if (!h.isCameraOpened || surface != null) return
        val s = view?.holder?.surface?.takeIf { it.isValid } ?: return
        surface = s
        h.addSurface(s, false)
    }

    // ---- the receiver's own sound ----------------------------------------
    //
    // An analog VRX carries the video's audio on a UAC (USB audio) interface,
    // and Android's audio stack claims that interface as a USB *input* device
    // — separate from the UVC video interface this class opens. So the sound
    // is not the library's to give (it only encodes the phone's mic for a
    // recording); it is read straight off that input with AudioRecord and
    // poured to the speaker through AudioTrack. No receiver audio interface,
    // no sound button — the picture is never held hostage to it.

    /** The receiver's audio input, if the OS enumerated one. */
    private fun usbAudioInput(): AudioDeviceInfo? {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    override val hasAudio: Boolean get() = usbAudioInput() != null

    // its sound is a USB input the OS guards with the microphone permission
    override val needsRecordAudio get() = true

    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null

    override fun setAudio(on: Boolean) {
        if (on == audioRunning) return
        if (on) {
            val input = usbAudioInput() ?: return events.onAudioLost()
            // the screen asks first, but a race or a revoke could still land
            // here unpermitted — drop the sound cleanly rather than throw
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) return events.onAudioLost()
            audioRunning = true
            audioThread = Thread({ pumpAudio(input) }, "uvc-audio").also { it.start() }
        } else {
            audioRunning = false
            audioThread?.join(500)
            audioThread = null
        }
    }

    private fun pumpAudio(input: AudioDeviceInfo) {
        // 48 kHz mono 16-bit — what these receivers speak, and what a phone
        // decodes without complaint; a mismatch AudioRecord resamples itself.
        val rate = 48000
        val inMin = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outMin = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (inMin <= 0 || outMin <= 0) {
            audioRunning = false
            return events.onAudioLost()
        }
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inMin * 2)
            // MIC is the default input; point it at the receiver instead
            record.preferredDevice = input
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(outMin * 2)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                audioRunning = false
                return events.onAudioLost()
            }
            record.startRecording()
            track.play()
            DebugLog.note("Video", "uvc audio up from ${input.productName}")
            val buf = ByteArray(inMin)
            while (audioRunning) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    track.write(buf, 0, n)
                } else if (n < 0) {
                    // the input is gone — the receiver unplugged mid-listen.
                    // Only trouble if it was still wanted; a clean setAudio(off)
                    // ends the read the same way and must not toast. onAudioLost
                    // dims the button that would otherwise still claim sound.
                    DebugLog.note("Video", "uvc audio read ended ($n)")
                    if (audioRunning) events.onAudioLost()
                    break
                }
            }
        } catch (e: Exception) {
            DebugLog.note("Video", "uvc audio failed: ${e.message}")
            if (audioRunning) events.onAudioLost()
        } finally {
            audioRunning = false
            try { record?.stop() } catch (e: Exception) {}
            record?.release()
            try { track?.stop() } catch (e: Exception) {}
            track?.release()
        }
    }

    override fun stop() {
        DebugLog.note("Video", "uvc stop")
        setAudio(false)
        view?.holder?.removeCallback(holderCallback)
        view?.removeOnLayoutChangeListener(refitOnLayout)
        view = null
        // taken from the helper before its release — the preview must not
        // render into a surface about to go; the holder frees the surface
        surface?.let { helper?.removeSurface(it) }
        surface = null
        helper?.release()
        helper = null
    }
}
