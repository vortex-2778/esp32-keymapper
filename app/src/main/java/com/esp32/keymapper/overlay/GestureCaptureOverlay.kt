package com.esp32.keymapper.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.esp32.keymapper.core.GesturePoint
import com.esp32.keymapper.core.ScreenUtils

/**
 * Full screen drawing surface used to record a custom gesture.
 *
 * The user picks the target button at the top, draws one continuous stroke and
 * taps Save. Points are stored normalized (0..1) so they can be replayed at
 * any resolution.
 */
class GestureCaptureOverlay(
    private val service: AccessibilityService,
    preselect: Int?,
    private val onResult: (Int?, List<GesturePoint>?) -> Unit
) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var captureView: CaptureView? = null
    private var hint: TextView? = null
    private var saveButton: Button? = null

    private var selectedId: Int = preselect ?: 1
    private var lastPoints: List<GesturePoint> = emptyList()
    private var finished = false

    private val chips = LinkedHashMap<Int, TextView>()

    fun show() {
        if (root != null) return
        val context = service
        val size = ScreenUtils.realSize(service)

        val container = FrameLayout(context)
        container.setBackgroundColor(DIM_COLOR)

        val capture = CaptureView(context)
        capture.onStrokeFinished = { points ->
            lastPoints = points
            hint?.text = "Stroke recorded (${points.size} points). Tap Save, or draw again."
            saveButton?.isEnabled = points.size >= 2
        }
        container.addView(
            capture,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val toolbar = buildToolbar(context)
        container.addView(
            toolbar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        val params = WindowManager.LayoutParams(
            size.x,
            size.y,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        try {
            windowManager.addView(container, params)
            root = container
            captureView = capture
        } catch (e: Exception) {
            root = null
            finish(null, null)
        }
    }

    fun dismiss() {
        val view = root ?: return
        root = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun finish(id: Int?, points: List<GesturePoint>?) {
        if (finished) return
        finished = true
        dismiss()
        onResult(id, points)
    }

    // -------------------------------------------------------------- toolbar

    private fun buildToolbar(context: AccessibilityService): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6101820.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnTouchListener { _, _ -> true }
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(context).apply {
                text = "Button"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(0, 0, dp(10), 0)
            }
        )

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (id in 1..15) {
            val chip = makeChip(context, "B$id")
            chip.setOnClickListener {
                selectedId = id
                refreshChips()
            }
            chips[id] = chip
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
            )
        }
        titleRow.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipRow)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(titleRow)

        val info = TextView(context).apply {
            text = "Draw the gesture with one finger, then tap Save."
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 12f
            setPadding(0, dp(6), 0, dp(6))
        }
        bar.addView(info)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancel = makeAction(context, "Cancel", false)
        cancel.setOnClickListener { finish(null, null) }
        val save = makeAction(context, "Save", true)
        save.isEnabled = false
        save.setOnClickListener {
            if (lastPoints.size >= 2) finish(selectedId, lastPoints)
        }
        actions.addView(cancel, actionParams(0))
        actions.addView(save, actionParams(dp(8)))
        bar.addView(actions)

        hint = info
        saveButton = save
        refreshChips()
        return bar
    }

    private fun makeChip(context: AccessibilityService, label: String): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(0x33FFFFFF, 8)
        }
    }

    private fun refreshChips() {
        for ((id, chip) in chips) {
            val selected = id == selectedId
            chip.background = rounded(if (selected) 0xFF00C853.toInt() else 0x33FFFFFF, 8)
            chip.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        }
    }

    private fun makeAction(context: AccessibilityService, label: String, primary: Boolean): Button {
        return Button(context).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (primary) 0xFF00E676.toInt() else 0x33FFFFFF
            )
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
    }

    private fun actionParams(marginStart: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.marginStart = marginStart }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt()
    }

    // --------------------------------------------------------- drawing view

    private inner class CaptureView(context: AccessibilityService) : View(context) {

        var onStrokeFinished: ((List<GesturePoint>) -> Unit)? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E676.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(5).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val path = Path()
        private val points = ArrayList<GesturePoint>()
        private var startTime = 0L
        private var down = false
        private var started = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    points.clear()
                    path.reset()
                    started = false
                    startTime = event.eventTime
                    down = true
                    addPoint(event.x, event.y, event.eventTime)
                    invalidate()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!down) return true
                    for (i in 0 until event.historySize) {
                        addPoint(
                            event.getHistoricalX(i),
                            event.getHistoricalY(i),
                            event.getHistoricalEventTime(i)
                        )
                    }
                    addPoint(event.x, event.y, event.eventTime)
                    invalidate()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!down) return true
                    addPoint(event.x, event.y, event.eventTime)
                    down = false
                    invalidate()
                    if (points.size >= 2) {
                        onStrokeFinished?.invoke(ArrayList(points))
                    }
                }
            }
            return true
        }

        private fun addPoint(x: Float, y: Float, time: Long) {
            if (width == 0 || height == 0) return
            val t = time - startTime
            val last = points.lastOrNull()
            if (last != null && t - last.t < 8L) return
            if (points.size > MAX_POINTS) return

            points.add(
                GesturePoint(
                    nx = (x / width).coerceIn(0f, 1f),
                    ny = (y / height).coerceIn(0f, 1f),
                    t = t
                )
            )
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (points.isEmpty()) return
            canvas.drawPath(path, paint)
            val first = points.first()
            canvas.drawCircle(
                first.nx * width,
                first.ny * height,
                dp(7).toFloat(),
                startPaint
            )
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private companion object {
        const val DIM_COLOR = 0x99000000.toInt()
        const val MAX_POINTS = 900
    }
}
