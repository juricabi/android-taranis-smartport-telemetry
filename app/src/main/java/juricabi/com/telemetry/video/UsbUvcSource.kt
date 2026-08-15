package juricabi.com.telemetry.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.view.Surface
import android.view.TextureView
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
    private val events: VideoSource.Events
) : VideoSource {

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
        UVCUtils.init(context.applicationContext)
    }

    private var helper: ICameraHelper? = null
    private var view: TextureView? = null
    private var surface: Surface? = null

    private val refit = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        fitToCamera()
    }

    private fun fitToCamera() {
        val size = helper?.previewSize ?: return
        view?.fitPicture(size.width, size.height)
    }

    private fun said(device: UsbDevice): String =
        "${device.productName ?: device.deviceName} " +
            "%04x:%04x".format(device.vendorId, device.productId)

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            DebugLog.note("Video", "uvc attach ${said(device)}")
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
            surface?.let { helper?.removeSurface(it) }
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

    override fun start(view: TextureView) {
        DebugLog.note("Video", "uvc start")
        this.view = view
        view.addOnLayoutChangeListener(refit)
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                attachSurfaceIfReady()
            }

            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                surface?.let { helper?.removeSurface(it) }
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {
                // not a signal of anything: the renderer's clear lands here
                // too. Noted once, for telling a drawn-black half from a
                // never-drawn one in the log.
                if (!sawSurfaceDraw) {
                    sawSurfaceDraw = true
                    DebugLog.note("Video", "uvc surface first drawn")
                }
            }
        }
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
        val texture = view?.surfaceTexture ?: return
        val s = Surface(texture)
        surface = s
        h.addSurface(s, false)
    }

    override fun stop() {
        DebugLog.note("Video", "uvc stop")
        view?.surfaceTextureListener = null
        view?.removeOnLayoutChangeListener(refit)
        view = null
        surface?.release()
        surface = null
        helper?.release()
        helper = null
    }
}
