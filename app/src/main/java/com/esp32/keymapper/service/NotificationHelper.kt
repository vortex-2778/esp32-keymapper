package com.esp32.keymapper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.esp32.keymapper.R
import com.esp32.keymapper.core.BtState
import com.esp32.keymapper.ui.MainActivity

object NotificationHelper {

    const val CHANNEL_ID = "controller_connection"
    const val NOTIFICATION_ID = 42

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        channel.description = "Keeps the Bluetooth connection to the ESP32 controller alive."
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: BtState, deviceName: String?): Notification {
        val openIntent = Intent(context, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val captureIntent = Intent(context, ControllerService::class.java)
            .setAction(ControllerService.ACTION_CAPTURE)
        val capturePending = PendingIntent.getService(
            context, 1, captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, ControllerService::class.java)
            .setAction(ControllerService.ACTION_STOP)
        val stopPending = PendingIntent.getService(
            context, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = when (state) {
            BtState.CONNECTED -> "Connected to ${deviceName ?: "controller"}"
            BtState.CONNECTING -> "Connecting to ${deviceName ?: "controller"}…"
            BtState.ERROR -> "Bluetooth error – retrying"
            BtState.DISCONNECTED -> "Disconnected – tap to open"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Record gesture", capturePending)
            .addAction(0, "Stop", stopPending)
            .build()
    }
}
