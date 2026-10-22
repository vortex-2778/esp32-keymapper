package com.esp32.keymapper.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import com.esp32.keymapper.core.ActionType
import com.esp32.keymapper.core.ButtonConfig
import com.esp32.keymapper.core.ControllerHub
import com.esp32.keymapper.core.GesturePoint
import com.esp32.keymapper.core.ScreenUtils

/**
 * Turns controller events into injected touch input.
 *
 * Android can only inject one gesture at a time, and every new gesture cancels
 * the previous one. Therefore all currently held buttons are packed into a
 * single [GestureDescription] with one stroke (finger) per button. When the set
 * of held buttons changes the combined gesture is re-dispatched, which keeps
 * every button independent while allowing several of them to be down at once.
 */
class GestureEngine(private val service: AccessibilityService) : ControllerHub.GestureSink {

    private data class Stroke(val path: Path, val duration: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val holds = LinkedHashMap<Int, PointF>()
    private val transients = ArrayList<Stroke>()

    private var lastPoint: PointF? = null
    private var holdGestureActive = false

    private val maxStrokes = GestureDescription.getMaxStrokeCount()
    private val maxDuration = GestureDescription.getMaxGestureDuration()

    private val extendRunnable = object : Runnable {
        override fun run() {
            if (holds.isNotEmpty()) rebuild()
        }
    }

    override fun press(config: ButtonConfig) {
        val size = ScreenUtils.realSize(service)
        val x = config.nx * size.x
        val y = config.ny * size.y
        lastPoint = PointF(x, y)

        when (config.action) {
            ActionType.HOLD -> {
                if (holds.size >= maxStrokes) return
                holds[config.id] = PointF(x, y)
            }

            ActionType.TAP -> {
                transients.add(Stroke(tapPath(x, y), TAP_DURATION_MS))
            }

            ActionType.GESTURE -> {
                val stroke = gestureStroke(config.gesture, size.x.toFloat(), size.y.toFloat())
                transients.add(stroke ?: Stroke(tapPath(x, y), TAP_DURATION_MS))
            }
        }
        rebuild()
    }

    override fun release(id: Int) {
        if (holds.remove(id) != null) rebuild()
    }

    override fun releaseAll() {
        holds.clear()
        transients.clear()
        cancelHoldGesture()
    }

    // --------------------------------------------------------------- build

    private fun rebuild() {
        handler.removeCallbacks(extendRunnable)

        val strokes = ArrayList<Stroke>()
        if (transients.isNotEmpty()) {
            strokes.addAll(transients)
            transients.clear()
        }
        for (point in holds.values) {
            strokes.add(Stroke(pointPath(point.x, point.y), maxDuration))
        }

        if (strokes.isEmpty()) {
            cancelHoldGesture()
            return
        }

        val count = minOf(strokes.size, maxStrokes)
        val builder = GestureDescription.Builder()
        for (index in 0 until count) {
            val stroke = strokes[index]
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    stroke.path,
                    0L,
                    stroke.duration,
                    index < count - 1
                )
            )
        }

        val dispatched = try {
            service.dispatchGesture(builder.build(), null, handler)
        } catch (e: Exception) {
            false
        }
        holdGestureActive = dispatched && holds.isNotEmpty()

        if (dispatched && holds.isNotEmpty()) {
            handler.postDelayed(extendRunnable, maxDuration - EXTEND_MARGIN_MS)
        }
    }

    /**
     * Lifts all fingers. The accessibility API has no "cancel" call, so a 1 ms
     * stroke at the last touched point is dispatched: it cancels the running
     * gesture and ends with the finger lifted. It is the only way to release a
     * long press without waiting for its duration to expire.
     */
    private fun cancelHoldGesture() {
        handler.removeCallbacks(extendRunnable)
        if (!holdGestureActive) return
        holdGestureActive = false

        val point = lastPoint ?: return
        val path = Path()
        path.moveTo(point.x, point.y)
        path.lineTo(point.x + 0.5f, point.y + 0.5f)
        try {
            service.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 1L))
                    .build(),
                null,
                null
            )
        } catch (_: Exception) {
        }
    }

    private fun pointPath(x: Float, y: Float): Path {
        val path = Path()
        path.moveTo(x, y)
        // A zero length path is rejected by some Android versions, so move by a
        // fraction of a pixel which is invisible on screen.
        path.lineTo(x + 0.5f, y + 0.5f)
        return path
    }

    private fun tapPath(x: Float, y: Float): Path = pointPath(x, y)

    private fun gestureStroke(points: List<GesturePoint>, width: Float, height: Float): Stroke? {
        if (points.size < 2) return null
        val path = Path()
        path.moveTo(points[0].nx * width, points[0].ny * height)
        for (index in 1 until points.size) {
            path.lineTo(points[index].nx * width, points[index].ny * height)
        }
        val recorded = points.last().t - points.first().t
        return Stroke(path, recorded.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS))
    }

    private companion object {
        const val TAP_DURATION_MS = 60L
        const val MIN_GESTURE_MS = 40L
        const val MAX_GESTURE_MS = 2_000L
        const val EXTEND_MARGIN_MS = 5_000L
    }
}
