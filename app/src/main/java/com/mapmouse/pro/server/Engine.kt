package com.mapmouse.pro.server

import android.os.SystemClock
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Which device classes should be grabbed (hidden from Android) right now. */
data class Grab(val mouse: Boolean, val keyboard: Boolean, val touch: Boolean, val pad: Boolean)

/** Global (not per-profile) options sent by the app. */
class Options(
    /** Mouse max polling rate in Hz; 0 = no limit. */
    val pollHz: Int = 0,
    /** View smoothing 0 (off) .. 0.8 (very smooth). */
    val smooth: Float = 0f,
    /** Re-inject real finger touches so touching the screen doesn't break mapping. */
    val touchCompat: Boolean = false,
    val showTouches: Boolean = false,
    /** Gamepad right-stick view speed (screen heights per second at full tilt). */
    val padSpeed: Float = 1.2f,
    val deadzone: Float = 0.15f,
    /**
     * Lift the aim finger shortly after the mouse stops. While any injected finger is down,
     * Android 14+ ignores the real touchscreen, so this keeps the screen usable in-game.
     */
    val touchFriendly: Boolean = true,
) {
    companion object {
        fun fromJson(o: JSONObject) = Options(
            o.optInt("pollHz", 0), o.optDouble("smooth", 0.0).toFloat(), o.optBoolean("touchCompat"),
            o.optBoolean("showTouches"), o.optDouble("padSpeed", 1.2).toFloat(), o.optDouble("deadzone", .15).toFloat(),
            o.optBoolean("touchFriendly", true),
        )
    }
}

/**
 * Turns keyboard/mouse/gamepad input into touches according to the active [Profile].
 * All state is guarded by `this`; macro/swipe/rapid-tap jobs run on their own
 * threads and only talk to the (thread-safe) [Touch] injector, or lock `this` briefly.
 */
class Engine(
    private val touch: Touch,
    private val devices: () -> Pair<Int, Int>,
    private val onGrab: (Grab) -> Unit,
    private val listener: Listener,
) : InputSink {

    interface Listener {
        fun onCursor(x: Int, y: Int, visible: Boolean)
        fun onState(enabled: Boolean, cursorMode: Boolean, paused: Boolean, mice: Int, keyboards: Int)
        fun onHud(json: String)
        fun onPointers(xy: IntArray)
    }

    private var profile: Profile? = null
    @Volatile private var screenW = 1920f
    @Volatile private var screenH = 1080f
    private var rotation = 0
    @Volatile private var options = Options()

    private var enabled = false
    private var paused = false
    private var cursorMode = false
    private var typing = false
    private var hudVisible = true
    private var rcPanelVisible = true
    private var crazyOn = true

    private val held = HashSet<Int>()
    private val keyOwner = HashMap<Int, Control>()
    private val activeGroups = HashSet<Int>()
    private val tapPid = HashMap<Control, Int>()
    private val joyDirs = HashMap<Control, MutableSet<Int>>()
    private val joyPid = HashMap<Control, Int>()
    private val joyPos = HashMap<Control, Pair<Float, Float>>()
    private val joyAlt = HashSet<Control>()
    private val jobs = HashMap<Control, Thread>()
    private val disabledMacros = HashSet<Control>()
    private val pressTime = HashMap<Control, Long>()

    // view (FPS camera) finger
    private var viewPid = -1
    private var viewX = 0f
    private var viewY = 0f
    private var lastViewMove = 0L
    private var altSens = false
    private var smoothX = 0f
    private var smoothY = 0f

    // free look finger
    private var freeLook: Control? = null
    private var freePid = -1
    private var freeX = 0f
    private var freeY = 0f

    // scroll finger
    private var scrollPid = -1
    private var scrollX = 0f
    private var scrollY = 0f
    private var scrollAnchorX = 0f
    private var scrollAnchorY = 0f
    private var scrollLast = 0L

    // cursor
    private var cursorX = 0f
    private var cursorY = 0f
    private var lastCursorX = -1f
    private var lastCursorY = -1f
    private var cursorPid = -1
    private var cursorDirty = false

    // mouse polling-rate cap
    private var pendDx = 0
    private var pendDy = 0
    private var lastApply = 0L

    // recoil
    private var aiming = false
    /** 0 = stand, 1 = crouch, 2 = prone. */
    private var stance = 0
    private var horizontalOn = true
    private var macroRecoil = false
    private val recoilControls = HashSet<Control>()
    private var wasFiring = false
    private var fireStart = 0L
    private var recoilAccX = 0f
    private var recoilAccY = 0f

    // gamepad
    private var leftStick = 0f to 0f
    private var rightStick = 0f to 0f
    private var padAccX = 0f
    private var padAccY = 0f

    // real fingers (touch-compatible mode): device slot -> injected pointer
    private val fingerPid = HashMap<Int, Int>()

    private var lastPointers = IntArray(0)
    private var lastPointerSend = 0L

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    companion object {
        /** Idle time before the aim finger lifts in touch-friendly mode. */
        private const val TOUCH_FRIENDLY_RELEASE_MS = 250L
    }

    fun start() {
        Thread({
            var last = SystemClock.uptimeMillis()
            while (true) {
                Thread.sleep(4)
                val now = SystemClock.uptimeMillis()
                tick(now, (now - last) / 1000f)
                last = now
            }
        }, "engine-tick").apply { isDaemon = true }.start()
    }

    // ---- commands from the app ----

    @Synchronized fun setScreen(w: Int, h: Int, rot: Int) {
        if (w <= 0 || h <= 0) return
        if (w.toFloat() != screenW || h.toFloat() != screenH) reset()
        screenW = w.toFloat(); screenH = h.toFloat(); rotation = rot
    }

    @Synchronized fun setProfile(p: Profile) {
        reset()
        profile = p
        horizontalOn = p.horizontalKey == Keys.NONE
        sendHud()
    }

    @Synchronized fun setOptions(o: Options) {
        options = o
        updateGrab()
    }

    /**
     * Live tuning of hotkeys, cursor and recoil settings. Unlike [setProfile] this doesn't
     * reset, so fingers that are down stay down (adjusting recoil mid-fight is fine).
     */
    @Synchronized fun updateSettings(s: Profile) {
        val p = profile ?: return
        p.toggleKey = s.toggleKey; p.cursorKey = s.cursorKey; p.cursorHold = s.cursorHold
        p.cursorShowPos = s.cursorShowPos; p.cursorX = s.cursorX; p.cursorY = s.cursorY
        p.terminateKey = s.terminateKey; p.cursorSpeed = s.cursorSpeed
        p.hudKey = s.hudKey; p.typingKey = s.typingKey; p.bubbleKey = s.bubbleKey
        p.recoilEnabled = s.recoilEnabled; p.recoilKey = s.recoilKey; p.recoilDelay = s.recoilDelay
        p.recoilGlobal = s.recoilGlobal; p.weapons = s.weapons
        p.activeWeapon = s.activeWeapon.coerceIn(0, s.weapons.size - 1)
        p.recoilOnlyAiming = s.recoilOnlyAiming; p.aimKey = s.aimKey; p.aimToggle = s.aimToggle
        if (p.horizontalKey != s.horizontalKey) horizontalOn = s.horizontalKey == Keys.NONE
        p.horizontalKey = s.horizontalKey; p.horizontalToggle = s.horizontalToggle
        p.crouchKey = s.crouchKey; p.proneKey = s.proneKey; p.crouchCoef = s.crouchCoef; p.proneCoef = s.proneCoef
        p.rcUpKey = s.rcUpKey; p.rcDownKey = s.rcDownKey; p.rcStep = s.rcStep; p.rcPanelKey = s.rcPanelKey
        p.crazyKey = s.crazyKey
        sendHud()
    }

    @Synchronized fun setEnabled(on: Boolean) {
        if (enabled == on) return
        reset()
        enabled = on
        typing = false
        updateGrab()
        sendState()
        sendHud()
    }

    @Synchronized fun setPaused(on: Boolean) {
        if (paused == on) return
        reset()
        paused = on
        updateGrab()
        sendState()
    }

    @Synchronized fun setTyping(on: Boolean) {
        if (!enabled || typing == on) return
        typing = on
        // Release keys that were down so nothing stays pressed while typing.
        keyOwner.keys.filter { isKeyboardKey(it) }.forEach { code -> keyOwner.remove(code)?.let { release(it, code) } }
        updateGrab()
        sendHud()
    }

    private fun updateGrab() {
        val active = enabled && !paused
        onGrab(Grab(mouse = active, keyboard = active && !typing, touch = active && options.touchCompat, pad = active))
    }

    @Synchronized fun sendState() {
        val (mice, kbds) = devices()
        listener.onState(enabled, cursorMode, paused, mice, kbds)
    }

    private fun sendHud() {
        val p = profile ?: return
        val w = p.activeWeaponObj()
        listener.onHud(JSONObject().apply {
            put("weapon", w.name); put("weaponIndex", p.activeWeapon)
            put("v", w.v.toDouble()); put("h", w.h.toDouble())
            put("recoil", p.recoilEnabled); put("horizontal", horizontalOn)
            put("stance", stance); put("aiming", aiming); put("crazy", crazyOn)
            put("hud", hudVisible); put("rcPanel", rcPanelVisible); put("typing", typing)
            put("enabled", enabled); put("cursor", cursorMode)
        }.toString())
    }

    private fun isKeyboardKey(code: Int) = code < Keys.BTN_LEFT || (code in 0x160..0x2ff && !Keys.isWheel(code) && !Keys.isGamepad(code))

    /** Lifts every finger and stops every job. */
    @Synchronized fun reset() {
        jobs.values.forEach { it.interrupt() }
        jobs.clear()
        touch.releaseAll()
        keyOwner.clear(); activeGroups.clear(); tapPid.clear(); joyDirs.clear(); joyPid.clear(); joyPos.clear()
        joyAlt.clear(); recoilControls.clear(); pressTime.clear(); fingerPid.clear()
        viewPid = -1; cursorPid = -1; freePid = -1; freeLook = null; scrollPid = -1
        altSens = false; macroRecoil = false; aiming = false; stance = 0
        smoothX = 0f; smoothY = 0f; pendDx = 0; pendDy = 0
        if (cursorMode) { cursorMode = false; listener.onCursor(0, 0, false) }
    }

    // ---- physical input ----

    override fun onKey(code: Int, down: Boolean): Unit = synchronized(this) {
        if (down) held += code else held -= code
        val p = profile ?: return
        if (paused) return
        if (code == p.toggleKey) { if (down) setEnabled(!enabled); return }
        if (!enabled) return
        if (code == p.typingKey) { if (down) setTyping(!typing); return }
        if (typing && isKeyboardKey(code)) return

        // Hotkeys that are consumed.
        when (code) {
            p.cursorKey -> {
                if (p.cursorHold) { if (down != cursorMode) toggleCursor() } else if (down) toggleCursor()
                return
            }
            p.terminateKey -> { if (down) { jobs.values.forEach { it.interrupt() }; jobs.clear() }; return }
            p.hudKey -> { if (down) { hudVisible = !hudVisible; sendHud() }; return }
            p.rcPanelKey -> { if (down) { rcPanelVisible = !rcPanelVisible; sendHud() }; return }
            p.crazyKey -> { if (down) { crazyOn = !crazyOn; sendHud() }; return }
            p.rcUpKey -> { if (down) adjustRecoil(+1); return }
            p.rcDownKey -> { if (down) adjustRecoil(-1); return }
            p.bubbleKey -> { if (down) listener.onHud(JSONObject().put("toggleBubble", true).toString()); return }
        }
        if (code == Keys.NONE) return
        // Ctrl + wheel adjusts recoil instead of reaching the game.
        if (Keys.isWheel(code) && (Keys.KEY_LEFTCTRL in held || Keys.KEY_RIGHTCTRL in held)) {
            if (down) adjustRecoil(if (code == Keys.WHEEL_UP) +1 else -1)
            return
        }

        // Observed keys: they still reach their controls.
        if (down) {
            p.weapons.indexOfFirst { it.key == code }.takeIf { it >= 0 }?.let { p.activeWeapon = it; sendHud() }
            if (code == p.crouchKey) { stance = if (stance == 1) 0 else 1; sendHud() }
            if (code == p.proneKey) { stance = if (stance == 2) 0 else 2; sendHud() }
        }
        if (code == p.aimKey) {
            if (p.aimToggle) { if (down) aiming = !aiming } else aiming = down
            sendHud()
        }
        if (code == p.horizontalKey) {
            if (p.horizontalToggle) { if (down) horizontalOn = !horizontalOn } else horizontalOn = down
            sendHud()
        }

        if (cursorMode && code == Keys.BTN_LEFT) {
            if (down && cursorPid < 0) cursorPid = touch.down(cursorX, cursorY)
            else if (!down && cursorPid >= 0) { touch.up(cursorPid); cursorPid = -1 }
            return
        }
        if (down) {
            val c = findControl(p, code)
            if (c == null) {
                if (Keys.isWheel(code)) scroll(if (code == Keys.WHEEL_UP) 1 else -1)
                return
            }
            keyOwner[code] = c
            press(c, code)
        } else {
            val c = keyOwner.remove(code) ?: return
            release(c, code)
        }
    }

    override fun onMove(dx: Int, dy: Int): Unit = synchronized(this) {
        if (!enabled || paused) return
        pendDx += dx; pendDy += dy
        val now = SystemClock.uptimeMillis()
        val hz = options.pollHz
        if (hz <= 0 || now - lastApply >= 1000 / hz) flushMove(now)
    }

    private fun flushMove(now: Long) {
        val dx = pendDx; val dy = pendDy
        pendDx = 0; pendDy = 0
        lastApply = now
        if (dx == 0 && dy == 0) return
        val p = profile ?: return
        val scale = screenH / 1080f
        when {
            cursorMode -> {
                cursorX = (cursorX + dx * p.cursorSpeed * scale).coerceIn(0f, screenW - 1)
                cursorY = (cursorY + dy * p.cursorSpeed * scale).coerceIn(0f, screenH - 1)
                if (cursorPid >= 0) touch.move(cursorPid, cursorX, cursorY)
                cursorDirty = true
            }
            freeLook != null -> moveFreeLook(freeLook!!, dx * scale, dy * scale)
            else -> {
                val v = viewControl() ?: return
                var mx = if (altSens) v.altSensX else v.sensX
                var my = if (altSens) v.altSensY else v.sensY
                if (v.accel) {
                    val speed = hypot(dx.toFloat(), dy.toFloat())
                    if (speed > v.accelMinSpeed) {
                        val k = min(1f + (speed - v.accelMinSpeed) * v.accelCoef * 0.1f, v.accelLimit)
                        mx *= k; my *= k
                    }
                }
                val s = options.smooth
                if (s > 0f) {
                    smoothX += dx * mx * scale; smoothY += dy * my * scale
                    val ax = smoothX * (1 - s); val ay = smoothY * (1 - s)
                    smoothX -= ax; smoothY -= ay
                    moveView(v, ax, ay)
                } else moveView(v, dx * mx * scale, dy * my * scale)
            }
        }
    }

    override fun onWheel(steps: Int) {
        val code = if (steps > 0) Keys.WHEEL_UP else Keys.WHEEL_DOWN
        onKey(code, true)
        scheduler.schedule({ onKey(code, false) }, 40, TimeUnit.MILLISECONDS)
    }

    override fun onStick(stick: Int, x: Float, y: Float): Unit = synchronized(this) {
        if (!enabled || paused) return
        val dz = options.deadzone
        val m = hypot(x, y)
        val v = if (m < dz) 0f to 0f else x to y
        if (stick == 0) {
            leftStick = v
            profile?.controls?.firstOrNull { it.type == ControlType.JOYSTICK }?.let { updateJoystick(it) }
        } else rightStick = v
    }

    override fun onFinger(slot: Int, down: Boolean, nx: Float, ny: Float): Unit = synchronized(this) {
        if (!enabled || paused || !options.touchCompat) return
        // Natural-orientation touchscreen coordinates -> current display orientation.
        val (x, y) = when (rotation) {
            1 -> ny * screenW to (1 - nx) * screenH
            2 -> (1 - nx) * screenW to (1 - ny) * screenH
            3 -> (1 - ny) * screenW to nx * screenH
            else -> nx * screenW to ny * screenH
        }
        val pid = fingerPid[slot]
        when {
            !down -> if (pid != null) { touch.up(pid); fingerPid.remove(slot) }
            pid == null -> touch.down(x, y).takeIf { it >= 0 }?.let { fingerPid[slot] = it }
            else -> touch.move(pid, x, y)
        }
    }

    // ---- control logic ----

    private fun findControl(p: Profile, code: Int): Control? {
        val matches = p.controls.filter { code in it.keys() }
        return matches.firstOrNull { it.group != 0 && it.group in activeGroups }
            ?: matches.firstOrNull { it.group == 0 }
    }

    private fun viewControl(): Control? = profile?.controls?.firstOrNull { it.type == ControlType.VIEW }

    private fun px(x: Float) = x * screenW
    private fun py(y: Float) = y * screenH

    private fun sideEffects(c: Control, down: Boolean) {
        if (c.type != ControlType.TAP && c.type != ControlType.CRAZY_TAP) return
        if (c.cursorToggle) {
            val fire = when (c.cursorEdge) { Edge.DOWN -> down; Edge.UP -> !down; Edge.BOTH -> true }
            if (fire) toggleCursor()
        }
        if (down && c.resetView) resetView()
        if (down && c.resetJoystick) resetJoysticks()
        if (c.triggersRecoil) { if (down) recoilControls += c else recoilControls -= c }
    }

    private fun press(c: Control, code: Int) {
        pressTime[c] = SystemClock.uptimeMillis()
        when (c.type) {
            ControlType.TAP -> when (c.tapMode) {
                TapMode.HOLD -> if (c !in tapPid) tapPid[c] = touch.down(px(c.x), py(c.y))
                TapMode.TAP, TapMode.DOWN_UP -> startJob(c) { tap(it, c.x, c.y, c.tapDuration) }
                TapMode.LONG_PRESS -> startJob(c) { ctx ->
                    Thread.sleep(c.longPressMs.toLong())
                    val id = touch.down(px(c.x), py(c.y))
                    if (id >= 0) ctx.pids += id
                    Thread.sleep(Long.MAX_VALUE)
                }
                TapMode.UP -> {}
            }
            ControlType.JOYSTICK -> {
                if (code == c.altKey) {
                    if (c.altToggle) { if (!joyAlt.remove(c)) joyAlt += c } else joyAlt += c
                } else joyDirs.getOrPut(c) { HashSet() } += code
                updateJoystick(c)
            }
            ControlType.VIEW -> when (code) {
                c.altSensKey -> altSens = if (c.altToggle) !altSens else true
                c.resetKey -> resetView()
            }
            ControlType.FREE_LOOK -> {
                if (viewPid >= 0) { touch.up(viewPid); viewPid = -1 }
                freeLook = c
                freeX = px(c.x); freeY = py(c.y)
                freePid = touch.down(freeX, freeY)
            }
            ControlType.SWIPE -> startJob(c) { ctx -> swipeControl(ctx, c) }
            ControlType.SCROLL -> {}
            ControlType.REPEAT -> startJob(c) { ctx ->
                while (true) {
                    tap(ctx, c.x, c.y, c.tapDuration)
                    Thread.sleep(max(10, c.duration - c.tapDuration).toLong())
                }
            }
            ControlType.CRAZY_TAP -> {
                val weaponAllows = !c.conditional || profile?.activeWeaponObj()?.crazy == true
                if (crazyOn && weaponAllows) startJob(c) { ctx -> crazyTap(ctx, c) }
                else if (c !in tapPid) tapPid[c] = touch.down(px(c.x), py(c.y))
            }
            ControlType.MACRO -> {
                if (code == c.terminateKey) {
                    stopJob(c)
                    if (c.disableOnTerminate) { if (!disabledMacros.remove(c)) disabledMacros += c }
                    return
                }
                if (c in disabledMacros) return
                when (c.trigger) {
                    MacroTrigger.DOWN, MacroTrigger.DOWN_UP -> startJob(c) { runOps(it, c.ops) }
                    MacroTrigger.LOOP -> startJob(c) { while (true) { runOps(it, c.ops); Thread.sleep(1) } }
                    MacroTrigger.UP -> {}
                }
            }
            ControlType.LONG_PRESS_GROUP -> startJob(c) { ctx -> longPressGroup(ctx, c) }
            ControlType.GROUP -> activeGroups += c.targetGroup
        }
        sideEffects(c, true)
    }

    private fun release(c: Control, code: Int) {
        val heldMs = SystemClock.uptimeMillis() - (pressTime[c] ?: 0L)
        when (c.type) {
            ControlType.TAP -> when (c.tapMode) {
                TapMode.HOLD -> tapPid.remove(c)?.let { touch.up(it) }
                TapMode.DOWN_UP, TapMode.UP -> startJob(c) { tap(it, c.x, c.y, c.tapDuration) }
                TapMode.LONG_PRESS -> stopJob(c)
                TapMode.TAP -> {}
            }
            ControlType.JOYSTICK -> {
                if (code == c.altKey) { if (!c.altToggle) joyAlt -= c } else joyDirs[c]?.remove(code)
                updateJoystick(c)
            }
            ControlType.VIEW -> if (code == c.altSensKey && !c.altToggle) altSens = false
            ControlType.FREE_LOOK -> {
                if (freePid >= 0) touch.up(freePid)
                freePid = -1; freeLook = null
            }
            ControlType.REPEAT -> stopJob(c)
            ControlType.CRAZY_TAP -> { stopJob(c); tapPid.remove(c)?.let { touch.up(it) } }
            ControlType.MACRO -> if (code != c.terminateKey && c !in disabledMacros) when (c.trigger) {
                MacroTrigger.UP, MacroTrigger.DOWN_UP -> startJob(c) { runOps(it, c.ops) }
                MacroTrigger.LOOP -> stopJob(c)
                MacroTrigger.DOWN -> {}
            }
            ControlType.LONG_PRESS_GROUP -> {
                stopJob(c)
                // A short press (before the first stage) taps the group's own point.
                val first = c.ops.minOfOrNull { it.duration } ?: Int.MAX_VALUE
                if (heldMs < first) startJob(c) { tap(it, c.x, c.y, c.tapDuration) }
            }
            ControlType.GROUP -> activeGroups -= c.targetGroup
            ControlType.SWIPE, ControlType.SCROLL -> {}
        }
        sideEffects(c, false)
    }

    private fun updateJoystick(c: Control) {
        val dirs = joyDirs[c] ?: emptySet<Int>()
        var vx = 0f; var vy = 0f
        if (c.keyLeft in dirs) vx -= 1; if (c.keyRight in dirs) vx += 1
        if (c.keyUp in dirs) vy -= 1; if (c.keyDown in dirs) vy += 1
        var len = hypot(vx, vy)
        if (len > 0f) { vx /= len; vy /= len } else {
            // No keys: use the gamepad's left stick (analog, so keep its magnitude).
            vx = leftStick.first; vy = leftStick.second
            len = hypot(vx, vy)
            if (len > 1f) { vx /= len; vy /= len }
        }
        val pid = joyPid[c]
        if (vx == 0f && vy == 0f) {
            if (pid != null) { touch.up(pid); joyPid.remove(c); joyPos.remove(c) }
            return
        }
        val r = c.radius * screenH * (if (c in joyAlt) c.altPercent else 1f)
        val cx = px(c.x); val cy = py(c.y)
        val id = pid ?: touch.down(cx, cy).also { joyPid[c] = it; joyPos[c] = cx to cy }
        if (id < 0) return
        val tx = cx + vx * r; val ty = cy + vy * r
        val (sx, sy) = joyPos[c] ?: (cx to cy)
        val n = c.steps.coerceIn(1, 10)
        for (i in 1..n) {
            val k = i.toFloat() / n
            touch.move(id, sx + (tx - sx) * k, sy + (ty - sy) * k)
        }
        joyPos[c] = tx to ty
    }

    private fun resetJoysticks() {
        for ((c, pid) in joyPid.toMap()) { touch.up(pid); joyPid.remove(c); joyPos.remove(c); updateJoystick(c) }
    }

    private fun resetView() {
        if (viewPid >= 0) { touch.up(viewPid); viewPid = -1 }
    }

    private fun moveView(v: Control, ddx: Float, ddy: Float) {
        val ax = px(v.x); val ay = py(v.y)
        if (viewPid < 0 || !touch.isDown(viewPid)) {
            viewPid = touch.down(ax, ay)
            viewX = ax; viewY = ay
            if (viewPid < 0) return
        }
        var nx = viewX + ddx
        var ny = viewY + ddy
        val outOfRange = hypot(nx - ax, ny - ay) > v.radius * screenH ||
            nx < 0 || ny < 0 || nx >= screenW || ny >= screenH
        if (outOfRange) {
            // Lift and put the finger back at the anchor, like re-centering a thumb.
            touch.up(viewPid)
            viewPid = touch.down(ax, ay)
            if (viewPid < 0) return
            nx = ax + ddx; ny = ay + ddy
        }
        touch.move(viewPid, nx, ny)
        viewX = nx; viewY = ny
        lastViewMove = SystemClock.uptimeMillis()
    }

    private fun moveFreeLook(c: Control, dx: Float, dy: Float) {
        if (freePid < 0) return
        val ax = px(c.x); val ay = py(c.y)
        var nx = freeX + dx * c.sensX
        var ny = freeY + dy * c.sensY
        if (hypot(nx - ax, ny - ay) > c.radius * screenH) {
            if (!c.edgeReleaseOnly) return // stop at the edge
            touch.up(freePid)
            freePid = touch.down(ax, ay)
            nx = ax; ny = ay
        }
        touch.move(freePid, nx, ny)
        freeX = nx; freeY = ny
    }

    private fun scroll(dir: Int) {
        val c = profile?.controls?.firstOrNull { it.type == ControlType.SCROLL } ?: return
        if (scrollPid < 0 || !touch.isDown(scrollPid)) {
            scrollAnchorX = if (cursorMode) cursorX else px(c.x)
            scrollAnchorY = if (cursorMode) cursorY else py(c.y)
            scrollX = scrollAnchorX; scrollY = scrollAnchorY
            scrollPid = touch.down(scrollX, scrollY)
            if (scrollPid < 0) return
        }
        val step = c.sensY * 0.04f * screenH * dir * (if (c.invert) -1 else 1)
        var ny = scrollY + step
        if (abs(ny - scrollAnchorY) > c.radius * screenH || ny < 0 || ny >= screenH) {
            touch.up(scrollPid)
            scrollPid = touch.down(scrollAnchorX, scrollAnchorY)
            ny = scrollAnchorY + step
        }
        touch.move(scrollPid, scrollX, ny)
        scrollY = ny
        scrollLast = SystemClock.uptimeMillis()
    }

    private fun toggleCursor() {
        val p = profile ?: return
        cursorMode = !cursorMode
        if (cursorMode) {
            if (viewPid >= 0) { touch.up(viewPid); viewPid = -1 }
            when (p.cursorShowPos) {
                CursorShowPos.CENTER -> { cursorX = screenW / 2; cursorY = screenH / 2 }
                CursorShowPos.CUSTOM -> { cursorX = px(p.cursorX); cursorY = py(p.cursorY) }
                CursorShowPos.LAST -> if (lastCursorX >= 0) { cursorX = lastCursorX; cursorY = lastCursorY }
                    else { cursorX = screenW / 2; cursorY = screenH / 2 }
            }
            cursorDirty = true
        } else {
            lastCursorX = cursorX; lastCursorY = cursorY
            if (cursorPid >= 0) { touch.up(cursorPid); cursorPid = -1 }
            listener.onCursor(0, 0, false)
        }
        sendState()
        sendHud()
    }

    private fun adjustRecoil(dir: Int) {
        val p = profile ?: return
        val w = p.activeWeaponObj()
        w.v = (w.v + dir * p.rcStep).coerceIn(0f, 1f)
        sendHud()
    }

    @Synchronized private fun tick(now: Long, dt: Float) {
        val p = profile ?: return
        if (!enabled || paused) return
        if (pendDx != 0 || pendDy != 0) {
            val hz = options.pollHz
            if (hz <= 0 || now - lastApply >= 1000 / hz) flushMove(now)
        }
        if (options.showTouches && now - lastPointerSend >= 33) {
            val pts = touch.snapshot()
            if (!pts.contentEquals(lastPointers)) { lastPointers = pts; listener.onPointers(pts) }
            lastPointerSend = now
        }
        if (cursorMode) {
            if (cursorDirty) { listener.onCursor(cursorX.toInt(), cursorY.toInt(), true); cursorDirty = false }
        }
        if (scrollPid >= 0) {
            val c = p.controls.firstOrNull { it.type == ControlType.SCROLL }
            if (c == null || now - scrollLast > c.duration) { touch.up(scrollPid); scrollPid = -1 }
        }
        val v = viewControl()
        if (v != null && !cursorMode && freeLook == null) {
            // Smoothing residue, idle release, gamepad right stick.
            if (options.smooth > 0f && (abs(smoothX) > 0.3f || abs(smoothY) > 0.3f)) {
                val s = options.smooth
                val ax = smoothX * (1 - s); val ay = smoothY * (1 - s)
                smoothX -= ax; smoothY -= ay
                moveView(v, ax, ay)
            }
            if (!v.edgeReleaseOnly && viewPid >= 0 && now - lastViewMove > v.idleReleaseMs) resetView()
            if (options.touchFriendly && viewPid >= 0 && now - lastViewMove > TOUCH_FRIENDLY_RELEASE_MS) resetView()
            val (rx, ry) = rightStick
            if (rx != 0f || ry != 0f) {
                padAccX += rx * options.padSpeed * screenH * dt * v.sensX
                padAccY += ry * options.padSpeed * screenH * dt * v.sensY
                val sx = padAccX.toInt(); val sy = padAccY.toInt()
                if (sx != 0 || sy != 0) { padAccX -= sx; padAccY -= sy; moveView(v, sx.toFloat(), sy.toFloat()) }
            }
        }
        recoilTick(p, v, now, dt)
    }

    private fun recoilTick(p: Profile, v: Control?, now: Long, dt: Float) {
        val firing = p.recoilKey in held || recoilControls.isNotEmpty() || macroRecoil
        if (firing && !wasFiring) {
            fireStart = now; recoilAccX = 0f; recoilAccY = 0f
            // One line per spray, so "it doesn't work" can be diagnosed from the server log.
            val w = p.activeWeaponObj()
            val why = when {
                v == null -> "no FPS View button in this config"
                !p.recoilEnabled -> "recoil control is off"
                cursorMode -> "mouse pointer is shown (unlocked)"
                freeLook != null -> "free look is held"
                p.recoilOnlyAiming && !aiming -> "only-while-aiming is on and not aiming"
                else -> "applying"
            }
            Log.i("recoil: fire (key ${Keys.name(p.recoilKey)}) slot ${w.name} v=${w.v} h=${w.h} delay=${p.recoilDelay} -> $why")
        }
        if (!firing && wasFiring) Log.i("recoil: stop, moved view by ${recoilApplied}px")
        if (firing && !wasFiring) recoilApplied = 0
        wasFiring = firing
        if (v == null || !p.recoilEnabled || !firing || cursorMode || freeLook != null) return
        if (p.recoilOnlyAiming && !aiming) return
        val t = now - fireStart - p.recoilDelay
        if (t < 0) return
        val (wv, wh) = p.activeWeaponObj().at(t)
        val coef = p.recoilGlobal * when (stance) { 1 -> p.crouchCoef; 2 -> p.proneCoef; else -> 1f }
        if (horizontalOn) recoilAccX += wh * coef * screenH * dt
        recoilAccY += wv * coef * screenH * dt
        // Only whole pixels are applied; the remainder carries over to the next tick.
        val stepX = recoilAccX.toInt()
        val stepY = recoilAccY.toInt()
        if (stepX != 0 || stepY != 0) {
            recoilAccX -= stepX; recoilAccY -= stepY
            moveView(v, stepX.toFloat(), stepY.toFloat())
            recoilApplied += stepY
        }
    }

    /** Pixels of downward pull in the current spray (for the log). */
    private var recoilApplied = 0

    // ---- jobs (swipes, rapid tap, macros) ----

    private class JobCtx {
        val pids = HashSet<Int>()
        val slots = HashMap<Int, Int>()
    }

    private fun startJob(c: Control, body: (JobCtx) -> Unit) {
        stopJob(c)
        val ctx = JobCtx()
        val t = Thread({
            try {
                body(ctx)
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.e("job: $e")
            } finally {
                ctx.pids.forEach { touch.up(it) }
                synchronized(this) { if (jobs[c] === Thread.currentThread()) jobs.remove(c) }
            }
        }, "job")
        jobs[c] = t
        t.start()
    }

    private fun stopJob(c: Control) {
        jobs.remove(c)?.interrupt()
    }

    private fun checkInterrupted() {
        if (Thread.interrupted()) throw InterruptedException()
    }

    private fun tap(ctx: JobCtx, x: Float, y: Float, ms: Int) {
        val id = touch.down(px(x), py(y))
        if (id < 0) return
        ctx.pids += id
        try { Thread.sleep(max(1, ms).toLong()) } finally { touch.up(id); ctx.pids -= id }
    }

    private fun swipe(ctx: JobCtx, x1: Float, y1: Float, x2: Float, y2: Float, ms: Int, roundTrip: Boolean = false) {
        val id = touch.down(px(x1), py(y1))
        if (id < 0) return
        ctx.pids += id
        try {
            val legs = if (roundTrip) listOf(Pair(x1 to y1, x2 to y2), Pair(x2 to y2, x1 to y1))
                else listOf(Pair(x1 to y1, x2 to y2))
            for ((a, b) in legs) {
                val steps = max(1, ms / 8)
                for (i in 1..steps) {
                    Thread.sleep(max(1, ms / steps).toLong())
                    val t = i.toFloat() / steps
                    touch.move(id, px(a.first + (b.first - a.first) * t), py(a.second + (b.second - a.second) * t))
                }
            }
        } finally {
            touch.up(id); ctx.pids -= id
        }
    }

    private fun swipeControl(ctx: JobCtx, c: Control) {
        val (sx, sy) = synchronized(this) {
            if (c.fromPointer && cursorMode) (cursorX / screenW) to (cursorY / screenH) else c.x to c.y
        }
        val ex = c.x2
        val ey = if (c.horizontalOnly) sy else c.y2
        if (c.tapBefore) { tap(ctx, sx, sy, c.tapDuration); Thread.sleep(30) }
        swipe(ctx, sx, sy, ex, ey, c.duration, c.roundTrip)
        if (c.tapAfter) { Thread.sleep(30); tap(ctx, if (c.roundTrip) sx else ex, if (c.roundTrip) sy else ey, c.tapDuration) }
    }

    /**
     * Ultra-fast tapping. A new finger lands every [Control.duration] ms (alternating between
     * the main and second point) and each finger lifts after [Control.tapDuration] ms, so with a
     * duration longer than the interval fingers overlap and the rate isn't limited by one finger.
     */
    private fun crazyTap(ctx: JobCtx, c: Control) {
        val fingers = ArrayDeque<Pair<Int, Long>>()
        var second = false
        var next = SystemClock.uptimeMillis()
        try {
            while (true) {
                var now = SystemClock.uptimeMillis()
                while (fingers.isNotEmpty() && fingers.first().second <= now) {
                    val (id, _) = fingers.removeFirst(); touch.up(id); ctx.pids -= id
                }
                if (now >= next) {
                    if (fingers.size >= 3) { val (id, _) = fingers.removeFirst(); touch.up(id); ctx.pids -= id }
                    val id = if (second) touch.down(px(c.x2), py(c.y2)) else touch.down(px(c.x), py(c.y))
                    if (id >= 0) { ctx.pids += id; fingers.addLast(id to now + max(5, c.tapDuration)) }
                    second = !second
                    next = now + max(5, c.duration)
                }
                now = SystemClock.uptimeMillis()
                val wake = min(next, fingers.firstOrNull()?.second ?: next)
                Thread.sleep(max(1L, wake - now))
            }
        } finally {
            fingers.forEach { touch.up(it.first) }
        }
    }

    private fun longPressGroup(ctx: JobCtx, c: Control) {
        val start = SystemClock.uptimeMillis()
        var prev = -1
        for (stage in c.ops.filter { it.hasPoint }.sortedBy { it.duration }) {
            val wait = start + stage.duration - SystemClock.uptimeMillis()
            if (wait > 0) Thread.sleep(wait)
            if (c.lastOnly && prev >= 0) { touch.up(prev); ctx.pids -= prev }
            val id = touch.down(px(stage.x), py(stage.y))
            if (id >= 0) { ctx.pids += id; prev = id }
        }
        Thread.sleep(Long.MAX_VALUE) // hold until the key is released
    }

    private fun runOps(ctx: JobCtx, ops: List<MacroOp>) {
        for (op in ops) {
            checkInterrupted()
            when (op.type) {
                OpType.TAP -> tap(ctx, op.x, op.y, op.duration)
                OpType.SWIPE -> swipe(ctx, op.x, op.y, op.x2, op.y2, op.duration, op.roundTrip)
                OpType.SLEEP -> Thread.sleep(max(1, op.duration).toLong())
                OpType.PRESS -> if (op.slot !in ctx.slots) {
                    val id = touch.down(px(op.x), py(op.y))
                    if (id >= 0) { ctx.slots[op.slot] = id; ctx.pids += id }
                }
                OpType.RELEASE -> ctx.slots.remove(op.slot)?.let { touch.up(it); ctx.pids -= it }
                OpType.MOUSE_TOGGLE -> synchronized(this) { toggleCursor() }
                OpType.RESET_VIEW -> synchronized(this) { resetView() }
                OpType.RESET_JOYSTICK -> synchronized(this) { resetJoysticks() }
                OpType.RECOIL_START -> synchronized(this) { macroRecoil = true }
                OpType.RECOIL_STOP -> synchronized(this) { macroRecoil = false }
            }
        }
    }
}
