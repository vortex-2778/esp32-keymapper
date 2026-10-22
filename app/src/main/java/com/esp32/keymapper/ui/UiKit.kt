package com.esp32.keymapper.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Small helpers so the screens can be built without XML layouts. */
object Ui {

    fun dp(context: Context, value: Number): Int =
        (value.toFloat() * context.resources.displayMetrics.density).toInt()

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float = 14f,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(0f, 1.1f)
    }

    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(context: Context, content: LinearLayout): MaterialCardView {
        val card = MaterialCardView(context)
        card.radius = dp(context, 18).toFloat()
        card.cardElevation = dp(context, 1).toFloat()
        card.setContentPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        card.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return card
    }

    fun button(
        context: Context,
        label: String,
        outlined: Boolean = false,
        onClick: () -> Unit
    ): MaterialButton {
        val button = if (outlined) {
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        } else {
            MaterialButton(context)
        }
        button.text = label
        button.isAllCaps = false
        button.textSize = 13f
        button.minWidth = 0
        button.minimumWidth = 0
        button.insetTop = 0
        button.insetBottom = 0
        button.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
        button.setOnClickListener { onClick() }
        return button
    }

    fun switchRow(
        context: Context,
        title: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): Pair<LinearLayout, com.google.android.material.switchmaterial.SwitchMaterial> {
        val row = row(context)
        val label = text(context, title, 14f)
        row.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val toggle = com.google.android.material.switchmaterial.SwitchMaterial(context)
        toggle.isChecked = checked
        toggle.setOnCheckedChangeListener { _, value -> onChanged(value) }
        row.addView(toggle)
        return row to toggle
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(0x1F888888)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 1)
        ).apply { topMargin = dp(context, 10); bottomMargin = dp(context, 10) }
    }

    fun weight(marginEndDp: Int = 8): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = marginEndDp
        }

    fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    fun matchTop(marginTopDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = marginTopDp }

    fun transparentColor(): ColorStateList = ColorStateList.valueOf(Color.TRANSPARENT)
}
