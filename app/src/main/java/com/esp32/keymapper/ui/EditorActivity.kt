package com.esp32.keymapper.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.esp32.keymapper.access.KeyMapperAccessibilityService
import com.esp32.keymapper.core.ActionType
import com.esp32.keymapper.core.ButtonConfig
import com.esp32.keymapper.core.ControllerHub
import com.esp32.keymapper.core.GesturePoint
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.roundToInt

/**
 * Visual layout editor: drag buttons, resize them, change their action and draw
 * custom gestures.
 */
class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BUTTON_ID = "extra_button_id"
    }

    private lateinit var editor: LayoutEditorView
    private lateinit var buttonSpinner: Spinner
    private lateinit var actionSpinner: Spinner
    private lateinit var enabledSwitch: SwitchMaterial
    private lateinit var labelField: EditText
    private lateinit var radiusBar: SeekBar
    private lateinit var radiusLabel: TextView
    private lateinit var opacityBar: SeekBar
    private lateinit var opacityLabel: TextView
    private lateinit var gestureInfo: TextView

    private var currentId = 1
    private var binding = false

    private val actionNames = listOf("Hold while pressed", "Tap", "Drawn gesture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ControllerHub.init(applicationContext)
        currentId = intent.getIntExtra(EXTRA_BUTTON_ID, 1).coerceIn(1, 15)

        setContentView(buildUi())
        selectButton(currentId)
    }

    override fun onPause() {
        super.onPause()
        ControllerHub.saveConfig()
    }

    // ------------------------------------------------------------------ ui

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101418.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        header.addView(Ui.text(this, "Layout editor", 20f, true), Ui.weight())
        header.addView(Ui.button(this, "Done") { finish() })
        root.addView(header)

        editor = LayoutEditorView(this).apply {
            onSelectionChanged = { id -> if (!binding) selectButton(id) }
            onLayoutFinished = { ControllerHub.saveConfig() }
            onGestureDrawn = { points -> assignGesture(points) }
        }
        root.addView(
            editor,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.3f)
        )

        val scroll = ScrollView(this)
        val panel = Ui.column(this)
        panel.setPadding(dp(16), dp(10), dp(16), dp(28))
        scroll.addView(panel)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        panel.addView(buildButtonRow())
        panel.addView(buildEnabledRow(), Ui.matchTop(dp(8)))
        panel.addView(buildActionRow(), Ui.matchTop(dp(8)))
        panel.addView(buildLabelRow(), Ui.matchTop(dp(8)))
        panel.addView(buildRadiusRow(), Ui.matchTop(dp(10)))
        panel.addView(buildOpacityRow(), Ui.matchTop(dp(6)))

        val actions = Ui.row(this)
        actions.addView(Ui.button(this, "Draw here", true) {
            editor.setDrawMode(true)
            gestureInfo.text = "Draw with one finger on the preview, lift to save."
        }, Ui.weight())
        actions.addView(Ui.button(this, "Full screen", true) {
            val started = ControllerHub.startCapture(currentId) { id -> selectButton(id) }
            if (!started) {
                toast("Enable the accessibility service to draw over other apps")
            }
        }, Ui.weight())
        actions.addView(Ui.button(this, "Clear", true) { clearGesture() }, Ui.weight(0))
        panel.addView(actions, Ui.matchTop(dp(12)))

        gestureInfo = Ui.text(this, "No gesture recorded.", 12f).apply { alpha = 0.75f }
        panel.addView(gestureInfo, Ui.matchTop(dp(8)))

        return root
    }

    private fun buildButtonRow(): LinearLayout {
        val row = Ui.row(this)
        row.addView(Ui.text(this, "Button", 13f))
        buttonSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                (1..15).map { "B$it" }
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (!binding) selectButton(position + 1)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        row.addView(buttonSpinner, Ui.weight(0))
        return row
    }

    private fun buildEnabledRow(): LinearLayout {
        val row = Ui.row(this)
        row.addView(Ui.text(this, "Enabled", 13f), Ui.weight())
        enabledSwitch = SwitchMaterial(this)
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            val button = currentButton() ?: return@setOnCheckedChangeListener
            button.enabled = checked
            ControllerHub.saveConfig()
            editor.invalidate()
        }
        row.addView(enabledSwitch)
        return row
    }

    private fun buildActionRow(): LinearLayout {
        val row = Ui.row(this)
        row.addView(Ui.text(this, "Action", 13f))
        actionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                actionNames
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (binding) return
                    val button = currentButton() ?: return
                    button.action = when (position) {
                        1 -> ActionType.TAP
                        2 -> ActionType.GESTURE
                        else -> ActionType.HOLD
                    }
                    if (button.action == ActionType.GESTURE && button.gesture.size < 2) {
                        toast("Draw a gesture, then press Draw here again")
                    }
                    ControllerHub.saveConfig()
                    editor.invalidate()
                    refreshGestureInfo()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        row.addView(actionSpinner, Ui.weight(0))
        return row
    }

    private fun buildLabelRow(): LinearLayout {
        val row = Ui.row(this)
        row.addView(Ui.text(this, "Label", 13f))
        labelField = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
        }
        labelField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (binding) return
                val button = currentButton() ?: return
                button.label = s?.toString()?.takeIf { it.isNotBlank() } ?: "B${button.id}"
                editor.invalidate()
                ControllerHub.saveConfig()
            }
        })
        row.addView(labelField, Ui.weight(0))
        return row
    }

    private fun buildRadiusRow(): LinearLayout {
        val column = Ui.column(this)
        radiusLabel = Ui.text(this, "Size", 13f)
        column.addView(radiusLabel)
        radiusBar = SeekBar(this).apply { max = 23 }
        radiusBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = progress + 2
                radiusLabel.text = "Size: $percent% of screen"
                if (fromUser && !binding) {
                    val button = currentButton() ?: return
                    button.radius = percent / 100f
                    editor.invalidate()
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                ControllerHub.saveConfig()
            }
        })
        column.addView(radiusBar)
        return column
    }

    private fun buildOpacityRow(): LinearLayout {
        val column = Ui.column(this)
        opacityLabel = Ui.text(this, "Opacity", 13f)
        column.addView(opacityLabel)
        opacityBar = SeekBar(this).apply { max = 95 }
        opacityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = progress + 5
                opacityLabel.text = "All buttons opacity: $percent%"
                if (fromUser && !binding) {
                    ControllerHub.setOpacity(percent / 100f)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        column.addView(opacityBar)
        return column
    }

    // ------------------------------------------------------------- helpers

    private fun currentButton(): ButtonConfig? =
        ControllerHub.config.buttons.firstOrNull { it.id == currentId }

    private fun selectButton(id: Int) {
        currentId = id
        binding = true
        buttonSpinner.setSelection(id - 1)
        binding = false
        editor.setSelected(id)
        refreshPanel()
    }

    private fun refreshPanel() {
        val button = currentButton() ?: return
        val config = ControllerHub.config

        binding = true
        enabledSwitch.isChecked = button.enabled
        actionSpinner.setSelection(button.action.ordinal)
        labelField.setText(button.label)

        val sizePercent = (button.radius * 100f).roundToInt().coerceIn(2, 25)
        radiusBar.progress = sizePercent - 2
        radiusLabel.text = "Size: $sizePercent% of screen"

        val opacityPercent = (config.opacity * 100f).roundToInt().coerceIn(5, 100)
        opacityBar.progress = opacityPercent - 5
        opacityLabel.text = "All buttons opacity: $opacityPercent%"
        binding = false

        refreshGestureInfo()
    }

    private fun refreshGestureInfo() {
        val button = currentButton() ?: return
        gestureInfo.text = if (button.gesture.size >= 2) {
            val duration = button.gesture.last().t - button.gesture.first().t
            "${button.gesture.size} points · ${duration} ms · plays on press"
        } else {
            "No gesture recorded. Use “Draw here” or “Full screen”."
        }
    }

    private fun assignGesture(points: List<GesturePoint>) {
        val button = currentButton() ?: return
        button.gesture = points
        button.action = ActionType.GESTURE
        ControllerHub.saveConfig()
        refreshPanel()
        editor.invalidate()
        toast("Gesture saved for ${button.label}")
    }

    private fun clearGesture() {
        val button = currentButton() ?: return
        button.gesture = emptyList()
        if (button.action == ActionType.GESTURE) button.action = ActionType.HOLD
        ControllerHub.saveConfig()
        refreshPanel()
        editor.invalidate()
    }

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
