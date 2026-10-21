package com.mapmouse.pro.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mapmouse.pro.AppSettings
import com.mapmouse.pro.model.CurvePoint
import com.mapmouse.pro.model.CursorShowPos
import com.mapmouse.pro.model.Profile
import com.mapmouse.pro.model.Weapon
import kotlin.math.roundToInt

/** Red round ✕ used to close in-game panels. */
fun Context.closeButton(onClick: () -> Unit): View = FrameLayout(this).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFF2453D.toInt()) }
    addView(IconView(context, Icon.CLOSE, Color.WHITE, 22f), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    layoutParams = LinearLayout.LayoutParams(dp(42f), dp(42f))
    setOnClickListener { onClick() }
}

/**
 * Indigo panel with a title bar ("< Return" on sub-pages, the title centered, a red ✕) and a
 * scrolling body. Returns the root view and the body to fill.
 */
fun Context.indigoPanel(title: String, onClose: () -> Unit, onBack: (() -> Unit)? = null): Pair<View, LinearLayout> {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val bar = FrameLayout(this).apply {
        setPadding(dp(14f), dp(8f), dp(10f), dp(8f))
        addView(TextView(context).apply {
            text = title; textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        if (onBack != null) addView(TextView(context).apply {
            text = "<  Return"; textSize = 17f; setTextColor(Color.WHITE)
            setPadding(dp(4f), dp(8f), dp(12f), dp(8f))
            setOnClickListener { onBack() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        addView(closeButton(onClose), FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.END or Gravity.CENTER_VERTICAL))
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Pal.ACCENT) }
        addView(bar)
        addView(View(context).apply { setBackgroundColor(0x40FFFFFF) }, LinearLayout.LayoutParams(-1, dp(1f)))
        addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    return root to body
}

/** Value shown in the recoil pill and the Adjust panel: strength × 1000 (0.075 → 75). */
fun recoilDisplay(v: Float) = (v * 1000).roundToInt()

/**
 * In-game settings pages (like the app's, but over the game). [save] persists and applies
 * changes; [navigate] swaps the panel to another page.
 */
class InGameSettings(
    private val ctx: Context,
    private val profile: () -> Profile,
    private val saveProfile: () -> Unit,
    private val saveOptions: () -> Unit,
    private val screenAdjust: (() -> View)?,
) {
    private val s get() = AppSettings.get(ctx)

    /** Waits for the next key on the open panel (set by the overlay for each panel). */
    var listen: (android.widget.Button, (Int) -> Unit) -> Unit = { _, _ -> }
    /** Rebuilds the page currently shown. */
    var refresh: () -> Unit = {}
    /** Opens a sub-page (title, builder, page to return to). */
    var navigate: (String, (LinearLayout) -> Unit) -> Unit = { _, _ -> }

    /** "Title: Key   [Edit]" row; Edit waits for a key/mouse button/wheel, long-press clears. */
    private fun hotkeyRow(b: LinearLayout, title: String, sub: String? = null, get: () -> Int, set: (Int) -> Unit) {
        val btn = ctx.editKeyButton()
        btn.setOnClickListener { listen(btn) { code -> set(code); refresh() } }
        btn.setOnLongClickListener { set(com.mapmouse.pro.model.Keys.NONE); refresh(); true }
        b.addView(ctx.ggRow("$title:  ${com.mapmouse.pro.model.Keys.name(get())}", sub, btn))
    }

    fun pages(): List<Pair<String, (LinearLayout) -> Unit>> = listOfNotNull(
        "Hotkeys" to ::hotkeys,
        "Basic Settings" to ::basic,
        "Device Info Monitor Settings" to ::monitor,
        "General Recoil Control Settings" to ::recoil,
        "Crosshair Settings" to ::crosshair,
        screenAdjust?.let { f -> "Screen Size Adjustment Tool Settings" to { b: LinearLayout -> b.addView(f()) } },
        "Gamepad Settings" to ::gamepad,
    )

    private fun basic(b: LinearLayout) = with(ctx) {
        val st = s; val p = profile()
        b.addView(ggHeader("Basic Settings"))
        b.addView(ggSwitchRow("Show Touch Paths", on = st.showTouches) { st.showTouches = it; saveOptions() })
        b.addView(ggStepperRow("Logo Transparency", min = 20f, max = 100f, step = 5f, value = st.bubbleAlpha * 100) {
            st.bubbleAlpha = it / 100; saveOptions() })
        b.addView(ggSwitchRow("Show HUD", "Key labels over the mapped buttons", on = st.showHud) { st.showHud = it; saveOptions() })
        b.addView(ggRow("HUD color", trailing = ggSwatches(SWATCHES, st.hudColor) { st.hudColor = it; saveOptions() }))
        b.addView(ggHeader("Hardware Settings"))
        val rates = listOf(125, 250, 500, 0)
        b.addView(ggSegmentedRow("Mouse Max Polling Rate", "Set this lower if you have a low performance phone",
            listOf("125hz", "250hz", "500hz", "No Limit"), rates.indexOf(st.pollHz).coerceAtLeast(3)) { st.pollHz = rates[it]; saveOptions() })
        val smooth = listOf(0f, 0.3f, 0.5f, 0.7f, 0.8f)
        b.addView(ggSegmentedRow("Mouse Smoothness", options = listOf("Off", "Low", "Medium", "High", "Max"),
            selected = smooth.indexOf(st.smooth).coerceAtLeast(0)) { st.smooth = smooth[it]; saveOptions() })
        b.addView(ggHeader("Mouse Settings"))
        b.addView(ggStepperRow("Mouse Pointer Sensitivity", min = 0.3f, max = 5f, step = 0.1f, value = p.cursorSpeed) {
            p.cursorSpeed = it; saveProfile() })
        b.addView(ggStepperRow("Mouse Pointer Size", min = 12f, max = 64f, step = 1f, value = st.pointerSize.toFloat()) {
            st.pointerSize = it.toInt(); saveOptions() })
        b.addView(ggSegmentedRow("Customized Pointer Style", options = listOf("Style 1", "Style 2", "Style 3", "Style 4", "Style 5", "Custom"),
            selected = st.pointerStyle) { st.pointerStyle = it; saveOptions() })
        b.addView(ggRow("Pointer color", trailing = ggSwatches(SWATCHES, st.pointerColor) { st.pointerColor = it; saveOptions() }))
        b.addView(ggSegmentedRow("Cursor Mode", "Toggle: press to switch. Hold: pointer only while the key is held",
            listOf("Toggle", "Hold"), if (p.cursorHold) 1 else 0) { p.cursorHold = it == 1; saveProfile() })
        b.addView(ggSegmentedRow("Mouse Display Position", options = CursorShowPos.entries.map { it.label },
            selected = p.cursorShowPos.ordinal) { p.cursorShowPos = CursorShowPos.entries[it]; saveProfile() })
        b.addView(ggHeader("Touch"))
        b.addView(ggSwitchRow("Lift Aim Finger When Mouse Stops", "Lets you tap the screen while mapping is on",
            on = st.touchFriendly) { st.touchFriendly = it; saveOptions() })
        b.addView(ggSwitchRow("Touch Compatible Mode", "Finger touches will work with mouse and keyboard at same time",
            on = st.touchCompat) { st.touchCompat = it; saveOptions() })
        b.addView(ggSwitchRow("Pause & Hide Outside the Game", on = st.autoHide) { st.autoHide = it; saveOptions() })
    }

    private fun hotkeys(b: LinearLayout) = with(ctx) {
        val p = profile()
        b.addView(ggHeader("Hotkeys (tap Edit, then press a key or mouse button; long-press Edit to clear)"))
        hotkeyRow(b, "Mapping On/Off", get = { p.toggleKey }) { p.toggleKey = it; saveProfile() }
        hotkeyRow(b, "Switch Mouse Display (cursor mode)", get = { p.cursorKey }) { p.cursorKey = it; saveProfile() }
        hotkeyRow(b, "Show/Hide HUD", get = { p.hudKey }) { p.hudKey = it; saveProfile() }
        hotkeyRow(b, "Keyboard Typing", "Type in chat while the mouse keeps playing", { p.typingKey }) { p.typingKey = it; saveProfile() }
        hotkeyRow(b, "Show/Hide Logo Button", get = { p.bubbleKey }) { p.bubbleKey = it; saveProfile() }
        hotkeyRow(b, "Crazy Tap On/Off", get = { p.crazyKey }) { p.crazyKey = it; saveProfile() }
        hotkeyRow(b, "Macro Termination (all macros)", get = { p.terminateKey }) { p.terminateKey = it; saveProfile() }
        hotkeyRow(b, "Show/Hide Recoil Panel", get = { p.rcPanelKey }) { p.rcPanelKey = it; saveProfile() }
    }

    private fun monitor(b: LinearLayout) = with(ctx) {
        val st = s
        b.addView(ggHeader("Basic Settings"))
        b.addView(ggSwitchRow("Enable Device Info Monitor", on = st.monitor) { st.monitor = it; saveOptions() })
        b.addView(ggSwitchRow("Fixed Panel", on = st.monFixed) { st.monFixed = it; saveOptions() })
        val rates = listOf(2000, 1000, 500)
        b.addView(ggSegmentedRow("Refresh Rate", "Higher refresh rate might impact the performance",
            listOf("Low", "Medium", "High"), rates.indexOf(st.monRefreshMs).coerceAtLeast(0)) { st.monRefreshMs = rates[it]; saveOptions() })
        b.addView(ggHeader("Customize Display Content"))
        b.addView(ggSwitchRow("FPS Rate", on = st.monFps) { st.monFps = it; saveOptions() })
        b.addView(ggSwitchRow("CPU Usage", on = st.monCpu) { st.monCpu = it; saveOptions() })
        b.addView(ggSwitchRow("CPU Temperature", on = st.monTemp) { st.monTemp = it; saveOptions() })
        b.addView(ggSwitchRow("Current Battery Level", on = st.monBattery) { st.monBattery = it; saveOptions() })
        b.addView(ggSwitchRow("Current Download/Upload Speed", on = st.monNet) { st.monNet = it; saveOptions() })
        b.addView(ggSwitchRow("Router Latency", on = st.monPing) { st.monPing = it; saveOptions() })
        b.addView(ggSwitchRow("Current Time", on = st.monTime) { st.monTime = it; saveOptions() })
        b.addView(ggSwitchRow("Play Time", on = st.monPlayTime) { st.monPlayTime = it; saveOptions() })
    }

    private fun recoil(b: LinearLayout): Unit = with(ctx) {
        val st = s; val p = profile()
        b.addView(ggHeader("Basic Settings"))
        b.addView(ggSwitchRow("Enable Recoil Control", on = p.recoilEnabled) { p.recoilEnabled = it; saveProfile() })
        b.addView(ggSwitchRow("Show Panel", on = st.showRcPanel) { st.showRcPanel = it; saveOptions() })
        b.addView(ggStepperRow("Panel Transparency", min = 20f, max = 100f, step = 1f, value = st.rcAlpha * 100) {
            st.rcAlpha = it / 100; saveOptions() })
        b.addView(ggStepperRow("Global Percentage", "Adjust All Values", 20f, 300f, 5f, p.recoilGlobal * 100) {
            p.recoilGlobal = it / 100; saveProfile() })
        b.addView(ggStepperRow("Start Delay (ms)", min = 0f, max = 500f, step = 10f, value = p.recoilDelay.toFloat()) {
            p.recoilDelay = it.toInt(); saveProfile() })
        b.addView(ggHeader("Active Slot: ${p.activeWeaponObj().name} (F1–F12 switch slots)"))
        b.addView(ggStepperRow("Vertical Value", min = 0f, max = 400f, step = 1f, value = recoilDisplay(p.recoilStrength).toFloat(),
            fmt = { it.toInt().toString() }) { p.recoilStrength = it / 1000; saveProfile() })
        b.addView(ggStepperRow("Horizontal Value", "Negative pulls left, positive pulls right", -200f, 200f, 1f,
            recoilDisplay(p.recoilStrengthX).toFloat(), { it.toInt().toString() }) { p.recoilStrengthX = it / 1000; saveProfile() })
        b.addView(ggSwitchRow("Crazy Tap With This Slot", "For conditional crazy-tap buttons",
            on = p.activeWeaponObj().crazy) { p.activeWeaponObj().crazy = it; saveProfile() })
        b.addView(ggHeader("Aiming & Stance"))
        b.addView(ggSwitchRow("Stop Recoil Control When Not Aiming", on = p.recoilOnlyAiming) { p.recoilOnlyAiming = it; saveProfile() })
        b.addView(ggSegmentedRow("Aiming Type", "Match the game's scope setting", listOf("Hold", "Click"),
            if (p.aimToggle) 1 else 0) { p.aimToggle = it == 1; saveProfile() })
        b.addView(ggSegmentedRow("Horizontal Recoil Enable Type", options = listOf("Press", "Switch"),
            selected = if (p.horizontalToggle) 1 else 0) { p.horizontalToggle = it == 1; saveProfile() })
        b.addView(ggStepperRow("Crouch Factor (%)", min = 10f, max = 150f, step = 5f, value = p.crouchCoef * 100) {
            p.crouchCoef = it / 100; saveProfile() })
        b.addView(ggStepperRow("Prone Factor (%)", min = 10f, max = 150f, step = 5f, value = p.proneCoef * 100) {
            p.proneCoef = it / 100; saveProfile() })
        b.addView(ggHeader("Recoil Keys"))
        hotkeyRow(b, "Fire Key (triggers recoil control)", get = { p.recoilKey }) { p.recoilKey = it; saveProfile() }
        hotkeyRow(b, "Aim Key", get = { p.aimKey }) { p.aimKey = it; saveProfile() }
        hotkeyRow(b, "Horizontal Recoil On/Off", "None = always on", { p.horizontalKey }) { p.horizontalKey = it; saveProfile() }
        hotkeyRow(b, "Crouch Key", get = { p.crouchKey }) { p.crouchKey = it; saveProfile() }
        hotkeyRow(b, "Prone Key", get = { p.proneKey }) { p.proneKey = it; saveProfile() }
        hotkeyRow(b, "RC Value +", "Replaces Ctrl + wheel when set", { p.rcUpKey }) { p.rcUpKey = it; saveProfile() }
        hotkeyRow(b, "RC Value -", get = { p.rcDownKey }) { p.rcDownKey = it; saveProfile() }
        b.addView(ggStepperRow("Adjustment Value", "Each press of RC Value +/- changes the value by this much", 1f, 20f, 1f,
            recoilDisplay(p.rcStep).toFloat(), { it.toInt().toString() }) { p.rcStep = it / 1000; saveProfile() })
        b.addView(ggHeader("Slots (tap one to rename, set its key, values and curve)"))
        p.weapons.forEachIndexed { i, w ->
            val active = i == p.activeWeapon
            b.addView(ggRow((if (active) "●  " else "○  ") + w.name,
                "Key ${com.mapmouse.pro.model.Keys.name(w.key)}  ·  V ${recoilDisplay(w.v)}  H ${recoilDisplay(w.h)}" +
                    (if (w.curve.isNotEmpty()) "  ·  curve" else "") + (if (w.crazy) "  ·  crazy tap" else ""),
                ggChevron()) { navigate("Slot: ${w.name}") { body -> slot(body, i) } })
        }
        b.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, dp(8f))
            addView(primaryButton("+ Add Slot") {
                p.weapons += Weapon("Slot${p.weapons.size}", p.recoilStrength, p.recoilStrengthX); saveProfile(); refresh()
            }.apply { background = roundBg(0xFF7B86FF.toInt(), dp(10f).toFloat()) })
        })
    }

    private fun slot(b: LinearLayout, i: Int): Unit = with(ctx) {
        val p = profile()
        val w = p.weapons.getOrNull(i) ?: return@with
        b.addView(ggHeader("Slot"))
        b.addView(ggRow("Name", trailing = EditText(this).apply {
            setText(w.name); setTextColor(Color.WHITE); textSize = 15f; isSingleLine = true; minWidth = dp(200f)
            setOnFocusChangeListener { v, has -> if (!has) { w.name = (v as EditText).text.toString().trim().ifEmpty { w.name }; saveProfile() } }
            setOnEditorActionListener { v, _, _ -> w.name = v.text.toString().trim().ifEmpty { w.name }; saveProfile(); v.clearFocus(); true }
        }))
        hotkeyRow(b, "Select Key", "Pressing it switches to this slot (doesn't block the key)", { w.key }) { w.key = it; saveProfile() }
        b.addView(ggStepperRow("Vertical Value", min = 0f, max = 400f, step = 1f, value = recoilDisplay(w.v).toFloat(),
            fmt = { it.toInt().toString() }) { w.v = it / 1000; saveProfile() })
        b.addView(ggStepperRow("Horizontal Value", "Negative pulls left, positive pulls right", -200f, 200f, 1f,
            recoilDisplay(w.h).toFloat(), { it.toInt().toString() }) { w.h = it / 1000; saveProfile() })
        b.addView(ggSwitchRow("Crazy Tap With This Slot", "For conditional crazy-tap buttons", on = w.crazy) { w.crazy = it; saveProfile() })
        b.addView(ggRow("Dynamic Curve", if (w.curve.isEmpty()) "Off: fixed values" else "${w.curve.size} points", ggChevron()) {
            navigate("Dynamic: ${w.name}") { body -> body.addView(dynamicBody(w, saveProfile, refresh)) }
        })
        b.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, dp(8f))
            addView(primaryButton("Use This Slot") { p.activeWeapon = i; saveProfile(); refresh() }
                .apply { background = roundBg(0xFF7B86FF.toInt(), dp(10f).toFloat()) })
            if (p.weapons.size > 1) addView(primaryButton("Delete Slot") {
                p.weapons.removeAt(i); p.activeWeapon = p.activeWeapon.coerceIn(0, p.weapons.size - 1); saveProfile()
                navigate("General Recoil Control Settings") { body -> recoil(body) }
            }.apply { background = roundBg(0xFFF2453D.toInt(), dp(10f).toFloat()) })
        })
    }

    private fun crosshair(b: LinearLayout) = with(ctx) {
        val st = s
        b.addView(ggHeader("Crosshair Settings"))
        b.addView(ggSwitchRow("Show Crosshair", on = st.crosshair) { st.crosshair = it; saveOptions() })
        b.addView(ggSwitchRow("Hide When Aiming", on = st.crossHideAiming) { st.crossHideAiming = it; saveOptions() })
        b.addView(ggSegmentedRow("Crosshair Style", options = listOf("Cross", "Dot", "Circle", "Cross+Dot", "T", "Brackets"),
            selected = st.crossStyle) { st.crossStyle = it; saveOptions() })
        b.addView(ggRow("Color", trailing = ggSwatches(SWATCHES + listOf(0xFF00FF00.toInt()), st.crossColor) { st.crossColor = it; saveOptions() }))
        b.addView(ggStepperRow("Size", min = 4f, max = 80f, step = 1f, value = st.crossSize.toFloat()) { st.crossSize = it.toInt(); saveOptions() })
        b.addView(ggStepperRow("Transparency", min = 10f, max = 100f, step = 5f, value = st.crossAlpha * 100) { st.crossAlpha = it / 100; saveOptions() })
        b.addView(ggStepperRow("Horizontal Offset", min = -200f, max = 200f, step = 1f, value = st.crossOffsetX.toFloat()) {
            st.crossOffsetX = it.toInt(); saveOptions() })
        b.addView(ggStepperRow("Vertical Offset", min = -200f, max = 200f, step = 1f, value = st.crossOffsetY.toFloat()) {
            st.crossOffsetY = it.toInt(); saveOptions() })
    }

    private fun gamepad(b: LinearLayout) = with(ctx) {
        val st = s
        b.addView(ggHeader("Gamepad"))
        b.addView(ggRow("Left stick → first joystick, right stick → view", "Buttons, D-pad and triggers can be bound like keys in the editor"))
        b.addView(ggStepperRow("Right Stick Speed", min = 0.2f, max = 4f, step = 0.1f, value = st.padSpeed) { st.padSpeed = it; saveOptions() })
        b.addView(ggStepperRow("Dead Zone (%)", min = 0f, max = 50f, step = 1f, value = st.deadzone * 100) { st.deadzone = it / 100; saveOptions() })
    }
}

/** Body of the "Adjust Value" panel opened from the recoil pill. */
fun Context.adjustValueBody(
    p: Profile, save: () -> Unit, rename: (String) -> Unit, openDynamic: () -> Unit, refresh: () -> Unit,
): View {
    val w = p.activeWeaponObj()
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10f), dp(4f), dp(10f), dp(8f)) }
    // Slot switcher.
    box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(chip("◀") { p.activeWeapon = (p.activeWeapon - 1 + p.weapons.size) % p.weapons.size; save(); refresh() })
        addView(label(w.name, 16f, Color.WHITE).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(chip("▶") { p.activeWeapon = (p.activeWeapon + 1) % p.weapons.size; save(); refresh() })
    })
    fun valueRow(title: String, min: Float, max: Float, get: () -> Float, set: (Float) -> Unit, default: Float): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(title, 14f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                text = "Reset"; textSize = 14f; setTextColor(Color.WHITE)
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener { set(default); save(); refresh() }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(ggStepper(min, max, 1f, recoilDisplay(get()).toFloat(), { it.toInt().toString() }) { set(it / 1000); save() })
        }
    }
    box.addView(valueRow("Anti-recoil Value (vertical)", 0f, 400f, { w.v }, { w.v = it }, 0.06f))
    box.addView(valueRow("Horizontal Value", -200f, 200f, { w.h }, { w.h = it }, 0f))
    box.addView(label("Adjust value shortcut: hold left Ctrl and scroll the mouse wheel. F1–F12 switch slots.", 12f, 0xDDFFFFFF.toInt()))
    val nameField = EditText(this).apply {
        setText(w.name); setTextColor(Color.WHITE); textSize = 14f; isSingleLine = true; visibility = View.GONE
        setOnEditorActionListener { v, _, _ -> rename(v.text.toString().trim()); true }
    }
    box.addView(nameField)
    box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        addView(primaryButton("Dynamic") { openDynamic() }.apply { background = roundBg(0xFF7B86FF.toInt(), dp(10f).toFloat()) })
        addView(primaryButton("Rename") { b ->
            if (nameField.visibility == View.GONE) { nameField.visibility = View.VISIBLE; nameField.requestFocus(); b.text = "OK" }
            else rename(nameField.text.toString().trim())
        }.apply { background = roundBg(0xFF7B86FF.toInt(), dp(10f).toFloat()) })
    })
    return box
}

/** Dynamic recoil: strength over time for the active slot. */
fun Context.dynamicBody(w: Weapon, save: () -> Unit, refresh: () -> Unit): View {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(ggRow("${w.name}: strength over the spray",
        "Each point sets the strength from that time after you start firing (linear in between). No points = the fixed value."))
    w.curve.sortBy { it.t }
    w.curve.forEachIndexed { i, pt ->
        box.addView(ggHeader("Point ${i + 1}"))
        box.addView(ggStepperRow("Time (s)", min = 0f, max = 5000f, step = 50f, value = pt.t.toFloat(),
            fmt = { "%.2f".format(it / 1000) }) { pt.t = it.toInt(); save() })
        box.addView(ggStepperRow("Vertical", min = 0f, max = 400f, step = 1f, value = recoilDisplay(pt.v).toFloat(),
            fmt = { it.toInt().toString() }) { pt.v = it / 1000; save() })
        box.addView(ggStepperRow("Horizontal", min = -200f, max = 200f, step = 1f, value = recoilDisplay(pt.h).toFloat(),
            fmt = { it.toInt().toString() }) { pt.h = it / 1000; save() })
        box.addView(ggRow("Delete point", trailing = chip("Delete") { w.curve.removeAt(i); save(); refresh() }))
    }
    box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, dp(8f))
        addView(primaryButton("+ Add Point") {
            val last = w.curve.lastOrNull()
            w.curve += CurvePoint((last?.t ?: -500) + 500, last?.v ?: w.v, last?.h ?: w.h); save(); refresh()
        }.apply { background = roundBg(0xFF7B86FF.toInt(), dp(10f).toFloat()) })
        addView(primaryButton("Clear") { w.curve.clear(); save(); refresh() }
            .apply { background = roundBg(0xFF7B86FF.toInt(), dp(10f).toFloat()) })
    })
    return box
}
