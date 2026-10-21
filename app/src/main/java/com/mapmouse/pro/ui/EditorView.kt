package com.mapmouse.pro.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.mapmouse.pro.model.Control
import com.mapmouse.pro.model.ControlType
import com.mapmouse.pro.model.CursorShowPos
import com.mapmouse.pro.model.Edge
import com.mapmouse.pro.model.Keys
import com.mapmouse.pro.model.MacroOp
import com.mapmouse.pro.model.MacroTrigger
import com.mapmouse.pro.model.OpType
import com.mapmouse.pro.model.Profile
import com.mapmouse.pro.model.TapMode
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Full-screen overlay drawn on top of the game for placing and binding controls.
 * Controls are dragged with a finger (or the mouse, which is released while editing);
 * key binding listens to the next key, mouse button or wheel step.
 */
@SuppressLint("ViewConstructor")
class EditorView(
    context: Context,
    private val profile: Profile,
    private val onClose: (save: Boolean) -> Unit,
) : FrameLayout(context) {

    private var selected: Control? = null
    private var listening: ((Int) -> Unit)? = null
    private var listenButton: Button? = null

    private val loc = IntArray(2)
    private var screenW = 1f
    private var screenH = 1f

    private val topBar = LinearLayout(context)
    private val panelScroll = ScrollView(context)
    private val panel = LinearLayout(context)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(13f).toFloat(); isFakeBoldText = true
    }

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0x22000000)

        // Toolbar: icon + label buttons on an indigo bar at the top.
        fun tool(icon: Icon, label: String, onClick: () -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
            addView(IconView(context, icon, Color.WHITE, 28f))
            addView(android.widget.TextView(context).apply {
                text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            })
            setOnClickListener { onClick() }
        }
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.background = roundBg(Pal.OVERLAY, dp(16f).toFloat())
        topBar.setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        topBar.addView(tool(Icon.EXIT, "Exit") { onClose(false) })
        topBar.addView(tool(Icon.PLUS, "Add") { showAddMenu() })
        topBar.addView(tool(Icon.SETTINGS, "Settings") { select(null); showProfileSettings() })
        topBar.addView(tool(Icon.RESET, "Reset") { resetToSaved() })
        topBar.addView(tool(Icon.SAVE, "Save") { onClose(true) })
        addView(topBar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        panelScroll.background = roundBg(0xF0484FCF.toInt(), dp(14f).toFloat())
        panelScroll.addView(panel)
        panelScroll.visibility = GONE
        addView(panelScroll, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8f) })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = context.realScreenSize()
        screenW = s.x.toFloat(); screenH = s.y.toFloat()
    }

    // ---- coordinates: model is normalized to the real screen, view may be offset ----

    private fun sx(nx: Float) = nx * screenW - loc[0]
    private fun sy(ny: Float) = ny * screenH - loc[1]
    private fun nx(viewX: Float) = ((viewX + loc[0]) / screenW).coerceIn(0f, 1f)
    private fun ny(viewY: Float) = ((viewY + loc[1]) / screenH).coerceIn(0f, 1f)

    // ---- drawing ----


    private fun hasStages(c: Control?) = c?.type == ControlType.MACRO || c?.type == ControlType.LONG_PRESS_GROUP
    private fun hasRadius(c: Control) = c.type == ControlType.JOYSTICK || c.type == ControlType.VIEW ||
        c.type == ControlType.FREE_LOOK || c.type == ControlType.SCROLL

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(loc)
        val btnR = dp(22f).toFloat()
        for (c in profile.controls) {
            val x = sx(c.x); val y = sy(c.y)
            val sel = c === selected
            when (c.type) {
                ControlType.JOYSTICK -> {
                    val r = c.radius * screenH
                    ring(canvas, x, y, r, 0x22000000)
                    // Four arrows pointing out, each labeled with its key.
                    val dirs = listOf(Triple(0f, -1f, c.keyUp), Triple(0f, 1f, c.keyDown), Triple(-1f, 0f, c.keyLeft), Triple(1f, 0f, c.keyRight))
                    for ((dx, dy, k) in dirs) {
                        val tipX = x + dx * r; val tipY = y + dy * r
                        val baseX = x + dx * r * 0.45f; val baseY = y + dy * r * 0.45f
                        val half = r * 0.28f
                        val path = android.graphics.Path().apply {
                            moveTo(tipX, tipY)
                            lineTo(baseX + dy * half, baseY + dx * half)
                            lineTo(baseX - dy * half, baseY - dx * half)
                            close()
                        }
                        paint.style = Paint.Style.FILL; paint.color = 0x553F4BE0
                        canvas.drawPath(path, paint)
                        paint.style = Paint.Style.STROKE; paint.color = Pal.ACCENT; paint.strokeWidth = dp(2.5f).toFloat()
                        canvas.drawPath(path, paint)
                        fitLabel(canvas, Keys.name(k), x + dx * r * 0.62f, y + dy * r * 0.62f, r * 0.4f)
                    }
                    paint.style = Paint.Style.STROKE; paint.color = Pal.ACCENT
                    canvas.drawCircle(x, y, btnR * 0.45f, paint)
                    canvas.drawLine(x - btnR * 0.7f, y, x + btnR * 0.7f, y, paint)
                    canvas.drawLine(x, y - btnR * 0.7f, x, y + btnR * 0.7f, paint)
                    drawHandle(canvas, x + r, y)
                }
                ControlType.VIEW -> {
                    ring(canvas, x, y, c.radius * screenH, 0x183F4BE0)
                    keyCircle(canvas, x, y, btnR * 1.25f, "View", filled = true)
                    drawHandle(canvas, x + c.radius * screenH, y)
                }
                ControlType.FREE_LOOK, ControlType.SCROLL -> {
                    ring(canvas, x, y, c.radius * screenH, 0x183F4BE0)
                    keyCircle(canvas, x, y, btnR * 1.1f,
                        if (c.type == ControlType.SCROLL) "Scroll" else "Eye\n${Keys.name(c.key)}")
                    drawHandle(canvas, x + c.radius * screenH, y)
                }
                ControlType.CRAZY_TAP -> {
                    val x2 = sx(c.x2); val y2 = sy(c.y2)
                    keyCircle(canvas, x2, y2, btnR * 0.5f, "2")
                    keyCircle(canvas, x, y, btnR, "⚡" + Keys.name(c.key))
                }
                ControlType.SWIPE -> {
                    val x2 = sx(c.x2); val y2 = sy(c.y2)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3f).toFloat(); paint.color = Pal.ACCENT
                    canvas.drawLine(x, y, x2, y2, paint)
                    keyCircle(canvas, x, y, btnR, Keys.name(c.key))
                    drawHandle(canvas, x2, y2)
                }
                else -> {
                    val prefix = when (c.type) {
                        ControlType.REPEAT -> "⟳"
                        ControlType.MACRO -> "M\n"
                        ControlType.GROUP -> "G${c.targetGroup}\n"
                        ControlType.LONG_PRESS_GROUP -> "LP\n"
                        else -> ""
                    }
                    keyCircle(canvas, x, y, btnR, prefix + Keys.name(c.key))
                }
            }
            if (c.name.isNotBlank() && c.type != ControlType.JOYSTICK) fitLabel(canvas, c.name, x, y + btnR + text.textSize, btnR * 4)
            if (c.group != 0) fitLabel(canvas, "G${c.group}", x, y - btnR - dp(6f), btnR * 2)
            if (sel) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3f).toFloat(); paint.color = Color.WHITE
                canvas.drawCircle(x, y, btnR + dp(5f), paint)
            }
        }
        // Custom pointer position, when that option is chosen.
        if (profile.cursorShowPos == CursorShowPos.CUSTOM) {
            paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = dp(2f).toFloat()
            canvas.drawCircle(sx(profile.cursorX), sy(profile.cursorY), dp(12f).toFloat(), paint)
            drawLabel(canvas, "pointer", sx(profile.cursorX), sy(profile.cursorY) - dp(16f))
        }
        // Macro operations / long-press stages of the selected control, numbered in order.
        val m = selected?.takeIf { hasStages(it) } ?: return
        m.ops.forEachIndexed { i, op ->
            if (!op.hasPoint) return@forEachIndexed
            val x = sx(op.x); val y = sy(op.y)
            paint.style = Paint.Style.FILL; paint.color = 0xDDFFFFFF.toInt()
            if (op.type == OpType.SWIPE) {
                paint.strokeWidth = dp(3f).toFloat()
                canvas.drawLine(x, y, sx(op.x2), sy(op.y2), paint)
                drawHandle(canvas, sx(op.x2), sy(op.y2))
            }
            canvas.drawCircle(x, y, dp(14f).toFloat(), paint)
            text.color = Color.BLACK
            canvas.drawText("${i + 1}", x, y + text.textSize / 3, text)
            text.color = Color.WHITE
        }
    }

    /** Explicit so calls inside panel-builder lambdas don't hit the panel's invalidate(). */
    private fun redraw() = invalidate()

    private fun drawLabel(canvas: Canvas, s: String, x: Float, y: Float) = canvas.drawText(s, x, y, text)

    /** Key button: dark see-through disc with an indigo ring (or filled indigo) and the label. */
    private fun keyCircle(canvas: Canvas, x: Float, y: Float, r: Float, label: String, filled: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = if (filled) Pal.ACCENT else 0x77000000
        canvas.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2.5f).toFloat(); paint.color = Pal.ACCENT
        canvas.drawCircle(x, y, r, paint)
        val lines = label.split("\n")
        val lh = text.textSize * 1.05f
        lines.forEachIndexed { i, l ->
            fitLabel(canvas, l, x, y + text.textSize / 3 + (i - (lines.size - 1) / 2f) * lh, r * 1.7f)
        }
    }

    /** Text centered at (x, y), shrunk to fit [maxWidth]. */
    private fun fitLabel(canvas: Canvas, s: String, x: Float, y: Float, maxWidth: Float) {
        val base = dp(13f).toFloat()
        text.textSize = base
        val w = text.measureText(s)
        if (w > maxWidth && w > 0) text.textSize = base * maxWidth / w
        canvas.drawText(s, x, y, text)
        text.textSize = base
    }

    /** Faint range circle around a control. */
    private fun ring(canvas: Canvas, x: Float, y: Float, r: Float, fill: Int) {
        paint.style = Paint.Style.FILL; paint.color = fill
        canvas.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f).toFloat(); paint.color = 0x885B67F6.toInt()
        canvas.drawCircle(x, y, r, paint)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        canvas.drawCircle(x, y, dp(7f).toFloat(), paint)
    }

    // ---- dragging ----

    private enum class Part { MAIN, RADIUS, END, OP, OP_END, POINTER }
    private var drag: Triple<Control, Part, Int>? = null
    /** Stand-in drag target for the custom pointer position. */
    private val pointerDummy = Control(ControlType.TAP)
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private fun hitTest(x: Float, y: Float): Triple<Control, Part, Int>? {
        val tol = dp(26f).toFloat()
        fun near(px: Float, py: Float) = hypot(x - px, y - py) < tol
        selected?.takeIf { hasStages(it) }?.let { m ->
            m.ops.forEachIndexed { i, op ->
                if (op.type == OpType.SWIPE && near(sx(op.x2), sy(op.y2))) return Triple(m, Part.OP_END, i)
                if (op.hasPoint && near(sx(op.x), sy(op.y))) return Triple(m, Part.OP, i)
            }
        }
        if (profile.cursorShowPos == CursorShowPos.CUSTOM && near(sx(profile.cursorX), sy(profile.cursorY)))
            return Triple(pointerDummy, Part.POINTER, 0)
        for (c in profile.controls.asReversed()) {
            if ((c.type == ControlType.SWIPE || c.type == ControlType.CRAZY_TAP) && near(sx(c.x2), sy(c.y2))) return Triple(c, Part.END, 0)
            if (hasRadius(c) && near(sx(c.x) + c.radius * screenH, sy(c.y))) return Triple(c, Part.RADIUS, 0)
            if (near(sx(c.x), sy(c.y))) return Triple(c, Part.MAIN, 0)
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = hitTest(e.x, e.y)
                downX = e.x; downY = e.y; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val d = drag ?: return true
                if (hypot(e.x - downX, e.y - downY) > dp(4f)) moved = true
                if (!moved) return true
                val (c, part, i) = d
                when (part) {
                    Part.MAIN -> { c.x = nx(e.x); c.y = ny(e.y) }
                    Part.END -> { c.x2 = nx(e.x); c.y2 = ny(e.y) }
                    Part.RADIUS -> c.radius = (hypot(e.x - sx(c.x), e.y - sy(c.y)) / screenH).coerceIn(0.02f, 0.6f)
                    Part.OP -> { c.ops[i].x = nx(e.x); c.ops[i].y = ny(e.y) }
                    Part.OP_END -> { c.ops[i].x2 = nx(e.x); c.ops[i].y2 = ny(e.y) }
                    Part.POINTER -> { profile.cursorX = nx(e.x); profile.cursorY = ny(e.y) }
                }
                redraw()
            }
            MotionEvent.ACTION_UP -> {
                val d = drag
                if (!moved) {
                    if (d == null) select(null)
                    else if (d.second == Part.POINTER) Unit
                    else if (d.second == Part.MAIN) {
                        // A tap on a button opens its settings (key, type, options).
                        select(d.first)
                        openSettings(d.first)
                    } else if (d.first !== selected) select(d.first)
                } else if (d != null && d.second == Part.MAIN) select(d.first)
                drag = null
            }
        }
        return true
    }

    // ---- key listening ----

    private fun listen(button: Button, onKey: (Int) -> Unit) {
        listenButton?.let { it.background = roundBg(0xFF2D3440.toInt(), dp(8f).toFloat()) }
        listenButton = button
        button.background = roundBg(0xFFB04040.toInt(), dp(8f).toFloat())
        button.text = "Press a key…"
        listening = onKey
        requestFocus()
    }

    private fun deliver(code: Int): Boolean {
        val l = listening ?: return false
        listening = null
        listenButton = null
        l(code)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let text fields (control names) receive typing.
        if (listening == null && findFocus() is EditText) return super.dispatchKeyEvent(event)
        if (listening == null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP &&
                !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                when {
                    settingsView != null -> closeSettings()
                    panelScroll.visibility == VISIBLE -> select(null)
                    else -> onClose(false)
                }
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        var code = event.scanCode
        if (code <= 0) {
            // Mouse right-click arrives as a synthetic BACK without a scan code: ignore it here.
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
            code = Keys.fromAndroidKeyCode(event.keyCode)
        }
        if (code > 0) deliver(code)
        return true
    }

    private fun mouseButtonCode(e: MotionEvent): Int {
        val b = if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) e.actionButton else e.buttonState
        return when {
            (b and MotionEvent.BUTTON_PRIMARY) != 0 -> Keys.BTN_LEFT
            (b and MotionEvent.BUTTON_SECONDARY) != 0 -> Keys.BTN_RIGHT
            (b and MotionEvent.BUTTON_TERTIARY) != 0 -> Keys.BTN_MIDDLE
            (b and MotionEvent.BUTTON_BACK) != 0 -> Keys.BTN_SIDE
            (b and MotionEvent.BUTTON_FORWARD) != 0 -> Keys.BTN_EXTRA
            else -> Keys.NONE
        }
    }

    private fun interceptMouse(e: MotionEvent): Boolean {
        if (listening == null || !e.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) deliver(if (v > 0) Keys.WHEEL_UP else Keys.WHEEL_DOWN)
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                val code = mouseButtonCode(e)
                if (code != Keys.NONE) deliver(code)
            }
        }
        return true
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean = interceptMouse(e) || super.dispatchTouchEvent(e)
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean =
        interceptMouse(e) || super.dispatchGenericMotionEvent(e)

    // ---- panels ----

    private var lastTap = 0L

    private fun select(c: Control?) {
        val first = selected == null && c != null
        selected = c
        listening = null
        panelScroll.visibility = GONE
        redraw()
    }

    private fun resetToSaved() {
        val saved = com.mapmouse.pro.Store.profiles(context).firstOrNull { it.id == profile.id } ?: return
        profile.controls.clear()
        profile.controls.addAll(saved.controls)
        select(null)
        Toast.makeText(context, "Back to the last saved layout", Toast.LENGTH_SHORT).show()
    }

    /** Full-screen settings for one button, opened on top of the editor. */
    private var settingsView: View? = null

    private fun openSettings(c: Control) {
        closeSettings()
        val v = ControlSettingsView(context, profile, c,
            listen = { button, onKey -> listen(button, onKey) },
            macroOps = { root -> macroPanel(root, c) },
            stages = { root -> stagesPanel(root, c) },
            onDelete = { profile.controls.remove(c); closeSettings(); select(null) },
            onDuplicate = {
                val copy = c.copy().apply { x = (x + 0.05f).coerceAtMost(1f); y = (y + 0.05f).coerceAtMost(1f) }
                profile.controls += copy; closeSettings(); select(copy)
            },
            onDone = { closeSettings() })
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        settingsView = v
        topBar.visibility = GONE
    }

    private fun closeSettings() {
        settingsView?.let { removeView(it) }
        settingsView = null
        topBar.visibility = VISIBLE
        listening = null
        redraw()
    }

    private fun showPanel(build: LinearLayout.() -> Unit) {
        panel.removeAllViews()
        panel.build()
        panelScroll.visibility = VISIBLE
        // Keep the panel away from the control being edited.
        val lp = panelScroll.layoutParams as LayoutParams
        val gravity = if ((selected?.y ?: 1f) > 0.5f) Gravity.TOP else Gravity.BOTTOM
        lp.gravity = gravity or Gravity.CENTER_HORIZONTAL
        lp.topMargin = if (gravity == Gravity.TOP) dp(56f) else 0
        lp.bottomMargin = dp(8f)
        lp.height = LayoutParams.WRAP_CONTENT
        panelScroll.layoutParams = lp
        post {
            if (panelScroll.height > height * 0.6f) {
                lp.height = (height * 0.6f).toInt(); panelScroll.layoutParams = lp
            }
        }
    }

    private fun keyButton(title: String, get: () -> Int, set: (Int) -> Unit): Button {
        val b = context.chip("$title: ${Keys.name(get())}") {}
        b.setOnClickListener { listen(b) { code -> set(code); b.text = "$title: ${Keys.name(code)}"; restyle(b); redraw() } }
        b.setOnLongClickListener { set(Keys.NONE); b.text = "$title: ${Keys.name(Keys.NONE)}"; redraw(); true }
        return b
    }

    private fun restyle(b: Button) { b.background = roundBg(0xFF2D3440.toInt(), dp(8f).toFloat()) }

    private fun cycleButton(title: String, values: List<String>, index: () -> Int, set: (Int) -> Unit): Button {
        val b = context.chip("$title: ${values[index()]}") {}
        b.setOnClickListener {
            val n = (index() + 1) % values.size
            set(n); b.text = "$title: ${values[n]}"; redraw()
        }
        return b
    }

    private fun scrollRow(vararg views: View) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(context.hrow(*views))
    }

    private fun toggle(title: String, get: () -> Boolean, set: (Boolean) -> Unit, onChange: () -> Unit = {}): Button {
        val b = context.chip("") {}
        fun label() { b.text = (if (get()) "☑ " else "☐ ") + title }
        label()
        b.setOnClickListener { set(!get()); label(); redraw(); onChange() }
        return b
    }

    private fun ms(v: Float) = "${v.toInt()} ms"
    private fun f2(v: Float) = "%.2f".format(v)

    private fun stagesPanel(root: LinearLayout, c: Control) {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun refresh() {
            list.removeAllViews()
            c.ops.forEachIndexed { i, op ->
                list.addView(context.hrow(
                    context.label("${i + 1}. at ${op.duration} ms", 13f, Color.WHITE).apply { minWidth = dp(110f) },
                    context.chip("−") { op.duration = (op.duration - 50).coerceAtLeast(0); refresh() },
                    context.chip("+") { op.duration += 50; refresh() },
                    context.chip("✕") { c.ops.removeAt(i); refresh() },
                ))
            }
            redraw()
        }
        refresh()
        root.addView(list)
        root.addView(context.chip("+ Stage") {
            val last = c.ops.lastOrNull()
            c.ops += MacroOp(OpType.PRESS, x = ((last?.x ?: c.x) + 0.05f).coerceAtMost(1f), y = last?.y ?: c.y,
                duration = (last?.duration ?: 0) + 300)
            refresh()
        })
    }

    private fun macroPanel(root: LinearLayout, c: Control) {
        root.addView(scrollRow(
            cycleButton("Trigger", MacroTrigger.entries.map { it.label }, { c.trigger.ordinal }, { c.trigger = MacroTrigger.entries[it] }),
            keyButton("Terminate key", { c.terminateKey }, { c.terminateKey = it }),
            cycleButton("On terminate", listOf("Stop only", "Stop + disable (press again to enable)"),
                { if (c.disableOnTerminate) 1 else 0 }, { c.disableOnTerminate = it == 1 }),
        ))
        val cm = context.getSystemService(ClipboardManager::class.java)
        root.addView(scrollRow(
            context.chip("Copy share code") {
                cm.setPrimaryClip(ClipData.newPlainText("macro", c.toJson().toString()))
                Toast.makeText(context, "Macro copied", Toast.LENGTH_SHORT).show()
            },
            context.chip("Import from clipboard") {
                val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                runCatching { Control.fromJson(JSONObject(txt!!)) }.onSuccess { m ->
                    c.ops.clear(); c.ops.addAll(m.ops); c.trigger = m.trigger; openSettings(c)
                }.onFailure { Toast.makeText(context, "No macro in clipboard", Toast.LENGTH_SHORT).show() }
            },
        ))
        root.addView(context.label("Operations (numbered dots on screen are draggable):"))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun refresh() {
            list.removeAllViews()
            c.ops.forEachIndexed { i, op ->
                val desc = context.label("${i + 1}. ${op.describe()}", 13f, Color.WHITE).apply { minWidth = dp(130f) }
                val views = mutableListOf<View>(desc)
                when (op.type) {
                    OpType.PRESS, OpType.RELEASE -> views += context.chip("Slot ${op.slot}") { op.slot = (op.slot + 1) % 5; refresh() }
                    OpType.TAP, OpType.SWIPE, OpType.SLEEP -> {
                        views += context.chip("−") { op.duration = (op.duration - if (op.duration > 100) 50 else 10).coerceAtLeast(10); refresh() }
                        views += context.chip("+") { op.duration += if (op.duration >= 100) 50 else 10; refresh() }
                        if (op.type == OpType.SWIPE) views += context.chip(if (op.roundTrip) "Round" else "One way") { op.roundTrip = !op.roundTrip; refresh() }
                    }
                    else -> {}
                }
                views += context.chip("↑") { if (i > 0) { c.ops.add(i - 1, c.ops.removeAt(i)); refresh() } }
                views += context.chip("✕") { c.ops.removeAt(i); refresh() }
                list.addView(context.hrow(*views.toTypedArray()))
            }
            redraw()
        }
        refresh()
        root.addView(list)
        fun add(t: OpType) {
            val last = c.ops.lastOrNull { it.hasPoint }
            val op = MacroOp(t, x = last?.x ?: c.x, y = last?.y ?: (c.y - 0.1f).coerceAtLeast(0f))
            op.x2 = (op.x + 0.1f).coerceAtMost(1f); op.y2 = op.y
            op.duration = when (t) { OpType.SWIPE -> 150; OpType.SLEEP -> 100; else -> 50 }
            if (t == OpType.RELEASE) op.slot = c.ops.lastOrNull { it.type == OpType.PRESS }?.slot ?: 0
            c.ops += op
            refresh()
        }
        OpType.entries.chunked(5).forEach { row ->
            root.addView(scrollRow(*row.map { t -> context.chip("+ ${t.label}") { add(t) } }.toTypedArray()))
        }
    }

    private fun showAddMenu() {
        selected = null
        redraw()
        showPanel {
            addView(context.label("Add a control", 14f, Color.WHITE))
            val buttons = ControlType.entries.map { t ->
                context.chip(t.label) {
                    val single = t == ControlType.VIEW || t == ControlType.SCROLL
                    if (single && profile.controls.any { it.type == t }) {
                        select(profile.controls.first { it.type == t })
                        return@chip
                    }
                    val c = Control(t, 0.5f, 0.5f)
                    when (t) {
                        ControlType.JOYSTICK -> { c.keyUp = Keys.KEY_W; c.keyLeft = Keys.KEY_A; c.keyDown = Keys.KEY_S; c.keyRight = Keys.KEY_D }
                        ControlType.VIEW, ControlType.FREE_LOOK -> c.radius = 0.2f
                        ControlType.SCROLL -> { c.radius = 0.25f; c.duration = 400 }
                        ControlType.REPEAT -> c.duration = 100
                        ControlType.CRAZY_TAP -> { c.duration = 20; c.tapDuration = 40; c.key = Keys.BTN_LEFT; c.x2 = 0.52f; c.y2 = 0.52f }
                        else -> {}
                    }
                    profile.controls += c
                    select(c)
                    openSettings(c)
                }
            }
            buttons.chunked(4).forEach { addView(context.hrow(*it.toTypedArray())) }
            addView(context.chip("Close") { select(null) })
        }
    }

    private fun showProfileSettings(): Unit = showPanel {
        val p = profile
        addView(context.label("Profile: ${p.name}   (long-press a key button to clear)", 14f, Color.WHITE))
        addView(scrollRow(
            keyButton("Mapping on/off", { p.toggleKey }, { p.toggleKey = it }),
            keyButton("Cursor key", { p.cursorKey }, { p.cursorKey = it }),
            keyButton("Stop macros", { p.terminateKey }, { p.terminateKey = it }),
            keyButton("Show HUD", { p.hudKey }, { p.hudKey = it }),
        ))
        addView(scrollRow(
            keyButton("Keyboard typing", { p.typingKey }, { p.typingKey = it }),
            keyButton("Floating button", { p.bubbleKey }, { p.bubbleKey = it }),
            keyButton("Crazy tap on/off", { p.crazyKey }, { p.crazyKey = it }),
        ))
        context.cursorRows(p) {}.forEach { addView(it) }
        addView(scrollRow(cycleButton("Pointer appears at", CursorShowPos.entries.map { it.label },
            { p.cursorShowPos.ordinal }, { p.cursorShowPos = CursorShowPos.entries[it]; redraw() })))
        addView(scrollRow(keyButton("Recoil fire key", { p.recoilKey }, { p.recoilKey = it })))
        context.recoilRows(p) {}.forEach { addView(it) }
        addView(context.label("Weapons, recoil curves, stance and aim options are in the app → Recoil control."))
        addView(context.chip("Close") { select(null) })
    }
}
