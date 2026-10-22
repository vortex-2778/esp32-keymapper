package com.esp32.keymapper.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.esp32.keymapper.bt.BtSppClient
import com.esp32.keymapper.core.BtState
import com.esp32.keymapper.core.ControllerHub
import com.esp32.keymapper.overlay.OverlayController

/**
 * Foreground service that owns the Bluetooth connection and the floating
 * overlay, so both keep working while the app is in the background.
 */
class ControllerService : Service(), BtSppClient.Callback {

    companion object {
        const val ACTION_START = "com.esp32.keymapper.action.START"
        const val ACTION_CONNECT = "com.esp32.keymapper.action.CONNECT"
        const val ACTION_DISCONNECT = "com.esp32.keymapper.action.DISCONNECT"
        const val ACTION_STOP = "com.esp32.keymapper.action.STOP"
        const val ACTION_CAPTURE = "com.esp32.keymapper.action.CAPTURE"
        const val ACTION_SET_OVERLAY = "com.esp32.keymapper.action.SET_OVERLAY"
        const val EXTRA_MAC = "extra_mac"
        const val EXTRA_ENABLED = "extra_enabled"

        private const val RECONNECT_DELAY_MS = 4_000L

        @Volatile
        var instance: ControllerService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            val intent = Intent(context, ControllerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun connect(context: Context, mac: String) {
            val intent = Intent(context, ControllerService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_MAC, mac)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, ControllerService::class.java).setAction(ACTION_DISCONNECT)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setOverlay(context: Context, enabled: Boolean) {
            val intent = Intent(context, ControllerService::class.java)
                .setAction(ACTION_SET_OVERLAY)
                .putExtra(EXTRA_ENABLED, enabled)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ControllerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private lateinit var bt: BtSppClient
    private var overlayController: OverlayController? = null
    private lateinit var reconnectHandler: Handler
    private var desiredMac: String? = null
    private var reconnectScheduled = false

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        val mac = desiredMac
        if (mac != null && instance != null) {
            ControllerHub.log("Reconnecting…")
            bt.connect(mac)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ControllerHub.init(applicationContext)
        NotificationHelper.ensureChannel(this)
        reconnectHandler = Handler(Looper.getMainLooper())
        bt = BtSppClient(this, this)

        val overlay = OverlayController(this)
        overlayController = overlay
        ControllerHub.overlay = overlay
        ControllerHub.log("Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CONNECT -> {
                val mac = intent.getStringExtra(EXTRA_MAC)
                if (!mac.isNullOrBlank()) connectTo(mac)
            }

            ACTION_CAPTURE -> {
                if (!ControllerHub.startCapture(null)) {
                    ControllerHub.log("Enable the accessibility service to record gestures")
                }
            }

            ACTION_DISCONNECT -> {
                desiredMac = null
                cancelReconnect()
                ControllerHub.releaseAll()
                bt.disconnect()
                ControllerHub.log("Disconnected by user")
            }

            ACTION_SET_OVERLAY -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                ControllerHub.updateConfig { it.overlayEnabled = enabled }
                if (enabled) showOverlay() else overlayController?.hide()
            }

            else -> {
                if (ControllerHub.config.overlayEnabled) showOverlay()
                val mac = desiredMac ?: ControllerHub.config.autoConnectMac
                if (mac != null &&
                    ControllerHub.btState != BtState.CONNECTED &&
                    ControllerHub.btState != BtState.CONNECTING
                ) {
                    connectTo(mac)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        reconnectHandler.removeCallbacksAndMessages(null)
        ControllerHub.releaseAll()
        bt.disconnect()
        overlayController?.hide()
        ControllerHub.overlay = null
        instance = null
        ControllerHub.log("Service stopped")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController?.onDisplayChanged()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------- bluetooth

    private fun connectTo(mac: String) {
        desiredMac = mac
        ControllerHub.btDeviceName = null
        ControllerHub.config.autoConnectMac = mac
        ControllerHub.saveConfig()

        if (!bt.hasPermission()) {
            ControllerHub.log("Bluetooth permission not granted")
            ControllerHub.btState = BtState.ERROR
            ControllerHub.notifyState()
            return
        }
        bt.connect(mac)
    }

    override fun onStateChanged(state: BtState, deviceName: String?) {
        ControllerHub.btState = state
        if (deviceName != null) ControllerHub.btDeviceName = deviceName

        when (state) {
            BtState.CONNECTED -> {
                cancelReconnect()
                ControllerHub.log("Connected to ${deviceName ?: "controller"}")
            }

            BtState.ERROR -> {
                ControllerHub.log("Bluetooth error")
            }

            BtState.DISCONNECTED -> {
                ControllerHub.releaseAll()
                ControllerHub.log("Disconnected")
                scheduleReconnect()
            }

            BtState.CONNECTING -> {
                ControllerHub.log("Connecting…")
            }
        }

        updateNotification()
        ControllerHub.notifyState()
    }

    override fun onLine(line: String) {
        ControllerHub.onSerialLine(line)
    }

    private fun scheduleReconnect() {
        if (desiredMac == null || instance == null) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun cancelReconnect() {
        reconnectScheduled = false
        reconnectHandler.removeCallbacks(reconnectRunnable)
    }

    // -------------------------------------------------------------- overlay

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            ControllerHub.log("Overlay permission missing")
            return
        }
        overlayController?.show()
    }

    // ---------------------------------------------------------- notification

    private fun startForegroundSafely() {
        val notification = NotificationHelper.build(
            this, ControllerHub.btState, ControllerHub.btDeviceName
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            ControllerHub.log("Foreground service start failed: ${e.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.build(this, ControllerHub.btState, ControllerHub.btDeviceName)
            )
        } catch (_: Exception) {
        }
    }
}
