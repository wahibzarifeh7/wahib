package com.mapmouse.pro.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Small helpers for building UI in code. The app's screens use a light theme (indigo accent),
 * the in-game overlays a dark translucent one; helpers pick based on the context.
 */
object Pal {
    const val ACCENT = 0xFF5B67F6.toInt()
    const val ACCENT_SOFT = 0xFFECEEFE.toInt()
    const val BG = 0xFFFFFFFF.toInt()
    const val BAR = 0xFFF6F6F6.toInt()
    const val INK = 0xFF111111.toInt()
    const val TEXT = 0xFF3C3C43.toInt()
    const val SUB = 0xFF8A8A8E.toInt()
    const val LINE = 0xFFE5E5EA.toInt()
    const val OK = 0xFF2E9E4F.toInt()
    const val BAD = 0xFFE5484D.toInt()
    /** In-game bar/panels. */
    const val OVERLAY = 0xE05B67F6.toInt()
}

/** Activities (the app's own screens) are light; services (overlays over the game) dark. */
val Context.light: Boolean get() = this is android.app.Activity

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Float): Int = context.dp(v)

/** Full display size in the current orientation, in the coordinate space touches are injected in. */
fun Context.realScreenSize(): Point {
    val wm = getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= 30) {
        val b = wm.maximumWindowMetrics.bounds
        Point(b.width(), b.height())
    } else {
        Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(it) }
    }
}

@Suppress("DEPRECATION")
fun Context.displayRotation(): Int = getSystemService(WindowManager::class.java).defaultDisplay.rotation

fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

fun roundBg(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.chip(text: String, onClick: (Button) -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    textSize = 13f
    setTextColor(if (light) Pal.ACCENT else Color.WHITE)
    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
    setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
    background = roundBg(if (light) Pal.ACCENT_SOFT else 0xFF2D3440.toInt(), dp(if (light) 18f else 8f).toFloat())
    stateListAnimator = null
    setOnClickListener { onClick(this) }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) }
}

fun Context.label(text: String, size: Float = 13f, color: Int? = null) = TextView(this).apply {
    this.text = text; textSize = size
    setTextColor(color ?: if (light) Pal.TEXT else 0xFFCDD3DD.toInt())
    setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
}

fun Context.hrow(vararg views: View) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEach { addView(it) }
}

/** Labeled slider over a float range; shows the current value. */
fun Context.slider(
    title: String, min: Float, max: Float, value: Float, fmt: (Float) -> String,
    onChange: (Float) -> Unit,
): View {
    val text = label("$title: ${fmt(value)}")
    text.minWidth = dp(150f)
    val seek = SeekBar(this).apply {
        this.max = 1000
        progress = (((value - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val v = min + (max - min) * p / 1000f
                text.text = "$title: ${fmt(v)}"
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        layoutParams = LinearLayout.LayoutParams(dp(220f), LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    return hrow(text, seek)
}

/** Filled indigo pill, like the "Launch" button. */
fun Context.primaryButton(text: String, onClick: (Button) -> Unit): Button = chip(text, onClick).apply {
    setTextColor(Color.WHITE)
    textSize = 15f
    setPadding(dp(22f), dp(9f), dp(22f), dp(9f))
    background = roundBg(Pal.ACCENT, dp(24f).toFloat())
    elevation = dp(2f).toFloat()
}

/** Text color for headings: dark in the app, white over the game. */
val Context.ink: Int get() = if (light) Pal.INK else Color.WHITE
