package com.mapmouse.pro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mapmouse.pro.model.Control
import com.mapmouse.pro.model.ControlType
import com.mapmouse.pro.model.CursorShowPos
import com.mapmouse.pro.model.Edge
import com.mapmouse.pro.model.Keys
import com.mapmouse.pro.model.MacroTrigger
import com.mapmouse.pro.model.Profile
import com.mapmouse.pro.model.TapMode

/**
 * Full-screen settings for one button: a sidebar of control kinds on the left (changing it
 * converts the button), and that kind's options on the right.
 */
@SuppressLint("ViewConstructor")
class ControlSettingsView(
    context: Context,
    private val profile: Profile,
    private val c: Control,
    private val listen: (Button, (Int) -> Unit) -> Unit,
    private val macroOps: (LinearLayout) -> Unit,
    private val stages: (LinearLayout) -> Unit,
    private val onDelete: () -> Unit,
    private val onDuplicate: () -> Unit,
    private val onDone: () -> Unit,
) : LinearLayout(context) {

    private enum class Kind(val label: String, val type: ControlType) {
        TOUCH("Touch", ControlType.TAP),
        SWIPE("Swipe", ControlType.SWIPE),
        SCROLL("Mouse Scroll", ControlType.SCROLL),
        MACRO("Macro", ControlType.MACRO),
        JOYSTICK("Joystick", ControlType.JOYSTICK),
        VIEW("FPS View", ControlType.VIEW),
        FREE_LOOK("Free Look", ControlType.FREE_LOOK),
        LONG_PRESS("Long press group", ControlType.LONG_PRESS_GROUP),
        GROUP("Trigger group", ControlType.GROUP),
    }

    private fun kindOf(t: ControlType) = when (t) {
        ControlType.TAP, ControlType.CRAZY_TAP, ControlType.REPEAT -> Kind.TOUCH
        else -> Kind.entries.first { it.type == t }
    }

    private val sidebar = LinearLayout(context).apply { orientation = VERTICAL }
    private val content = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(24f), dp(8f), dp(24f), dp(24f)) }

    init {
        orientation = VERTICAL
        isClickable = true // swallow touches meant for the editor underneath
        setBackgroundColor(0xFF4646C9.toInt())
        // Title bar with Done.
        addView(FrameLayout(context).apply {
            setBackgroundColor(Pal.ACCENT)
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            addView(TextView(context).apply {
                text = "Button settings"; textSize = 18f; setTextColor(Color.WHITE)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
            addView(TextView(context).apply {
                text = "Done"; textSize = 18f; setTextColor(Color.WHITE); paint.isFakeBoldText = true
                setPadding(dp(16f), dp(6f), 0, dp(6f))
                setOnClickListener { onDone() }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        }, LayoutParams(-1, -2))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(ScrollView(context).apply {
                setBackgroundColor(Pal.ACCENT)
                addView(sidebar)
            }, LayoutParams(dp(180f), -1))
            addView(ScrollView(context).apply { addView(content) }, LayoutParams(0, -1, 1f))
        }, LayoutParams(-1, 0, 1f))
        rebuild()
    }

    private fun rebuild() {
        buildSidebar()
        content.removeAllViews()
        build()
    }

    private fun buildSidebar() {
        sidebar.removeAllViews()
        val current = kindOf(c.type)
        for (k in Kind.entries) {
            sidebar.addView(TextView(context).apply {
                text = k.label; textSize = 17f; gravity = Gravity.CENTER
                setPadding(dp(8f), dp(20f), dp(8f), dp(20f))
                val on = k == current
                setTextColor(if (on) Pal.ACCENT else Color.WHITE)
                setBackgroundColor(if (on) 0xFFE3E5FF.toInt() else Color.TRANSPARENT)
                setOnClickListener { convert(k) }
            }, LayoutParams(-1, -2))
        }
    }

    /** Turns the button into another kind, keeping its key and position. */
    private fun convert(k: Kind) {
        if (k == kindOf(c.type)) return
        if ((k == Kind.VIEW || k == Kind.SCROLL) && profile.controls.any { it !== c && it.type == k.type }) {
            Toast.makeText(context, "You can only add one ${k.label}", Toast.LENGTH_SHORT).show()
            return
        }
        c.type = k.type
        when (k) {
            Kind.JOYSTICK -> if (c.keyUp == Keys.NONE) { c.keyUp = Keys.KEY_W; c.keyLeft = Keys.KEY_A; c.keyDown = Keys.KEY_S; c.keyRight = Keys.KEY_D }
            Kind.VIEW, Kind.FREE_LOOK -> c.radius = 0.2f
            Kind.SCROLL -> { c.radius = 0.25f; c.duration = 400 }
            else -> {}
        }
        rebuild()
    }

    // ---- building blocks ----

    private fun dashed(): View = View(context).apply {
        background = ShapeDrawable(RectShape()).apply {
            paint.color = 0x99FFFFFF.toInt(); paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f).toFloat(); paint.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        layoutParams = LayoutParams(-1, dp(1f))
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun help(text: String): View = TextView(context).apply {
        this.text = "?"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Pal.ACCENT); paint.isFakeBoldText = true
        background = roundBg(Color.WHITE, dp(13f).toFloat())
        layoutParams = LayoutParams(dp(26f), dp(26f)).apply { marginStart = dp(12f) }
        setOnClickListener { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    /** A row: title, then a control, then an optional "?" on the far right; dashed line below. */
    private fun row(title: String, control: View? = null, helpText: String? = null) {
        content.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
            setPadding(0, dp(6f), 0, dp(6f))
            addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(Color.WHITE); minWidth = dp(150f) })
            if (control != null) addView(control, LayoutParams(-2, -2).apply { marginStart = dp(12f) })
            addView(View(context), LayoutParams(0, 1, 1f))
            if (helpText != null) addView(help(helpText))
        })
        content.addView(dashed())
    }

    private fun intro(text: String) {
        content.addView(TextView(context).apply { this.text = "Introduction"; textSize = 19f; setTextColor(Color.WHITE); setPadding(0, dp(8f), 0, dp(4f)) })
        content.addView(TextView(context).apply { this.text = text; textSize = 15f; setTextColor(0xEEFFFFFF.toInt()); setPadding(0, 0, 0, dp(10f)) })
        content.addView(dashed())
    }

    /** "Hotkey: X  [Edit]" — Edit waits for the next key, mouse button or wheel step. */
    private fun hotkey(title: String, get: () -> Int, set: (Int) -> Unit, helpText: String? = null) {
        val label = TextView(context).apply { text = "$title: ${Keys.name(get())}"; textSize = 16f; setTextColor(Color.WHITE) }
        val edit = Button(context).apply {
            text = "Edit"; isAllCaps = false; textSize = 15f; setTextColor(Pal.ACCENT)
            background = roundBg(Color.WHITE, dp(6f).toFloat()); stateListAnimator = null
            minWidth = dp(90f); minimumWidth = dp(90f); minHeight = 0; minimumHeight = 0
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        fun reset() { edit.text = "Edit"; edit.background = roundBg(Color.WHITE, dp(6f).toFloat()); edit.setTextColor(Pal.ACCENT) }
        edit.setOnClickListener {
            listen(edit) { code -> set(code); label.text = "$title: ${Keys.name(code)}"; reset() }
            edit.setTextColor(Color.WHITE)
        }
        edit.setOnLongClickListener { set(Keys.NONE); label.text = "$title: ${Keys.name(Keys.NONE)}"; true }
        content.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f); setPadding(0, dp(6f), 0, dp(6f))
            addView(label)
            addView(edit, LayoutParams(-2, -2).apply { marginStart = dp(12f) })
            addView(View(context), LayoutParams(0, 1, 1f))
            if (helpText != null) addView(help(helpText))
        })
        content.addView(dashed())
    }

    private fun seg(options: List<String>, selected: Int, onSelect: (Int) -> Unit) = context.ggSegmented(options, selected, onSelect)
    private fun step(min: Float, max: Float, step: Float, value: Float, fmt: (Float) -> String = { "%.1f".format(it) }, set: (Float) -> Unit) =
        context.ggStepper(min, max, step, value, fmt, set)
    private fun sw(on: Boolean, set: (Boolean) -> Unit) = context.ggSwitch(on, set)
    private fun ms(v: Float) = "${v.toInt()}"

    private fun nameAndGroup() {
        row("Name", EditText(context).apply {
            setText(c.name); setTextColor(Color.WHITE); textSize = 15f; isSingleLine = true; minWidth = dp(200f)
            hint = "Optional"; setHintTextColor(0x88FFFFFF.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { c.name = e.toString() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        })
        if (c.type != ControlType.GROUP && c.type != ControlType.VIEW && c.type != ControlType.SCROLL)
            row("Trigger group", seg(listOf("Base", "G1", "G2", "G3"), c.group) { c.group = it },
                "Buttons in G1–G3 only work while that group's key is held (see Trigger group)")
    }

    private fun footer() {
        content.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(16f), 0, 0)
            addView(context.primaryButton("Duplicate") { onDuplicate() }.apply { background = roundBg(0xFF7B86FF.toInt(), dp(10f).toFloat()) })
            addView(context.primaryButton("Delete") { onDelete() }.apply { background = roundBg(0xFFF2453D.toInt(), dp(10f).toFloat()) })
        })
    }

    // ---- pages ----

    private fun build() {
        when (kindOf(c.type)) {
            Kind.TOUCH -> touch()
            Kind.SWIPE -> swipe()
            Kind.SCROLL -> scroll()
            Kind.MACRO -> macro()
            Kind.JOYSTICK -> joystick()
            Kind.VIEW -> fpsView()
            Kind.FREE_LOOK -> freeLook()
            Kind.LONG_PRESS -> longPress()
            Kind.GROUP -> group()
        }
        footer()
    }



    private fun touch() {
        intro("Simulates screen touching with different types to choose from")
        hotkey("Hotkey", { c.key }, { c.key = it })
        val sel = touchTypes.indexOfFirst { it.second == c.type && (c.type != ControlType.TAP || it.third == c.tapMode) }.coerceAtLeast(0)
        row("Types", seg(touchTypes.map { it.first }, sel) { i ->
            val (_, t, m) = touchTypes[i]
            c.type = t; c.tapMode = m
            if (t == ControlType.CRAZY_TAP && c.duration > 150) c.duration = 50
            if (t == ControlType.REPEAT && c.duration < 40) c.duration = 100
            rebuild()
        }, "Press: hold while the key is held. Tap: one tap. Crazy Tap: very fast repeated taps while held. " +
            "Down/Up Tap: a tap on press and on release. Up Trigger: tap on release. Long Press: holds after a delay. Rapid Tap: repeats while held.")
        val showDuration = c.type != ControlType.TAP || c.tapMode != TapMode.HOLD
        if (showDuration) row("Tap Duration(ms)", step(5f, 500f, 5f, c.tapDuration.toFloat(), ::ms) { c.tapDuration = it.toInt() },
            "How long each simulated finger stays down")
        if (c.type == ControlType.CRAZY_TAP) {
            row("Tap Interval(ms)", step(5f, 300f, 1f, c.duration.toFloat(), ::ms) { c.duration = it.toInt() },
                "Time between taps. With a duration longer than the interval, fingers overlap for the fastest rate")
            row("Conditional Crazy Tap", sw(c.conditional) { c.conditional = it },
                "Only crazy-tap with recoil slots that have \"Crazy Tap With This Slot\" on; otherwise it presses normally")
        }
        if (c.type == ControlType.REPEAT)
            row("Tap Interval(ms)", step(20f, 1000f, 10f, c.duration.toFloat(), ::ms) { c.duration = it.toInt() })
        if (c.type == ControlType.TAP && c.tapMode == TapMode.LONG_PRESS)
            row("Long Press Threshold(ms)", step(100f, 2000f, 50f, c.longPressMs.toFloat(), ::ms) { c.longPressMs = it.toInt() })
        row("Reset Controlling View Finger", sw(c.resetView) { c.resetView = it },
            "Lifts and re-presses the camera finger when this key is pressed (fixes a stuck view after getting in/out of vehicles)")
        row("Reset Joystick", sw(c.resetJoystick) { c.resetJoystick = it },
            "Re-presses the movement joystick when this key is pressed (fixes not moving after vehicles)")
        row("Trigger Recoil Control", sw(c.triggersRecoil) { c.triggersRecoil = it },
            "Recoil control pulls the aim while this button is held")
        row("Switch Mouse Display State", sw(c.cursorToggle) { c.cursorToggle = it; rebuild() },
            "Pressing this also shows/hides the mouse pointer (e.g. for the backpack or map)")
        if (c.cursorToggle) {
            row("Switch Mouse Display State Trigger Type", seg(listOf("Key Down", "Key Up", "Down/Up"), c.cursorEdge.ordinal) {
                c.cursorEdge = Edge.entries[it] })
            row("Mouse Display Position", seg(CursorShowPos.entries.map { it.label }, profile.cursorShowPos.ordinal) {
                profile.cursorShowPos = CursorShowPos.entries[it] }, "Custom: drag the \"pointer\" marker in the editor")
        }
        nameAndGroup()
    }

    private fun swipe() {
        intro("Swipe Simulation")
        hotkey("Hotkey", { c.key }, { c.key = it })
        row("Type", seg(listOf("Single Way", "Round Way"), if (c.roundTrip) 1 else 0) { c.roundTrip = it == 1 },
            "Single Way: swipe from the start to the end point. Round Way: there and back")
        row("Swipe Duration(ms)", step(30f, 2000f, 10f, c.duration.toFloat(), ::ms) { c.duration = it.toInt() })
        row("From Pointer", sw(c.fromPointer) { c.fromPointer = it }, "Start the swipe at the mouse pointer when it's shown")
        row("Horizontal Swiping", sw(c.horizontalOnly) { c.horizontalOnly = it },
            "Only swipe horizontally, not to the exact end point")
        row("Tap before swipe", sw(c.tapBefore) { c.tapBefore = it })
        row("Tap after swipe", sw(c.tapAfter) { c.tapAfter = it })
        nameAndGroup()
    }

    private fun scroll() {
        intro("Roll the mouse wheel to simulate a finger dragging on the screen. When the mouse pointer is shown, " +
            "the drag starts at the pointer; otherwise at this button")
        row("Length", step(0.05f, 0.6f, 0.01f, c.radius, { "%.2f".format(it) }) { c.radius = it })
        row("Speed", step(0.2f, 5f, 0.1f, c.sensY) { c.sensY = it })
        row("Invert Y-Axis", sw(c.invert) { c.invert = it })
        row("Auto Release Delay(ms)", step(50f, 2000f, 50f, c.duration.toFloat(), ::ms) { c.duration = it.toInt() })
        nameAndGroup()
    }

    private fun macro() {
        intro("A macro presses several touches in sequence when you press the hotkey")
        hotkey("Macro Trigger Hotkey", { c.key }, { c.key = it })
        hotkey("Terminate Hotkey", { c.terminateKey }, { c.terminateKey = it }, "Stops this macro while it runs")
        row("Type", seg(listOf("Key Down", "Key Up", "Loop", "Down/Up"), c.trigger.ordinal) { c.trigger = MacroTrigger.entries[it] },
            "Key Down: run on press. Key Up: on release. Loop: repeat while held. Down/Up: on press and on release")
        row("Termination Type", seg(listOf("Terminate Only", "Terminate+Disable"), if (c.disableOnTerminate) 1 else 0) {
            c.disableOnTerminate = it == 1 }, "Terminate+Disable: the terminate key also switches the macro off until pressed again")
        content.addView(TextView(context).apply {
            text = "Macro Operations (after Done, drag the numbered dots to place them)"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(0, dp(12f), 0, dp(4f))
        })
        val ops = LinearLayout(context).apply { orientation = VERTICAL }
        macroOps(ops)
        content.addView(ops)
        content.addView(dashed())
        nameAndGroup()
    }

    private fun joystick() {
        intro("Joystick Settings: drag the white dot on the editor to change the radius")
        val wasd = c.keyUp == Keys.KEY_W && c.keyLeft == Keys.KEY_A && c.keyDown == Keys.KEY_S && c.keyRight == Keys.KEY_D
        val arrows = c.keyUp == 103 && c.keyLeft == 105 && c.keyDown == 108 && c.keyRight == 106
        row("Control Type", seg(listOf("WASD", "Arrows", "Customized"), if (wasd) 0 else if (arrows) 1 else 2) {
            when (it) {
                0 -> { c.keyUp = Keys.KEY_W; c.keyLeft = Keys.KEY_A; c.keyDown = Keys.KEY_S; c.keyRight = Keys.KEY_D }
                1 -> { c.keyUp = 103; c.keyLeft = 105; c.keyDown = 108; c.keyRight = 106 }
            }
            rebuild()
        })
        hotkey("Forward", { c.keyUp }, { c.keyUp = it })
        hotkey("Left", { c.keyLeft }, { c.keyLeft = it })
        hotkey("Backward", { c.keyDown }, { c.keyDown = it })
        hotkey("Right", { c.keyRight }, { c.keyRight = it })
        row("Radius", step(0.03f, 0.4f, 0.01f, c.radius, { "%.2f".format(it) }) { c.radius = it },
            "The movement radius of the joystick")
        val mode = if (c.altKey == Keys.NONE) 0 else if (c.altToggle) 2 else 1
        row("Switch Radius Mode", seg(listOf("No Switch", "Long Press", "Click"), mode) {
            when (it) { 0 -> c.altKey = Keys.NONE; 1 -> c.altToggle = false; 2 -> c.altToggle = true }
            if (it != 0 && c.altKey == Keys.NONE) c.altKey = Keys.KEY_LEFTSHIFT
            rebuild()
        }, "Long Press: smaller radius (e.g. walk) while the key is held. Click: press to switch")
        if (mode != 0) {
            hotkey("Switch Radius Hotkey", { c.altKey }, { c.altKey = it })
            row("Switch Percentage", step(10f, 100f, 5f, c.altPercent * 100, ::ms) { c.altPercent = it / 100 })
        }
        row("Total Movement Step", step(1f, 10f, 1f, c.steps.toFloat(), ::ms) { c.steps = it.toInt() },
            "Intermediate moves each time the joystick changes direction. Try changing it if the joystick sticks")
        nameAndGroup()
    }

    private fun fpsView() {
        intro("According to the movement of the mouse, simulate a finger to control the view of the game. " +
            "The display hotkey locks the mouse (camera) or frees it (pointer for menus)")
        hotkey("Display Hotkey", { profile.cursorKey }, { profile.cursorKey = it },
            "Locks/unlocks the mouse: locked = the mouse turns the camera, unlocked = a pointer for tapping menus")
        row("Display Mode", seg(listOf("Click (toggle)", "Long Press"), if (profile.cursorHold) 1 else 0) {
            profile.cursorHold = it == 1 }, "Click: press once to switch. Long Press: the pointer shows only while the key is held")
        row("Display Position", seg(CursorShowPos.entries.map { it.label }, profile.cursorShowPos.ordinal) {
            profile.cursorShowPos = CursorShowPos.entries[it] }, "Where the pointer appears when the mouse is unlocked")
        row("Horizontal Sensitivity", step(0.1f, 5f, 0.05f, c.sensX, { "%.2f".format(it) }) { c.sensX = it })
        row("Vertical Sensitivity", step(0.1f, 5f, 0.05f, c.sensY, { "%.2f".format(it) }) { c.sensY = it })
        row("View Range", step(0.05f, 0.6f, 0.01f, c.radius, { "%.2f".format(it) }) { c.radius = it },
            "How far the finger moves before it is lifted and re-centered")
        val mode = if (c.altSensKey == Keys.NONE) 0 else if (c.altToggle) 2 else 1
        row("Switch Sensitivity Hotkey", seg(listOf("No Switch", "Long Press", "Click"), mode) {
            when (it) { 0 -> c.altSensKey = Keys.NONE; 1 -> c.altToggle = false; 2 -> c.altToggle = true }
            if (it != 0 && c.altSensKey == Keys.NONE) c.altSensKey = Keys.BTN_RIGHT
            rebuild()
        }, "Use a second sensitivity while aiming / scoped")
        if (mode != 0) {
            hotkey("Hotkey", { c.altSensKey }, { c.altSensKey = it })
            row("Horizontal Sensitivity (Switched)", step(0.1f, 5f, 0.05f, c.altSensX, { "%.2f".format(it) }) { c.altSensX = it })
            row("Vertical Sensitivity (Switched)", step(0.1f, 5f, 0.05f, c.altSensY, { "%.2f".format(it) }) { c.altSensY = it })
        }
        hotkey("Reset View Hotkey", { c.resetKey }, { c.resetKey = it }, "Lifts and re-centers the view finger")
        row("Only Release When Reach Edge", sw(c.edgeReleaseOnly) { c.edgeReleaseOnly = it; rebuild() })
        if (!c.edgeReleaseOnly)
            row("Non-edge Release Time Interval (ms)", step(50f, 2000f, 50f, c.idleReleaseMs.toFloat(), ::ms) { c.idleReleaseMs = it.toInt() })
        row("Enable Mouse Acceleration", sw(c.accel) { c.accel = it; rebuild() })
        if (c.accel) {
            row("Acceleration Coefficient", step(0.05f, 3f, 0.05f, c.accelCoef, { "%.2f".format(it) }) { c.accelCoef = it })
            row("Minimum Effective Speed", step(0f, 30f, 1f, c.accelMinSpeed, ::ms) { c.accelMinSpeed = it })
            row("Acceleration Limit", step(1f, 6f, 0.1f, c.accelLimit) { c.accelLimit = it })
        }
    }

    private fun freeLook() {
        intro("Align this with the game's Free Look (eye) button. Hold the hotkey and move the mouse to look around")
        hotkey("Hotkey", { c.key }, { c.key = it })
        row("Horizontal Sensitivity", step(0.1f, 5f, 0.05f, c.sensX, { "%.2f".format(it) }) { c.sensX = it })
        row("Vertical Sensitivity", step(0.1f, 5f, 0.05f, c.sensY, { "%.2f".format(it) }) { c.sensY = it })
        row("Range", step(0.05f, 0.6f, 0.01f, c.radius, { "%.2f".format(it) }) { c.radius = it })
        row("Reset on edge", sw(c.edgeReleaseOnly) { c.edgeReleaseOnly = it })
        nameAndGroup()
    }

    private fun longPress() {
        intro("Hold the hotkey: each stage is pressed when its time is reached. A short press taps this button")
        hotkey("Hotkey", { c.key }, { c.key = it })
        row("Terminate mode", seg(listOf("Terminate previous one when triggered", "At the same time"), if (c.lastOnly) 0 else 1) {
            c.lastOnly = it == 0 })
        row("Short press tap duration(ms)", step(10f, 500f, 10f, c.tapDuration.toFloat(), ::ms) { c.tapDuration = it.toInt() })
        content.addView(TextView(context).apply {
            text = "Stages (after Done, drag the numbered dots to place them)"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(0, dp(12f), 0, dp(4f))
        })
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        stages(list)
        content.addView(list)
        content.addView(dashed())
        nameAndGroup()
    }

    private fun group() {
        intro("While this key is held, buttons assigned to the chosen group replace base buttons that use the same keys")
        hotkey("Hotkey", { c.key }, { c.key = it })
        row("Activates", seg(listOf("G1", "G2", "G3"), c.targetGroup - 1) { c.targetGroup = it + 1 })
        nameAndGroup()
    }

    companion object {
        /** Touch types: (label, control type, tap mode). Static: the page is built from init, before instance properties declared below it exist. */
        private val touchTypes = listOf(
            Triple("Press", ControlType.TAP, TapMode.HOLD),
            Triple("Tap", ControlType.TAP, TapMode.TAP),
            Triple("Crazy Tap", ControlType.CRAZY_TAP, TapMode.HOLD),
            Triple("Down/Up Tap", ControlType.TAP, TapMode.DOWN_UP),
            Triple("Up Trigger", ControlType.TAP, TapMode.UP),
            Triple("Long Press", ControlType.TAP, TapMode.LONG_PRESS),
            Triple("Rapid Tap", ControlType.REPEAT, TapMode.HOLD),
        )
    }
}
