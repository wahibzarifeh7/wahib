package com.mapmouse.pro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Simple line icons drawn in code (no image assets needed). */
enum class Icon { GAMEPAD, CONFIGS, GRID, PLUS, EDITOR, SETTINGS, RETURN, KEYBOARD, PLAY, STOP, POWER, MOUSE, CHEVRON, CLOSE, SAVE, RESET, EXIT, TUNE }

class IconView(context: Context, var icon: Icon, var color: Int, private val sizeDp: Float = 26f) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = context.dp(sizeDp)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        p.color = color
        p.strokeWidth = s / 12f
        drawIcon(c, icon, s, p)
    }

    companion object {
        /** Draws [icon] into an s×s box at the canvas origin. */
        fun drawIcon(c: Canvas, icon: Icon, s: Float, p: Paint) {
            val path = Path()
            p.style = Paint.Style.STROKE
            when (icon) {
                Icon.GAMEPAD -> {
                    c.drawRoundRect(RectF(s * .08f, s * .28f, s * .92f, s * .78f), s * .25f, s * .25f, p)
                    c.drawLine(s * .25f, s * .53f, s * .41f, s * .53f, p); c.drawLine(s * .33f, s * .45f, s * .33f, s * .61f, p)
                    p.style = Paint.Style.FILL
                    c.drawCircle(s * .66f, s * .47f, s * .05f, p); c.drawCircle(s * .76f, s * .58f, s * .05f, p)
                }
                Icon.CONFIGS -> { // tray with a down arrow
                    path.moveTo(s * .15f, s * .55f); path.lineTo(s * .15f, s * .85f); path.lineTo(s * .85f, s * .85f); path.lineTo(s * .85f, s * .55f)
                    c.drawPath(path, p)
                    c.drawLine(s * .5f, s * .12f, s * .5f, s * .65f, p)
                    c.drawLine(s * .33f, s * .48f, s * .5f, s * .65f, p); c.drawLine(s * .67f, s * .48f, s * .5f, s * .65f, p)
                }
                Icon.GRID -> {
                    c.drawRect(s * .12f, s * .12f, s * .88f, s * .88f, p)
                    c.drawLine(s * .5f, s * .12f, s * .5f, s * .88f, p); c.drawLine(s * .12f, s * .5f, s * .88f, s * .5f, p)
                }
                Icon.PLUS -> {
                    c.drawCircle(s * .5f, s * .5f, s * .4f, p)
                    c.drawLine(s * .5f, s * .3f, s * .5f, s * .7f, p); c.drawLine(s * .3f, s * .5f, s * .7f, s * .5f, p)
                }
                Icon.EDITOR -> { // pencil over a frame
                    c.drawRoundRect(RectF(s * .12f, s * .2f, s * .72f, s * .88f), s * .06f, s * .06f, p)
                    c.drawLine(s * .42f, s * .62f, s * .88f, s * .16f, p)
                    c.drawLine(s * .42f, s * .62f, s * .38f, s * .7f, p)
                }
                Icon.SETTINGS -> { // gear
                    c.drawCircle(s * .5f, s * .5f, s * .14f, p)
                    c.drawCircle(s * .5f, s * .5f, s * .3f, p)
                    for (i in 0 until 8) {
                        val a = Math.PI * i / 4
                        c.drawLine(s * .5f + (s * .3f * cos(a)).toFloat(), s * .5f + (s * .3f * sin(a)).toFloat(),
                            s * .5f + (s * .42f * cos(a)).toFloat(), s * .5f + (s * .42f * sin(a)).toFloat(), p)
                    }
                }
                Icon.RETURN -> { // curved back arrow
                    path.moveTo(s * .85f, s * .8f)
                    path.cubicTo(s * .85f, s * .45f, s * .6f, s * .38f, s * .2f, s * .38f)
                    c.drawPath(path, p)
                    c.drawLine(s * .2f, s * .38f, s * .38f, s * .2f, p); c.drawLine(s * .2f, s * .38f, s * .38f, s * .56f, p)
                }
                Icon.KEYBOARD -> {
                    c.drawRoundRect(RectF(s * .06f, s * .25f, s * .94f, s * .75f), s * .06f, s * .06f, p)
                    p.style = Paint.Style.FILL
                    for (r in 0..1) for (k in 0..4) c.drawCircle(s * (.2f + k * .15f), s * (.4f + r * .12f), s * .028f, p)
                    p.style = Paint.Style.STROKE
                    c.drawLine(s * .3f, s * .64f, s * .7f, s * .64f, p)
                }
                Icon.PLAY -> {
                    p.style = Paint.Style.FILL
                    path.moveTo(s * .3f, s * .18f); path.lineTo(s * .82f, s * .5f); path.lineTo(s * .3f, s * .82f); path.close()
                    c.drawPath(path, p)
                }
                Icon.STOP -> { p.style = Paint.Style.FILL; c.drawRoundRect(RectF(s * .24f, s * .24f, s * .76f, s * .76f), s * .06f, s * .06f, p) }
                Icon.POWER -> {
                    c.drawArc(RectF(s * .14f, s * .18f, s * .86f, s * .9f), -60f, 300f, false, p)
                    c.drawLine(s * .5f, s * .08f, s * .5f, s * .5f, p)
                }
                Icon.MOUSE -> {
                    c.drawRoundRect(RectF(s * .26f, s * .08f, s * .74f, s * .92f), s * .24f, s * .24f, p)
                    c.drawLine(s * .5f, s * .08f, s * .5f, s * .38f, p)
                    c.drawLine(s * .26f, s * .38f, s * .74f, s * .38f, p)
                }
                Icon.CHEVRON -> {
                    c.drawCircle(s * .5f, s * .5f, s * .42f, p)
                    c.drawLine(s * .42f, s * .3f, s * .62f, s * .5f, p); c.drawLine(s * .62f, s * .5f, s * .42f, s * .7f, p)
                }
                Icon.CLOSE -> {
                    c.drawLine(s * .25f, s * .25f, s * .75f, s * .75f, p); c.drawLine(s * .75f, s * .25f, s * .25f, s * .75f, p)
                }
                Icon.SAVE -> {
                    c.drawRoundRect(RectF(s * .14f, s * .14f, s * .86f, s * .86f), s * .08f, s * .08f, p)
                    c.drawRect(s * .32f, s * .14f, s * .68f, s * .38f, p)
                    c.drawLine(s * .3f, s * .66f, s * .7f, s * .66f, p)
                }
                Icon.RESET -> {
                    c.drawArc(RectF(s * .15f, s * .15f, s * .85f, s * .85f), -80f, 300f, false, p)
                    c.drawLine(s * .56f, s * .06f, s * .5f, s * .16f, p); c.drawLine(s * .5f, s * .16f, s * .62f, s * .24f, p)
                }
                Icon.EXIT -> {
                    path.moveTo(s * .55f, s * .15f); path.lineTo(s * .15f, s * .15f); path.lineTo(s * .15f, s * .85f); path.lineTo(s * .55f, s * .85f)
                    c.drawPath(path, p)
                    c.drawLine(s * .38f, s * .5f, s * .88f, s * .5f, p)
                    c.drawLine(s * .7f, s * .32f, s * .88f, s * .5f, p); c.drawLine(s * .7f, s * .68f, s * .88f, s * .5f, p)
                }
                Icon.TUNE -> {
                    for ((i, k) in listOf(.3f, .65f, .45f).withIndex()) {
                        val y = s * (.25f + i * .25f)
                        c.drawLine(s * .1f, y, s * .9f, y, p)
                        p.style = Paint.Style.FILL; c.drawCircle(s * k, y, s * .08f, p); p.style = Paint.Style.STROKE
                    }
                }
            }
            p.style = Paint.Style.STROKE
        }
    }
}
