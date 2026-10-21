package com.mapmouse.pro.ui

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import com.mapmouse.pro.model.Profile
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cursor-mode and recoil controls shared by the main screen, the keymap editor and the
 * in-game quick panel. [onChange] is called after every edit of [p].
 */

/** Slider with −/+ buttons for exact steps (sliders alone are fiddly on a phone). */
fun Context.stepSlider(
    title: String, min: Float, max: Float, step: Float, value: Float,
    fmt: (Float) -> String, onChange: (Float) -> Unit,
): View {
    var current = value.coerceIn(min, max)
    val text = label("$title: ${fmt(current)}").apply { minWidth = dp(150f) }
    val steps = ((max - min) / step).roundToInt()
    val seek = SeekBar(this).apply {
        this.max = steps
        progress = ((current - min) / step).roundToInt()
        layoutParams = LinearLayout.LayoutParams(dp(170f), LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    fun set(v: Float, fromSeek: Boolean) {
        // Snap to the step grid so values like 0.06 don't drift to 0.0599999.
        current = (min + ((v - min) / step).roundToInt() * step).coerceIn(min, max)
        if (abs(current) < step / 2) current = 0f
        text.text = "$title: ${fmt(current)}"
        if (!fromSeek) seek.progress = ((current - min) / step).roundToInt()
        onChange(current)
    }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) set(min + p * step, true) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    })
    return hscroll(hrow(text, chip("−") { set(current - step, false) }, seek, chip("+") { set(current + step, false) }))
}

/**
 * Lets a row scroll sideways instead of being squeezed: in a narrow window (like the in-game
 * panel) a squeezed row wraps every label one word per line.
 */
fun Context.hscroll(v: View): View = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    addView(v)
}

fun Context.cursorRows(p: Profile, onChange: () -> Unit): List<View> = listOf(
    hscroll(hrow(
        label("Cursor mode:"),
        chip(if (p.cursorHold) "Hold key" else "Toggle") { b ->
            p.cursorHold = !p.cursorHold
            b.text = if (p.cursorHold) "Hold key" else "Toggle"
            onChange()
        },
        label("Toggle: press to switch.  Hold: pointer only while held.", 12f),
    )),
    stepSlider("Cursor speed", 0.3f, 5f, 0.1f, p.cursorSpeed, { "%.1f".format(it) }) { p.cursorSpeed = it; onChange() },
)

private fun fmtHorizontal(v: Float) = when {
    v < 0f -> "%.3f ←".format(-v)
    v > 0f -> "%.3f →".format(v)
    else -> "off"
}

fun Context.recoilRows(p: Profile, onChange: () -> Unit): List<View> = listOf(
    hscroll(hrow(
        label("Recoil control:"),
        chip(if (p.recoilEnabled) "On" else "Off") { b ->
            p.recoilEnabled = !p.recoilEnabled
            b.text = if (p.recoilEnabled) "On" else "Off"
            onChange()
        },
        label("Pulls the aim while the fire key is held.", 12f),
    )),
    stepSlider("Vertical ↓", 0f, 0.4f, 0.005f, p.recoilStrength, { if (it == 0f) "off" else "%.3f".format(it) }) {
        p.recoilStrength = it; onChange()
    },
    stepSlider("Horizontal", -0.2f, 0.2f, 0.005f, p.recoilStrengthX, ::fmtHorizontal) {
        p.recoilStrengthX = it; onChange()
    },
    stepSlider("Start delay", 0f, 500f, 10f, p.recoilDelay.toFloat(), { "${it.toInt()} ms" }) {
        p.recoilDelay = it.toInt(); onChange()
    },
)
