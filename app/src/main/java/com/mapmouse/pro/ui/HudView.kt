package com.mapmouse.pro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.mapmouse.pro.AppSettings
import com.mapmouse.pro.model.ControlType
import com.mapmouse.pro.model.Keys
import com.mapmouse.pro.model.Profile
import org.json.JSONObject

/**
 * Full-screen, untouchable overlay for in-game information:
 * key labels over the mapped buttons, the touch-path trail, a crosshair and the recoil panel.
 */
class HudView(context: Context) : View(context) {
    var profile: Profile? = null
        set(v) { field = v; invalidate() }
    var hud = JSONObject()
        set(v) { field = v; invalidate() }

    private val loc = IntArray(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = context.dp(11f).toFloat(); isFakeBoldText = true
    }
    private val panelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dp(12f).toFloat(); isFakeBoldText = true; color = Color.WHITE
    }

    /** Recent finger positions with their timestamps, for fading trails. */
    private val trail = ArrayDeque<Triple<Float, Float, Long>>()
    private var pointers = IntArray(0)

    fun setPointers(xy: IntArray) {
        pointers = xy
        val now = SystemClock.uptimeMillis()
        for (i in 0 until xy.size / 2) trail.addLast(Triple(xy[i * 2].toFloat(), xy[i * 2 + 1].toFloat(), now))
        while (trail.size > 400) trail.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        val st = AppSettings.get(context)
        val enabled = hud.optBoolean("enabled")
        val cursor = hud.optBoolean("cursor")
        val size = context.realScreenSize()
        val w = size.x.toFloat(); val h = size.y.toFloat()
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        // Key labels over mapped buttons.
        val p = profile
        if (p != null && enabled && st.showHud && hud.optBoolean("hud", true)) {
            text.color = st.hudColor
            text.alpha = (st.hudAlpha * 255).toInt()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(1.5f).toFloat()
            paint.color = st.hudColor; paint.alpha = (st.hudAlpha * 160).toInt()
            for (c in p.controls) {
                val x = c.x * w; val y = c.y * h
                val label = when (c.type) {
                    ControlType.JOYSTICK -> Keys.name(c.keyUp) + Keys.name(c.keyLeft) + Keys.name(c.keyDown) + Keys.name(c.keyRight)
                    ControlType.VIEW, ControlType.SCROLL -> continue
                    else -> Keys.name(c.key).takeIf { c.key != Keys.NONE } ?: continue
                }
                canvas.drawCircle(x, y, context.dp(14f).toFloat(), paint)
                canvas.drawText(label, x, y + text.textSize / 3, text)
            }
        }

        // Touch paths: fading dots where fingers have been in the last second.
        if (st.showTouches) {
            val now = SystemClock.uptimeMillis()
            while (trail.isNotEmpty() && now - trail.first().third > 1000) trail.removeFirst()
            paint.style = Paint.Style.FILL
            for ((x, y, t) in trail) {
                paint.color = Color.CYAN; paint.alpha = (200 * (1 - (now - t) / 1000f)).toInt().coerceIn(0, 200)
                canvas.drawCircle(x, y, context.dp(3f).toFloat(), paint)
            }
            paint.color = Color.RED; paint.alpha = 200
            for (i in 0 until pointers.size / 2) canvas.drawCircle(pointers[i * 2].toFloat(), pointers[i * 2 + 1].toFloat(), context.dp(8f).toFloat(), paint)
            if (trail.isNotEmpty()) postInvalidateDelayed(50)
        }

        // Crosshair in the middle of the screen.
        val hideForAim = st.crossHideAiming && hud.optBoolean("aiming")
        if (st.crosshair && enabled && !cursor && !hideForAim) drawCrosshair(canvas, st, w / 2 + st.crossOffsetX, h / 2 + st.crossOffsetY)

        // (The recoil panel is a separate, tappable pill next to the logo button.)
        canvas.restore()
    }

    private fun drawCrosshair(canvas: Canvas, st: AppSettings, x: Float, y: Float) {
        val s = context.dp(st.crossSize.toFloat()).toFloat()
        paint.color = st.crossColor; paint.alpha = (st.crossAlpha * 255).toInt()
        paint.strokeWidth = (s / 10f).coerceAtLeast(2f)
        val gap = s * 0.25f
        when (st.crossStyle) {
            1 -> { paint.style = Paint.Style.FILL; canvas.drawCircle(x, y, s * 0.12f, paint) }
            2 -> { paint.style = Paint.Style.STROKE; canvas.drawCircle(x, y, s * 0.5f, paint)
                paint.style = Paint.Style.FILL; canvas.drawCircle(x, y, s * 0.06f, paint) }
            5 -> { // corner brackets
                val b = s * 0.45f; val l = s * 0.22f
                for ((dx, dy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
                    val cx = x + dx * b; val cy = y + dy * b
                    canvas.drawLine(cx, cy, cx - dx * l, cy, paint); canvas.drawLine(cx, cy, cx, cy - dy * l, paint)
                }
                paint.style = Paint.Style.FILL; canvas.drawCircle(x, y, s * 0.05f, paint)
            }
            4 -> { // T shape (no top line)
                canvas.drawLine(x - s, y, x - gap, y, paint); canvas.drawLine(x + gap, y, x + s, y, paint)
                canvas.drawLine(x, y + gap, x, y + s, paint)
            }
            else -> {
                canvas.drawLine(x - s, y, x - gap, y, paint); canvas.drawLine(x + gap, y, x + s, y, paint)
                canvas.drawLine(x, y - s, x, y - gap, paint); canvas.drawLine(x, y + gap, x, y + s, paint)
                if (st.crossStyle == 3) { paint.style = Paint.Style.FILL; canvas.drawCircle(x, y, s * 0.08f, paint) }
            }
        }
    }
}
