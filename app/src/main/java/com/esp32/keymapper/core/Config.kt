package com.esp32.keymapper.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** How a physical ESP32 button is turned into touch input. */
enum class ActionType {
    /** Touch down while the physical button is held, lift on release. */
    HOLD,

    /** One quick tap when the physical button goes down. */
    TAP,

    /** Replay a gesture that the user drew on screen. */
    GESTURE
}

/**
 * One sample of a recorded gesture.
 * Coordinates are normalized (0..1) so they survive rotation / different screens,
 * [t] is milliseconds since the gesture started.
 */
data class GesturePoint(val nx: Float, val ny: Float, val t: Long)

/**
 * Configuration of one controller button (1..15).
 * [nx]/[ny] are normalized screen coordinates of the overlay button AND of the
 * touch point that is injected when the button is pressed.
 */
data class ButtonConfig(
    val id: Int,
    var label: String = "B$id",
    var enabled: Boolean = true,
    var nx: Float = 0.5f,
    var ny: Float = 0.5f,
    var radius: Float = 0.075f,
    var action: ActionType = ActionType.HOLD,
    var gesture: List<GesturePoint> = emptyList()
)

data class AppConfig(
    var overlayEnabled: Boolean = true,
    var opacity: Float = 0.55f,
    var autoConnectMac: String? = null,
    val buttons: MutableList<ButtonConfig> = defaultButtons()
)

const val BUTTON_COUNT = 15

/** A 5 x 3 pad in the lower part of the screen. */
fun defaultButtons(): MutableList<ButtonConfig> {
    val list = ArrayList<ButtonConfig>(BUTTON_COUNT)
    for (i in 1..BUTTON_COUNT) {
        val col = (i - 1) % 5
        val row = (i - 1) / 5
        list.add(
            ButtonConfig(
                id = i,
                label = "B$i",
                nx = 0.11f + col * 0.195f,
                ny = 0.68f + row * 0.12f,
                radius = 0.068f
            )
        )
    }
    return list
}

object ConfigCodec {

    fun toJson(config: AppConfig): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("overlayEnabled", config.overlayEnabled)
        root.put("opacity", config.opacity.toDouble())
        config.autoConnectMac?.let { root.put("autoConnectMac", it) }

        val buttons = JSONArray()
        for (b in config.buttons) {
            val o = JSONObject()
            o.put("id", b.id)
            o.put("label", b.label)
            o.put("enabled", b.enabled)
            o.put("nx", b.nx.toDouble())
            o.put("ny", b.ny.toDouble())
            o.put("radius", b.radius.toDouble())
            o.put("action", b.action.name)

            val gesture = JSONArray()
            for (p in b.gesture) {
                val po = JSONObject()
                po.put("x", p.nx.toDouble())
                po.put("y", p.ny.toDouble())
                po.put("t", p.t)
                gesture.put(po)
            }
            o.put("gesture", gesture)
            buttons.put(o)
        }
        root.put("buttons", buttons)
        return root.toString()
    }

    fun fromJson(raw: String?): AppConfig {
        val config = AppConfig(buttons = ArrayList())
        if (raw.isNullOrBlank()) {
            config.buttons.addAll(defaultButtons())
            return config
        }
        try {
            val root = JSONObject(raw)
            config.overlayEnabled = root.optBoolean("overlayEnabled", true)
            config.opacity = root.optDouble("opacity", 0.55).toFloat().coerceIn(0.05f, 1f)
            config.autoConnectMac = if (root.has("autoConnectMac")) root.getString("autoConnectMac") else null

            val stored = HashMap<Int, ButtonConfig>()
            val arr = root.optJSONArray("buttons")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optInt("id", -1)
                    if (id !in 1..BUTTON_COUNT) continue

                    val points = ArrayList<GesturePoint>()
                    val ga = o.optJSONArray("gesture")
                    if (ga != null) {
                        for (j in 0 until ga.length()) {
                            val po = ga.optJSONObject(j) ?: continue
                            points.add(
                                GesturePoint(
                                    nx = po.optDouble("x", 0.0).toFloat(),
                                    ny = po.optDouble("y", 0.0).toFloat(),
                                    t = po.optLong("t", 0L)
                                )
                            )
                        }
                    }

                    val action = try {
                        ActionType.valueOf(o.optString("action", ActionType.HOLD.name))
                    } catch (e: IllegalArgumentException) {
                        ActionType.HOLD
                    }

                    stored[id] = ButtonConfig(
                        id = id,
                        label = o.optString("label", "B$id"),
                        enabled = o.optBoolean("enabled", true),
                        nx = o.optDouble("nx", 0.5).toFloat().coerceIn(0f, 1f),
                        ny = o.optDouble("ny", 0.5).toFloat().coerceIn(0f, 1f),
                        radius = o.optDouble("radius", 0.075).toFloat().coerceIn(0.02f, 0.4f),
                        action = action,
                        gesture = points
                    )
                }
            }

            val defaults = defaultButtons().associateBy { it.id }
            for (id in 1..BUTTON_COUNT) {
                config.buttons.add(stored[id] ?: defaults.getValue(id))
            }
            return config
        } catch (e: Exception) {
            config.buttons.clear()
            config.buttons.addAll(defaultButtons())
            return config
        }
    }
}

class ConfigRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("esp32_keymapper", Context.MODE_PRIVATE)

    fun load(): AppConfig = ConfigCodec.fromJson(prefs.getString(KEY_CONFIG, null))

    fun save(config: AppConfig) {
        prefs.edit().putString(KEY_CONFIG, ConfigCodec.toJson(config)).apply()
    }

    private companion object {
        const val KEY_CONFIG = "config_json"
    }
}
