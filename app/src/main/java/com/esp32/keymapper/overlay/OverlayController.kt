package com.esp32.keymapper.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.esp32.keymapper.core.ControllerHub
import com.esp32.keymapper.core.ScreenUtils

/**
 * Adds / removes / refreshes the floating button overlay.
 */
class OverlayController(private val context: Context) : ControllerHub.OverlayHandle {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ButtonOverlayView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) {
            ControllerHub.log("Overlay permission missing")
            return
        }

        val overlay = ButtonOverlayView(context) { ControllerHub.config }
        overlay.alpha = ControllerHub.config.opacity
        val layoutParams = buildParams()
        try {
            windowManager.addView(overlay, layoutParams)
            view = overlay
            params = layoutParams
        } catch (e: Exception) {
            ControllerHub.log("Overlay failed: ${e.javaClass.simpleName}")
        }
    }

    fun hide() {
        val overlay = view ?: return
        view = null
        params = null
        try {
            windowManager.removeView(overlay)
        } catch (_: Exception) {
        }
    }

    /** Re-creates the window after a rotation or resolution change. */
    fun onDisplayChanged() {
        if (view == null) return
        hide()
        show()
    }

    override fun setActive(id: Int, active: Boolean) {
        view?.setActive(id, active)
    }

    override fun clearActive() {
        view?.clearActive()
    }

    override fun refresh() {
        val overlay = view ?: return
        overlay.alpha = ControllerHub.config.opacity
        overlay.invalidate()
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val size = ScreenUtils.realSize(context)
        val layoutParams = WindowManager.LayoutParams(
            size.x,
            size.y,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 0
        layoutParams.y = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        return layoutParams
    }
}
