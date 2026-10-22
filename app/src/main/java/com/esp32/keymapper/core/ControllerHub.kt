package com.esp32.keymapper.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class BtState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Single in-process meeting point between the Bluetooth foreground service,
 * the accessibility service, the floating overlay and the UI activities.
 *
 * Everything here runs on the main thread.
 */
object ControllerHub {

    interface Listener {
        fun onStateChanged()
    }

    /** Implemented by the floating overlay. */
    interface OverlayHandle {
        fun setActive(id: Int, active: Boolean)
        fun clearActive()
        fun refresh()
    }

    /** Implemented by the accessibility service (gesture injection). */
    interface GestureSink {
        fun press(config: ButtonConfig)
        fun release(id: Int)
        fun releaseAll()
    }

    /**
     * Implemented by the accessibility service: shows a full screen drawing
     * surface. [preselect] is the button that should be selected first (or null
     * to let the user pick). The result callback must be called on the main thread.
     */
    var captureSink: ((preselect: Int?, result: (Int, List<GesturePoint>) -> Unit) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val pressedIds = HashSet<Int>()

    lateinit var repository: ConfigRepository
        private set

    var config: AppConfig = AppConfig()
        private set

    var overlay: OverlayHandle? = null
    var gestures: GestureSink? = null

    var btState: BtState = BtState.DISCONNECTED
    var btDeviceName: String? = null

    private val eventLog = ArrayDeque<String>()
    private var initialized = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        repository = ConfigRepository(context.applicationContext)
        config = repository.load()
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifyState() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchState()
        } else {
            main.post { dispatchState() }
        }
    }

    private fun dispatchState() {
        for (listener in listeners) listener.onStateChanged()
    }

    fun saveConfig() {
        repository.save(config)
        overlay?.refresh()
        notifyState()
    }

    /** Applies [block] to the config, persists it and refreshes the overlay. */
    fun updateConfig(block: (AppConfig) -> Unit) {
        block(config)
        saveConfig()
    }

    fun setOpacity(opacity: Float) {
        config.opacity = opacity.coerceIn(0.05f, 1f)
        saveConfig()
    }

    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        eventLog.addLast(line)
        while (eventLog.size > 8) eventLog.removeFirst()
        notifyState()
    }

    fun logText(): String = if (eventLog.isEmpty()) "No events yet." else eventLog.joinToString("\n")

    // ---------------------------------------------------------------- input

    private val lineRegex = Regex("""BTN_(PRESS|RELEASE)[_:\s-]*(\d{1,2})""")

    /** Called for every complete line received from the ESP32. */
    fun onSerialLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        val match = lineRegex.find(line) ?: return
        val id = match.groupValues[2].toIntOrNull() ?: return
        if (id !in 1..BUTTON_COUNT) return
        if (match.groupValues[1] == "PRESS") handlePress(id) else handleRelease(id)
    }

    private fun handlePress(id: Int) {
        if (!pressedIds.add(id)) return
        val button = config.buttons.firstOrNull { it.id == id } ?: return
        if (!button.enabled) return

        overlay?.setActive(id, true)
        val sink = gestures
        if (sink == null) {
            log("B$id pressed - accessibility service is off")
            return
        }
        sink.press(button)
        log("B$id down (${button.action.name.lowercase()})")
    }

    private fun handleRelease(id: Int) {
        val wasPressed = pressedIds.remove(id)
        overlay?.setActive(id, false)
        gestures?.release(id)
        if (wasPressed) log("B$id up")
    }

    fun releaseAll() {
        pressedIds.clear()
        gestures?.releaseAll()
        overlay?.clearActive()
    }

    // -------------------------------------------------------------- capture

    /** Returns false when the accessibility service is not available. */
    fun startCapture(preselect: Int? = null, onDone: ((Int) -> Unit)? = null): Boolean {
        val sink = captureSink ?: return false
        sink(preselect) { id, points ->
            val button = config.buttons.firstOrNull { it.id == id }
            if (button != null && points.size >= 2) {
                button.gesture = points
                button.action = ActionType.GESTURE
                saveConfig()
                log("Gesture recorded for B$id (${points.size} points)")
                onDone?.invoke(id)
            }
        }
        return true
    }
}
