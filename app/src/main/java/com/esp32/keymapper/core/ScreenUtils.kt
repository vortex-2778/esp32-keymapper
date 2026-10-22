package com.esp32.keymapper.core

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

object ScreenUtils {

    /** Size of the whole display in pixels (includes the system bars). */
    fun realSize(context: Context): Point {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        return point
    }

    fun px(context: Context, nx: Float, total: Int): Float = nx * total
}
