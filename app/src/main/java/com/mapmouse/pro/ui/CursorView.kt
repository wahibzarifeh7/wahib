package com.mapmouse.pro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.mapmouse.pro.AppSettings
import java.io.File

/** Full-screen, untouchable overlay that draws the emulated mouse pointer in cursor mode. */
class CursorView(context: Context) : View(context) {
    private var cx = 0f
    private var cy = 0f
    private var shown = false
    private val loc = IntArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var custom: Bitmap? = null
    private var customStamp = 0L

    fun update(x: Int, y: Int, visible: Boolean) {
        cx = x.toFloat(); cy = y.toFloat(); shown = visible
        invalidate()
    }

    private fun customBitmap(): Bitmap? {
        val f = pointerFile(context)
        if (!f.exists()) return null
        if (f.lastModified() != customStamp) {
            custom = BitmapFactory.decodeFile(f.path); customStamp = f.lastModified()
        }
        return custom
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        getLocationOnScreen(loc)
        val st = AppSettings.get(context)
        val s = context.dp(st.pointerSize.toFloat()).toFloat()
        fill.color = st.pointerColor
        stroke.strokeWidth = s / 14f
        canvas.save()
        canvas.translate(cx - loc[0], cy - loc[1])
        when (st.pointerStyle) {
            1 -> { // dot
                canvas.drawCircle(0f, 0f, s * 0.22f, fill); canvas.drawCircle(0f, 0f, s * 0.22f, stroke)
            }
            2 -> { // ring
                fill.style = Paint.Style.STROKE; fill.strokeWidth = s / 8f
                canvas.drawCircle(0f, 0f, s * 0.35f, fill)
                fill.style = Paint.Style.FILL
                canvas.drawCircle(0f, 0f, s * 0.06f, fill)
            }
            3 -> { // cross
                fill.strokeWidth = s / 9f
                canvas.drawLine(-s * 0.45f, 0f, s * 0.45f, 0f, fill)
                canvas.drawLine(0f, -s * 0.45f, 0f, s * 0.45f, fill)
            }
            4 -> { // hollow arrow
                arrow(s); stroke.color = st.pointerColor; stroke.strokeWidth = s / 9f
                canvas.drawPath(path, stroke); stroke.color = Color.BLACK
            }
            5 -> customBitmap()?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, s, s * it.height / it.width), null) }
                ?: run { arrow(s); canvas.drawPath(path, fill); canvas.drawPath(path, stroke) }
            else -> { arrow(s); canvas.drawPath(path, fill); canvas.drawPath(path, stroke) }
        }
        canvas.restore()
    }

    /** Classic arrow pointer with its tip at the touch point. */
    private fun arrow(s: Float) {
        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(0f, s)
        path.lineTo(s * 0.28f, s * 0.75f)
        path.lineTo(s * 0.46f, s * 1.1f)
        path.lineTo(s * 0.6f, s * 1.03f)
        path.lineTo(s * 0.42f, s * 0.7f)
        path.lineTo(s * 0.75f, s * 0.7f)
        path.close()
    }

    companion object {
        fun pointerFile(ctx: Context) = File(ctx.filesDir, "pointer.png")
    }
}
