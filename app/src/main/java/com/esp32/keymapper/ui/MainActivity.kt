package com.esp32.keymapper.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.esp32.keymapper.R
import com.esp32.keymapper.access.KeyMapperAccessibilityService
import com.esp32.keymapper.core.ActionType
import com.esp32.keymapper.core.BtState
import com.esp32.keymapper.core.ControllerHub
import com.esp32.keymapper.service.ControllerService
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity(), ControllerHub.Listener {

    private lateinit var btStatus: TextView
    private lateinit var accessStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var deviceContainer: LinearLayout
    private lateinit var logView: TextView
    private lateinit var opacityBar: SeekBar
    private lateinit var opacityLabel: TextView
    private lateinit var overlaySwitch: SwitchMaterial
    private lateinit var serviceButton: MaterialButton

    private val buttonSwitches = HashMap<Int, SwitchMaterial>()
    private val buttonSummaries = HashMap<Int, TextView>()

    private var bindingUi = false
    private var lastBtState: BtState? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        rebuildDevices()
        refreshAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ControllerHub.init(applicationContext)
        setContentView(buildContent())
        requestPermissionsIfNeeded()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        ControllerHub.addListener(this)
        lastBtState = null
        refreshAll(rebuildDeviceList = true)
    }

    override fun onPause() {
        super.onPause()
        ControllerHub.removeListener(this)
    }

    override fun onStateChanged() {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            val stateChanged = lastBtState != ControllerHub.btState
            refreshAll(rebuildDeviceList = stateChanged)
        }
    }

    // ------------------------------------------------------------------ ui

    private fun buildContent(): ViewGroup {
        val scroll = android.widget.ScrollView(this)
        val column = Ui.column(this)
        column.setPadding(Ui.dp(this, 16), Ui.dp(this, 28), Ui.dp(this, 16), Ui.dp(this, 40))
        scroll.addView(
            column,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        column.addView(Ui.text(this, getString(R.string.app_name), 26f, true))
        column.addView(
            Ui.text(this, "ESP32 Bluetooth buttons become on-screen touches.", 13f)
                .apply { alpha = 0.7f }
        )

        column.addView(Ui.card(this, buildStatusCard()), Ui.matchTop(Ui.dp(this, 18)))
        column.addView(Ui.card(this, buildControllerCard()), Ui.matchTop(Ui.dp(this, 14)))
        column.addView(Ui.card(this, buildOverlayCard()), Ui.matchTop(Ui.dp(this, 14)))
        column.addView(Ui.card(this, buildButtonCard()), Ui.matchTop(Ui.dp(this, 14)))
        column.addView(Ui.card(this, buildLogCard()), Ui.matchTop(Ui.dp(this, 14)))

        return scroll
    }

    private fun buildStatusCard(): LinearLayout {
        val content = Ui.column(this)
        content.addView(Ui.text(this, "Status", 16f, true))

        btStatus = Ui.text(this, "Bluetooth: –", 13f)
        accessStatus = Ui.text(this, "Accessibility: –", 13f)
        overlayStatus = Ui.text(this, "Overlay: –", 13f)
        content.addView(btStatus, Ui.matchTop(Ui.dp(this, 8)))
        content.addView(accessStatus, Ui.matchTop(Ui.dp(this, 4)))
        content.addView(overlayStatus, Ui.matchTop(Ui.dp(this, 4)))

        val row = Ui.row(this)
        val accessButton = Ui.button(this, "Accessibility", true) { openAccessibilitySettings() }
        val overlayButton = Ui.button(this, "Overlay access", true) { openOverlaySettings() }
        row.addView(accessButton, Ui.weight())
        row.addView(overlayButton, Ui.weight(0))
        content.addView(row, Ui.matchTop(Ui.dp(this, 12)))

        serviceButton = Ui.button(this, "Start service") {
            if (ControllerService.isRunning) {
                ControllerService.stop(this)
                serviceButton.postDelayed({ refreshAll() }, 400)
            } else {
                startServiceIfAllowed()
            }
        }
        content.addView(serviceButton, Ui.matchTop(Ui.dp(this, 10)))
        return content
    }

    private fun buildControllerCard(): LinearLayout {
        val content = Ui.column(this)
        content.addView(Ui.text(this, "Controller", 16f, true))
        content.addView(
            Ui.text(this, "Pair the ESP32 in Android Settings first. The sketch advertises itself as “ESP32 Sim-Race Controller”.", 12f)
                .apply { alpha = 0.7f },
            Ui.matchTop(Ui.dp(this, 4))
        )

        deviceContainer = Ui.column(this)
        content.addView(deviceContainer, Ui.matchTop(Ui.dp(this, 8)))

        val row = Ui.row(this)
        val refresh = Ui.button(this, "Refresh", true) { rebuildDevices() }
        val disconnect = Ui.button(this, "Disconnect", true) { disconnectNow() }
        row.addView(refresh, Ui.weight())
        row.addView(disconnect, Ui.weight(0))
        content.addView(row, Ui.matchTop(Ui.dp(this, 8)))
        return content
    }

    private fun buildOverlayCard(): LinearLayout {
        val content = Ui.column(this)
        content.addView(Ui.text(this, "Overlay", 16f, true))

        val (switchRow, toggle) = Ui.switchRow(
            this,
            "Show buttons on screen",
            ControllerHub.config.overlayEnabled
        ) { checked ->
            if (!bindingUi) {
                ControllerHub.updateConfig { it.overlayEnabled = checked }
                if (ControllerService.isRunning) {
                    ControllerService.setOverlay(this, checked)
                }
            }
        }
        overlaySwitch = toggle
        content.addView(switchRow, Ui.matchTop(Ui.dp(this, 6)))

        opacityLabel = Ui.text(this, "Opacity", 13f)
        content.addView(opacityLabel, Ui.matchTop(Ui.dp(this, 12)))

        opacityBar = SeekBar(this).apply {
            max = 95
            progress = ((ControllerHub.config.opacity - 0.05f) * 100f).toInt().coerceIn(0, 95)
        }
        opacityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                opacityLabel.text = "Opacity: ${progress + 5}%"
                if (fromUser && !bindingUi) {
                    ControllerHub.setOpacity((progress + 5) / 100f)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        content.addView(opacityBar)

        val edit = Ui.button(this, "Edit layout & gestures", true) {
            startActivity(Intent(this, EditorActivity::class.java))
        }
        content.addView(edit, Ui.matchTop(Ui.dp(this, 8)))
        return content
    }

    private fun buildButtonCard(): LinearLayout {
        val content = Ui.column(this)
        content.addView(Ui.text(this, "Buttons", 16f, true))
        content.addView(
            Ui.text(this, "Turn a button off to ignore it. Each button can hold, tap or play a drawn gesture.", 12f)
                .apply { alpha = 0.7f },
            Ui.matchTop(Ui.dp(this, 4))
        )

        for (button in ControllerHub.config.buttons.sortedBy { it.id }) {
            val row = Ui.row(this)
            val label = Ui.text(this, "B${button.id} · ${summaryOf(button.action, button.gesture.size)}", 13f)
            buttonSummaries[button.id] = label
            row.addView(label, Ui.weight())

            val toggle = SwitchMaterial(this)
            toggle.isChecked = button.enabled
            toggle.setOnCheckedChangeListener { _, checked ->
                if (bindingUi) return@setOnCheckedChangeListener
                ControllerHub.updateConfig { config ->
                    config.buttons.firstOrNull { it.id == button.id }?.enabled = checked
                }
            }
            buttonSwitches[button.id] = toggle
            row.addView(toggle)

            val edit = Ui.button(this, "Edit", true) {
                val intent = Intent(this, EditorActivity::class.java)
                intent.putExtra(EditorActivity.EXTRA_BUTTON_ID, button.id)
                startActivity(intent)
            }
            row.addView(edit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(this@MainActivity, 6) })

            content.addView(row, Ui.matchTop(Ui.dp(this, 6)))
        }
        return content
    }

    private fun buildLogCard(): LinearLayout {
        val content = Ui.column(this)
        content.addView(Ui.text(this, "Last events", 16f, true))
        logView = Ui.text(this, "No events yet.", 11f).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        }
        content.addView(logView, Ui.matchTop(Ui.dp(this, 6)))
        return content
    }

    // ------------------------------------------------------------- refresh

    private fun refreshAll(rebuildDeviceList: Boolean = false) {
        if (bindingUi) return
        bindingUi = true

        val hub = ControllerHub
        btStatus.text = "Bluetooth: " + when (hub.btState) {
            BtState.CONNECTED -> "connected to ${hub.btDeviceName ?: "controller"}"
            BtState.CONNECTING -> "connecting…"
            BtState.ERROR -> "error, retrying…"
            BtState.DISCONNECTED -> "disconnected"
        }
        accessStatus.text = "Accessibility: " +
            if (KeyMapperAccessibilityService.isEnabled) "enabled" else "disabled"
        overlayStatus.text = "Overlay: " + when {
            !Settings.canDrawOverlays(this) -> "permission needed"
            !ControllerService.isRunning -> "service off"
            !hub.config.overlayEnabled -> "hidden"
            else -> "visible"
        }

        overlaySwitch.isChecked = hub.config.overlayEnabled
        val percent = (hub.config.opacity * 100f).toInt().coerceIn(5, 100)
        opacityBar.progress = percent - 5
        opacityLabel.text = "Opacity: $percent%"
        serviceButton.text = if (ControllerService.isRunning) "Stop service" else "Start service"

        for (button in hub.config.buttons) {
            buttonSwitches[button.id]?.isChecked = button.enabled
            buttonSummaries[button.id]?.text =
                "B${button.id} · ${summaryOf(button.action, button.gesture.size)}"
        }

        logView.text = hub.logText()

        if (rebuildDeviceList) rebuildDevices()
        lastBtState = hub.btState
        bindingUi = false
    }

    private fun rebuildDevices() {
        if (bindingUi) {
            // Called while a refresh is running – defer.
            deviceContainer.post { rebuildDevices() }
            return
        }
        if (!this::deviceContainer.isInitialized) return

        deviceContainer.removeAllViews()

        if (!hasBluetoothPermission()) {
            deviceContainer.addView(Ui.text(this, "Bluetooth permission is required.", 13f))
            deviceContainer.addView(Ui.button(this, "Grant permission", true) {
                requestPermissionsIfNeeded()
            }, Ui.matchTop(Ui.dp(this, 6)))
            return
        }

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            deviceContainer.addView(Ui.text(this, "This device has no Bluetooth adapter.", 13f))
            return
        }
        if (!adapter.isEnabled) {
            deviceContainer.addView(Ui.text(this, "Bluetooth is turned off.", 13f))
            return
        }

        val devices: List<BluetoothDevice> = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

        if (devices.isEmpty()) {
            deviceContainer.addView(
                Ui.text(this, "No paired devices yet. Pair the controller in Android Bluetooth settings.", 13f)
            )
            return
        }

        for (device in devices.sortedBy { safeName(it) }) {
            val row = Ui.row(this)
            val info = Ui.column(this)
            info.addView(Ui.text(this, safeName(device), 14f, true))
            info.addView(Ui.text(this, device.address, 11f).apply { alpha = 0.6f })
            row.addView(info, Ui.weight())

            val isConnected = ControllerHub.btState == BtState.CONNECTED &&
                ControllerHub.config.autoConnectMac == device.address
            val action = Ui.button(this, if (isConnected) "Connected" else "Connect", true) {
                connectTo(device)
            }
            action.isEnabled = !isConnected
            row.addView(action)

            deviceContainer.addView(row, Ui.matchTop(Ui.dp(this, 8)))
        }
    }

    // ------------------------------------------------------------- actions

    private fun connectTo(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            requestPermissionsIfNeeded()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Grant the overlay permission first")
            openOverlaySettings()
            return
        }
        if (!ControllerService.isRunning) {
            ControllerService.start(this)
        }
        ControllerService.connect(this, device.address)
        toast("Connecting to ${safeName(device)}…")
        lastBtState = null
        deviceContainer.postDelayed({ refreshAll(rebuildDeviceList = true) }, 600)
    }

    private fun disconnectNow() {
        ControllerHub.releaseAll()
        if (ControllerService.isRunning) {
            ControllerService.disconnect(this)
        } else {
            ControllerHub.btState = BtState.DISCONNECTED
            ControllerHub.notifyState()
        }
        toast("Disconnect requested")
    }

    private fun startServiceIfAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission()) {
            toast("Grant the Bluetooth permission first")
            requestPermissionsIfNeeded()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Grant the overlay permission first")
            openOverlaySettings()
            return
        }
        ControllerService.start(this)
        serviceButton.postDelayed({ refreshAll() }, 500)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Enable “ESP32 Key Mapper” in the list")
        } catch (e: Exception) {
            toast("Could not open accessibility settings")
        }
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            toast("Could not open overlay settings")
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name ?: device.address
    } catch (e: SecurityException) {
        device.address
    }

    private fun summaryOf(action: ActionType, points: Int): String = when (action) {
        ActionType.HOLD -> "Hold"
        ActionType.TAP -> "Tap"
        ActionType.GESTURE -> "Gesture ($points pts)"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
