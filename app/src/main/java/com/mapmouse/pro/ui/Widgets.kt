package com.mapmouse.pro.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Settings widgets in the style of the app: full-width rows with a title (and optional
 * subtitle) on the left and a control on the right, separated by thin lines; section
 * headers are a strip with the title in the accent color.
 *
 * Two skins: light (app screens: white rows, dark text) and indigo (in-game panels:
 * indigo rows, white text, white header strips). Chosen from the context, see [light].
 */

private val Context.rowText get() = if (light) Pal.INK else Color.WHITE
private val Context.rowSub get() = if (light) Pal.SUB else 0xCCFFFFFF.toInt()
private val Context.rowLine get() = if (light) Pal.LINE else 0x40FFFFFF

/** Section header strip. */
fun Context.ggHeader(title: String): View = TextView(this).apply {
    text = title
    textSize = 15f
    setTextColor(Pal.ACCENT)
    setBackgroundColor(if (light) Pal.BAR else Color.WHITE)
    setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
}

/** A settings row: title/subtitle on the left, [trailing] on the right, divider below. */
fun Context.ggRow(title: String, subtitle: String? = null, trailing: View? = null, onClick: (() -> Unit)? = null): View {
    val text = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { this.text = title; textSize = 16f; setTextColor(rowText) })
        if (subtitle != null) addView(TextView(context).apply {
            this.text = subtitle; textSize = 13f; setTextColor(rowSub); setPadding(0, dp(2f), 0, 0)
        })
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(58f)
        setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) addView(trailing)
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (tv.resourceId != 0) foreground = context.getDrawable(tv.resourceId)
        }
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(row)
        addView(View(context).apply { setBackgroundColor(rowLine) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                marginStart = dp(16f); marginEnd = dp(16f)
            })
    }
}

/** "value  >" on the right of a navigation row. */
fun Context.ggValue(value: String, chevron: Boolean = true): View = TextView(this).apply {
    text = if (chevron) "$value  >" else value
    textSize = 15f
    setTextColor(if (light) Pal.SUB else Color.WHITE)
}

/** Circled arrow used on the in-game settings list. */
fun Context.ggChevron(): View = IconView(this, Icon.CHEVRON, if (light) Pal.ACCENT else Color.WHITE, 34f)

fun Context.ggSwitch(on: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
    isChecked = on
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.WHITE))
    trackTintList = ColorStateList(states,
        if (light) intArrayOf(Pal.ACCENT, 0xFFD1D1D6.toInt()) else intArrayOf(0xFFB8BEFF.toInt(), 0xFF3B47C8.toInt()))
    setOnCheckedChangeListener { _, c -> onChange(c) }
}

/** Row with a switch. */
fun Context.ggSwitchRow(title: String, subtitle: String? = null, on: Boolean, onChange: (Boolean) -> Unit): View {
    val sw = ggSwitch(on, onChange)
    return ggRow(title, subtitle, sw) { sw.toggle() }
}

/** Slider with boxed −/+ buttons and the value shown above it. */
fun Context.ggStepper(
    min: Float, max: Float, step: Float, value: Float, fmt: (Float) -> String, onChange: (Float) -> Unit,
): View {
    var current = value.coerceIn(min, max)
    val steps = ((max - min) / step).roundToInt().coerceAtLeast(1)
    val fg = if (light) Pal.ACCENT else Color.WHITE
    val valueText = TextView(this).apply { textSize = 13f; setTextColor(fg); text = fmt(current) }
    val seek = SeekBar(this).apply {
        this.max = steps
        progress = ((current - min) / step).roundToInt()
        progressTintList = ColorStateList.valueOf(fg)
        thumbTintList = ColorStateList.valueOf(fg)
        progressBackgroundTintList = ColorStateList.valueOf(if (light) 0xFFD1D1D6.toInt() else 0xFFB8BEFF.toInt())
    }
    fun set(v: Float, fromSeek: Boolean) {
        current = (min + ((v - min) / step).roundToInt() * step).coerceIn(min, max)
        if (abs(current) < step / 2) current = 0f
        valueText.text = fmt(current)
        if (!fromSeek) seek.progress = ((current - min) / step).roundToInt()
        onChange(current)
    }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) set(min + p * step, true) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    })
    fun box(label: String, delta: Float) = TextView(this).apply {
        text = label; textSize = 18f; setTextColor(fg); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setStroke(dp(1.5f), fg); cornerRadius = dp(6f).toFloat() }
        layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        setOnClickListener { set(current + delta, false) }
    }
    val middle = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(valueText.apply { setPadding(dp(14f), 0, 0, 0) })
        addView(seek, LinearLayout.LayoutParams(dp(190f), LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(box("−", -step))
        addView(middle)
        addView(box("+", step))
    }
}

fun Context.ggStepperRow(
    title: String, subtitle: String? = null, min: Float, max: Float, step: Float, value: Float,
    fmt: (Float) -> String = { "%.1f".format(it) }, onChange: (Float) -> Unit,
): View = ggRow(title, subtitle, ggStepper(min, max, step, value, fmt, onChange))

/** Segmented control: the selected option is a filled pill. */
fun Context.ggSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundBg(if (light) Pal.ACCENT_SOFT else 0xFFB8BEFF.toInt(), dp(10f).toFloat())
        setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
    }
    var sel = selected
    val views = ArrayList<TextView>()
    fun paint() = views.forEachIndexed { i, v ->
        val on = i == sel
        v.background = if (on) roundBg(if (light) Pal.ACCENT else Color.WHITE, dp(8f).toFloat()) else null
        v.setTextColor(if (on) (if (light) Color.WHITE else Pal.ACCENT) else Pal.ACCENT)
    }
    options.forEachIndexed { i, o ->
        val v = TextView(this).apply {
            text = o; textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            setOnClickListener { sel = i; paint(); onSelect(i) }
        }
        views += v
        bar.addView(v)
    }
    paint()
    // Many options: scroll sideways instead of squeezing (which wraps labels into tall columns).
    return CappedScroll(this, (realScreenSize().x * 0.5f).toInt()).apply { addView(bar) }
}

/** HorizontalScrollView that never asks for more than [maxWidth] pixels. */
class CappedScroll(context: Context, private val maxWidth: Int) : android.widget.HorizontalScrollView(context) {
    init { isHorizontalScrollBarEnabled = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        val mode = View.MeasureSpec.getMode(widthMeasureSpec)
        val cap = if (mode == View.MeasureSpec.UNSPECIFIED) maxWidth else minOf(size, maxWidth)
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST), heightMeasureSpec)
    }
}

fun Context.ggSegmentedRow(title: String, subtitle: String? = null, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View =
    ggRow(title, subtitle, ggSegmented(options, selected, onSelect))

/** Color swatches; the chosen one gets a highlighted tile. */
fun Context.ggSwatches(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit): View {
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    var sel = selected
    val tiles = ArrayList<FrameLayout>()
    fun paint() = tiles.forEachIndexed { i, t ->
        t.background = if (colors[i] == sel) roundBg(if (light) Pal.ACCENT_SOFT else 0x55FFFFFF, dp(6f).toFloat()) else null
    }
    for (c in colors) {
        val tile = FrameLayout(this).apply {
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(c); cornerRadius = dp(3f).toFloat()
                    if (c == Color.WHITE || c == Color.BLACK) setStroke(dp(1f), 0x55888888)
                }
            }, FrameLayout.LayoutParams(dp(28f), dp(28f)))
            setOnClickListener { sel = c; paint(); onSelect(c) }
        }
        tiles += tile
        bar.addView(tile)
    }
    paint()
    return bar
}

val SWATCHES = listOf(Color.WHITE, Color.BLACK, 0xFFFF3B30.toInt(), 0xFF34C759.toInt(), 0xFFB57BFF.toInt(),
    0xFFFFD580.toInt(), 0xFFFF9500.toInt(), 0xFF00E5FF.toInt())
