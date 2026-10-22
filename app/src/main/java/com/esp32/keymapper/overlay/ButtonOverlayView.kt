package com.esp32.keymapper.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.esp32.keymapper.core.AppConfig
import com.esp32.keymapper.core.ButtonConfig
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent, non touchable window that shows every enabled button on top of
 * the running game. Only [ButtonOverlayView.setActive] changes it while playing.
 */
class ButtonOverlayView(
    context: Context,
    private val configProvider: () -> AppConfig
) : View(context) {

    private val activeIds = HashSet<Int>(4)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0x66FFFFFF
    }
    private val activeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = 0xFF00E676.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setActive(id: Int, active: Boolean) {
        val changed = if (active) activeIds.add(id) else activeIds.remove(id)
        if (changed) invalidate()
    }

    fun clearActive() {
        if (activeIds.isNotEmpty()) {
            activeIds.clear()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val config = configProvider()
        val shortSide = min(width, height).toFloat()
        if (shortSide <= 0f) return

        for (button in config.buttons) {
            if (!button.enabled) continue
            drawButton(canvas, button, shortSide, activeIds.contains(button.id))
        }
    }

    private fun drawButton(canvas: Canvas, button: ButtonConfig, shortSide: Float, active: Boolean) {
        val cx = button.nx * width
        val cy = button.ny * height
        val radius = max(button.radius * shortSide, dp(12f))

        fillPaint.color = if (active) ACTIVE_FILL else IDLE_FILL
        fillPaint.alpha = if (active) 235 else 175
        canvas.drawCircle(cx, cy, radius, fillPaint)

        if (active) {
            canvas.drawCircle(cx, cy, radius + dp(3f), activeRingPaint)
        }
        canvas.drawCircle(cx, cy, radius, ringPaint)

        textPaint.textSize = radius * 0.62f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(button.label, cx, baseline, textPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val IDLE_FILL = 0xFF3949AB.toInt()
        const val ACTIVE_FILL = 0xFF00C853.toInt()
    }
}
