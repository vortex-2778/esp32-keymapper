package com.esp32.keymapper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.esp32.keymapper.core.ActionType
import com.esp32.keymapper.core.ButtonConfig
import com.esp32.keymapper.core.ControllerHub
import com.esp32.keymapper.core.GesturePoint
import com.esp32.keymapper.core.ScreenUtils
import kotlin.math.hypot
import kotlin.math.min

/**
 * Miniature of the real screen. Buttons can be dragged around and gestures can
 * be drawn directly on it (the preview keeps the real screen aspect ratio, so
 * normalized coordinates map 1:1 to the overlay).
 */
class LayoutEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onSelectionChanged: ((Int) -> Unit)? = null
    var onLayoutFinished: (() -> Unit)? = null
    var onGestureDrawn: ((List<GesturePoint>) -> Unit)? = null

    private val content = RectF()
    private var selectedId = 1
    private var draggingId = -1

    private var drawMode = false
    private var drawing = false
    private val drawPath = Path()
    private val drawPoints = ArrayList<GesturePoint>()
    private var drawStart = 0L

    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101820.toInt()
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0x66FFFFFF
    }
    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0xFF00E676.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val gesturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF40C4FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
    }

    init {
        setBackgroundColor(0xFF1B1B1B.toInt())
    }

    fun setSelected(id: Int) {
        selectedId = id
        invalidate()
    }

    fun setDrawMode(enabled: Boolean) {
        drawMode = enabled
        drawing = false
        drawPoints.clear()
        drawPath.reset()
        invalidate()
    }

    fun isInDrawMode(): Boolean = drawMode

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeContent()
    }

    private fun computeContent() {
        val screen = ScreenUtils.realSize(context)
        if (screen.x <= 0 || screen.y <= 0) return
        val scale = min(width.toFloat() / screen.x, height.toFloat() / screen.y)
        val w = screen.x * scale
        val h = screen.y * scale
        content.set(
            (width - w) / 2f,
            (height - h) / 2f,
            (width + w) / 2f,
            (height + h) / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (content.isEmpty) computeContent()
        if (content.isEmpty) return

        canvas.drawRoundRect(content, dp(16f), dp(16f), screenPaint)
        canvas.drawRoundRect(content, dp(16f), dp(16f), framePaint)

        val buttons = ControllerHub.config.buttons.sortedBy { it.id }
        for (button in buttons) {
            if (button.id == selectedId) continue
            drawButton(canvas, button, false)
        }
        buttons.firstOrNull { it.id == selectedId }?.let { drawButton(canvas, it, true) }

        if (drawing) {
            canvas.drawPath(drawPath, gesturePaint)
        }

        if (drawMode) {
            canvas.drawText(
                "Draw the gesture with one finger – lift to save",
                content.centerX(),
                content.top + dp(32f),
                hintPaint
            )
        }
    }

    private fun drawButton(canvas: Canvas, button: ButtonConfig, selected: Boolean) {
        val cx = content.left + button.nx * content.width()
        val cy = content.top + button.ny * content.height()
        val radius = button.radius * min(content.width(), content.height())

        fillPaint.color = when {
            !button.enabled -> 0xFF555555.toInt()
            selected -> 0xFF00C853.toInt()
            else -> 0xFF3949AB.toInt()
        }
        fillPaint.alpha = if (button.enabled) 235 else 110
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        if (selected) canvas.drawCircle(cx, cy, radius + dp(4f), selectedRingPaint)

        textPaint.textSize = radius * 0.62f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(button.label, cx, baseline, textPaint)

        if (selected && button.action == ActionType.GESTURE && button.gesture.size >= 2) {
            val path = Path()
            val first = button.gesture.first()
            path.moveTo(content.left + first.nx * content.width(), content.top + first.ny * content.height())
            for (point in button.gesture.drop(1)) {
                path.lineTo(content.left + point.nx * content.width(), content.top + point.ny * content.height())
            }
            canvas.drawPath(path, gesturePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (drawMode) {
                    startDrawing(x, y, event.eventTime)
                } else {
                    val hit = hitTest(x, y)
                    if (hit != null) {
                        selectedId = hit.id
                        draggingId = hit.id
                        onSelectionChanged?.invoke(hit.id)
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (drawing) {
                    addDrawPoint(x, y, event.eventTime)
                    invalidate()
                } else if (draggingId >= 0 && content.width() > 0f) {
                    val button = buttonById(draggingId)
                    if (button != null) {
                        button.nx = ((x - content.left) / content.width()).coerceIn(0.02f, 0.98f)
                        button.ny = ((y - content.top) / content.height()).coerceIn(0.02f, 0.98f)
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawing) {
                    finishDrawing(x, y, event.eventTime)
                }
                if (draggingId >= 0) {
                    draggingId = -1
                    onLayoutFinished?.invoke()
                }
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): ButtonConfig? {
        val buttons = ControllerHub.config.buttons.sortedBy { it.id }
        for (button in buttons.asReversed()) {
            val cx = content.left + button.nx * content.width()
            val cy = content.top + button.ny * content.height()
            val radius = button.radius * min(content.width(), content.height())
            val distance = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
            if (distance <= radius * 1.2f) return button
        }
        return null
    }

    private fun buttonById(id: Int): ButtonConfig? =
        ControllerHub.config.buttons.firstOrNull { it.id == id }

    // ------------------------------------------------------------ drawing

    private fun startDrawing(x: Float, y: Float, time: Long) {
        drawing = true
        drawPoints.clear()
        drawPath.reset()
        drawStart = time
        addDrawPoint(x, y, time)
    }

    private fun addDrawPoint(x: Float, y: Float, time: Long) {
        if (content.width() <= 0f || content.height() <= 0f) return
        val t = time - drawStart
        val last = drawPoints.lastOrNull()
        if (last != null && t - last.t < 10L) return
        if (drawPoints.size > 600) return

        val nx = ((x - content.left) / content.width()).coerceIn(0f, 1f)
        val ny = ((y - content.top) / content.height()).coerceIn(0f, 1f)
        drawPoints.add(GesturePoint(nx, ny, t))

        if (drawPoints.size == 1) {
            drawPath.moveTo(x, y)
        } else {
            drawPath.lineTo(x, y)
        }
    }

    private fun finishDrawing(x: Float, y: Float, time: Long) {
        addDrawPoint(x, y, time)
        drawing = false
        drawMode = false

        val points = ArrayList(drawPoints)
        drawPoints.clear()
        invalidate()

        if (points.size >= 2) {
            onGestureDrawn?.invoke(points)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
