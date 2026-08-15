package juricabi.com.telemetry.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.view.Surface
import android.view.TextureView
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.utils.UVCUtils

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

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            // the first camera wins; one arriving while another is open waits
            // for the next start
            val h = helper ?: return
            if (!h.isCameraOpened) h.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            helper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            helper?.startPreview()
            fitToCamera()
            attachSurfaceIfReady()
        }

        override fun onCameraClose(device: UsbDevice) {
            surface?.let { helper?.removeSurface(it) }
        }

        override fun onDeviceClose(device: UsbDevice) {}
        override fun onDetach(device: UsbDevice) {}

        override fun onCancel(device: UsbDevice) {
            events.onTrouble("USB permission was refused")
        }
    }

    override fun start(view: TextureView) {
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
                // fires per delivered frame; the first one is the picture
                if (!live) {
                    live = true
                    events.onLive()
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
        view?.surfaceTextureListener = null
        view?.removeOnLayoutChangeListener(refit)
        view = null
        surface?.release()
        surface = null
        helper?.release()
        helper = null
    }
}
