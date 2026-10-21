package com.mapmouse.pro.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Keymap data shared by the app (editor) and the input server.
 * Positions are normalized: x in [0,1] of screen width, y in [0,1] of screen height.
 * Radii/distances are normalized to screen height.
 */
enum class ControlType(val label: String) {
    TAP("Touch"),
    JOYSTICK("Joystick"),
    VIEW("View / Aim (FPS)"),
    FREE_LOOK("Free look"),
    SWIPE("Swipe"),
    SCROLL("Mouse scroll"),
    REPEAT("Rapid tap"),
    CRAZY_TAP("Crazy tap"),
    MACRO("Macro"),
    LONG_PRESS_GROUP("Long press group"),
    GROUP("Trigger group (hold)"),
}

/** How a TAP control turns key presses into touches. */
enum class TapMode(val label: String) {
    HOLD("Press (hold)"),
    TAP("Tap"),
    DOWN_UP("Down/Up tap"),
    LONG_PRESS("Long press trigger"),
    UP("Up trigger"),
}

/** When a control's side effect (e.g. switching the mouse pointer) fires. */
enum class Edge(val label: String) { DOWN("Key down"), UP("Key up"), BOTH("Down/Up") }

enum class MacroTrigger(val label: String) {
    DOWN("Key down"), UP("Key up"), LOOP("Loop while held"), DOWN_UP("Down & up")
}

enum class OpType(val label: String) {
    TAP("Tap"), PRESS("Press"), RELEASE("Release"), SWIPE("Swipe"), SLEEP("Sleep"),
    MOUSE_TOGGLE("Mouse display"), RESET_VIEW("Reset view"), RESET_JOYSTICK("Reset joystick"),
    RECOIL_START("Recoil start"), RECOIL_STOP("Recoil stop"),
}

/** Where the pointer appears when cursor mode turns on. */
enum class CursorShowPos(val label: String) { CENTER("Center"), LAST("Last hidden"), CUSTOM("Custom") }

private fun JSONObject.f(k: String, d: Float) = optDouble(k, d.toDouble()).toFloat()
private inline fun <reified E : Enum<E>> JSONObject.e(k: String, d: E): E =
    runCatching { enumValueOf<E>(optString(k)) }.getOrDefault(d)

class MacroOp(
    var type: OpType,
    var x: Float = 0.5f, var y: Float = 0.5f,
    var x2: Float = 0.6f, var y2: Float = 0.5f,
    var duration: Int = 50,
    /** PRESS/RELEASE pair through the same slot so a finger can stay down across ops. */
    var slot: Int = 0,
    /** SWIPE: go to the end point and back again. */
    var roundTrip: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("type", type.name); put("x", x.toDouble()); put("y", y.toDouble())
        put("x2", x2.toDouble()); put("y2", y2.toDouble()); put("duration", duration); put("slot", slot)
        put("roundTrip", roundTrip)
    }

    val hasPoint get() = type == OpType.TAP || type == OpType.PRESS || type == OpType.SWIPE

    fun describe(): String = when (type) {
        OpType.TAP -> "Tap ${duration}ms"
        OpType.PRESS -> "Press #$slot"
        OpType.RELEASE -> "Release #$slot"
        OpType.SWIPE -> "Swipe ${duration}ms" + if (roundTrip) " ⇄" else ""
        OpType.SLEEP -> "Sleep ${duration}ms"
        else -> type.label
    }

    companion object {
        fun fromJson(o: JSONObject) = MacroOp(
            o.e("type", OpType.SLEEP), o.f("x", .5f), o.f("y", .5f), o.f("x2", .6f), o.f("y2", .5f),
            o.optInt("duration", 50), o.optInt("slot", 0), o.optBoolean("roundTrip", false),
        )
    }
}

class Control(
    var type: ControlType,
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var key: Int = Keys.NONE,
    /** Group this control belongs to. 0 = base layer, 1..3 only while a GROUP key is held. */
    var group: Int = 0,
    var name: String = "",

    // TAP
    var tapMode: TapMode = TapMode.HOLD,
    /** TAP/CRAZY_TAP: how long each tap holds the finger down (ms). */
    var tapDuration: Int = 60,
    /** TAP long-press trigger threshold (ms). */
    var longPressMs: Int = 300,
    /** Pressing this also switches the mouse pointer (e.g. the backpack button). */
    var cursorToggle: Boolean = false,
    var cursorEdge: Edge = Edge.DOWN,
    var resetView: Boolean = false,
    var resetJoystick: Boolean = false,
    /** Holding this control fires recoil control (in addition to the profile's fire key). */
    var triggersRecoil: Boolean = false,

    // JOYSTICK
    var keyUp: Int = Keys.NONE, var keyDown: Int = Keys.NONE,
    var keyLeft: Int = Keys.NONE, var keyRight: Int = Keys.NONE,
    /** JOYSTICK: stick travel. VIEW/FREE_LOOK: distance before re-centering. SCROLL: drag length. */
    var radius: Float = 0.12f,
    /** JOYSTICK: key that switches to a smaller radius (walk). */
    var altKey: Int = Keys.NONE,
    var altPercent: Float = 0.5f,
    /** true = altKey toggles, false = only while held. Also used for VIEW's alt sensitivity. */
    var altToggle: Boolean = false,
    /** JOYSTICK: intermediate moves when the stick changes direction (smoother for some games). */
    var steps: Int = 1,

    // VIEW / FREE_LOOK / SCROLL
    var sensX: Float = 1f, var sensY: Float = 1f,
    /** VIEW: key switching to the alternate (e.g. scoped) sensitivity. */
    var altSensKey: Int = Keys.NONE,
    var altSensX: Float = 0.5f, var altSensY: Float = 0.5f,
    /** VIEW: only lift the finger at the edge of the range (otherwise also after [idleReleaseMs] idle). */
    var edgeReleaseOnly: Boolean = true,
    var idleReleaseMs: Int = 300,
    /** VIEW: mouse acceleration. */
    var accel: Boolean = false,
    var accelCoef: Float = 0.5f,
    var accelMinSpeed: Float = 2f,
    var accelLimit: Float = 3f,
    /** VIEW: key that lifts and re-centers the view finger. */
    var resetKey: Int = Keys.NONE,
    /** SCROLL: invert direction. */
    var invert: Boolean = false,

    // SWIPE end point / CRAZY_TAP alternate finger
    var x2: Float = 0.6f, var y2: Float = 0.5f,
    /** SWIPE duration / REPEAT and CRAZY_TAP interval / SCROLL auto-release, in ms. */
    var duration: Int = 120,
    var roundTrip: Boolean = false,
    var fromPointer: Boolean = false,
    var horizontalOnly: Boolean = false,
    var tapBefore: Boolean = false,
    var tapAfter: Boolean = false,
    /** CRAZY_TAP: only crazy-tap when the active weapon has crazy tap enabled. */
    var conditional: Boolean = false,

    // MACRO
    var trigger: MacroTrigger = MacroTrigger.DOWN,
    var ops: MutableList<MacroOp> = mutableListOf(),
    /** MACRO: stops this macro; with [disableOnTerminate] it also toggles the macro off/on. */
    var terminateKey: Int = Keys.NONE,
    var disableOnTerminate: Boolean = false,

    // LONG_PRESS_GROUP: ops are stages (point + delay in `duration`)
    /** true = each stage replaces the previous finger; false = fingers accumulate. */
    var lastOnly: Boolean = true,

    /** GROUP: which group is enabled while this key is held. */
    var targetGroup: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name); put("x", x.toDouble()); put("y", y.toDouble())
        put("key", key); put("group", group); put("name", name)
        put("tapMode", tapMode.name); put("tapDuration", tapDuration); put("longPressMs", longPressMs)
        put("cursorToggle", cursorToggle); put("cursorEdge", cursorEdge.name)
        put("resetView", resetView); put("resetJoystick", resetJoystick); put("triggersRecoil", triggersRecoil)
        put("keyUp", keyUp); put("keyDown", keyDown); put("keyLeft", keyLeft); put("keyRight", keyRight)
        put("radius", radius.toDouble()); put("altKey", altKey); put("altPercent", altPercent.toDouble())
        put("altToggle", altToggle); put("steps", steps)
        put("sensX", sensX.toDouble()); put("sensY", sensY.toDouble())
        put("altSensKey", altSensKey); put("altSensX", altSensX.toDouble()); put("altSensY", altSensY.toDouble())
        put("edgeReleaseOnly", edgeReleaseOnly); put("idleReleaseMs", idleReleaseMs)
        put("accel", accel); put("accelCoef", accelCoef.toDouble())
        put("accelMinSpeed", accelMinSpeed.toDouble()); put("accelLimit", accelLimit.toDouble())
        put("resetKey", resetKey); put("invert", invert)
        put("x2", x2.toDouble()); put("y2", y2.toDouble()); put("duration", duration)
        put("roundTrip", roundTrip); put("fromPointer", fromPointer); put("horizontalOnly", horizontalOnly)
        put("tapBefore", tapBefore); put("tapAfter", tapAfter); put("conditional", conditional)
        put("trigger", trigger.name); put("terminateKey", terminateKey); put("disableOnTerminate", disableOnTerminate)
        put("lastOnly", lastOnly); put("targetGroup", targetGroup)
        put("ops", JSONArray().apply { ops.forEach { put(it.toJson()) } })
    }

    /** Every key code this control listens to. */
    fun keys(): List<Int> = when (type) {
        ControlType.JOYSTICK -> listOf(keyUp, keyDown, keyLeft, keyRight, altKey)
        ControlType.VIEW -> listOf(altSensKey, resetKey)
        ControlType.SCROLL -> emptyList()
        ControlType.MACRO -> listOf(key, terminateKey)
        else -> listOf(key)
    }.filter { it != Keys.NONE }

    fun copy(): Control = fromJson(toJson())

    fun title(): String = name.ifBlank { type.label }

    companion object {
        fun fromJson(o: JSONObject): Control {
            val c = Control(o.e("type", ControlType.TAP))
            c.x = o.f("x", .5f); c.y = o.f("y", .5f)
            c.key = o.optInt("key", Keys.NONE); c.group = o.optInt("group", 0); c.name = o.optString("name", "")
            c.tapMode = o.e("tapMode", TapMode.HOLD); c.tapDuration = o.optInt("tapDuration", 60)
            c.longPressMs = o.optInt("longPressMs", 300)
            c.cursorToggle = o.optBoolean("cursorToggle"); c.cursorEdge = o.e("cursorEdge", Edge.DOWN)
            c.resetView = o.optBoolean("resetView"); c.resetJoystick = o.optBoolean("resetJoystick")
            c.triggersRecoil = o.optBoolean("triggersRecoil")
            c.keyUp = o.optInt("keyUp", Keys.NONE); c.keyDown = o.optInt("keyDown", Keys.NONE)
            c.keyLeft = o.optInt("keyLeft", Keys.NONE); c.keyRight = o.optInt("keyRight", Keys.NONE)
            c.radius = o.f("radius", .12f); c.altKey = o.optInt("altKey", Keys.NONE)
            c.altPercent = o.f("altPercent", .5f); c.altToggle = o.optBoolean("altToggle")
            c.steps = o.optInt("steps", 1)
            c.sensX = o.f("sensX", 1f); c.sensY = o.f("sensY", 1f)
            c.altSensKey = o.optInt("altSensKey", Keys.NONE)
            c.altSensX = o.f("altSensX", .5f); c.altSensY = o.f("altSensY", .5f)
            c.edgeReleaseOnly = o.optBoolean("edgeReleaseOnly", true); c.idleReleaseMs = o.optInt("idleReleaseMs", 300)
            c.accel = o.optBoolean("accel"); c.accelCoef = o.f("accelCoef", .5f)
            c.accelMinSpeed = o.f("accelMinSpeed", 2f); c.accelLimit = o.f("accelLimit", 3f)
            c.resetKey = o.optInt("resetKey", Keys.NONE); c.invert = o.optBoolean("invert")
            c.x2 = o.f("x2", .6f); c.y2 = o.f("y2", .5f); c.duration = o.optInt("duration", 120)
            c.roundTrip = o.optBoolean("roundTrip"); c.fromPointer = o.optBoolean("fromPointer")
            c.horizontalOnly = o.optBoolean("horizontalOnly")
            c.tapBefore = o.optBoolean("tapBefore"); c.tapAfter = o.optBoolean("tapAfter")
            c.conditional = o.optBoolean("conditional")
            c.trigger = o.e("trigger", MacroTrigger.DOWN)
            c.terminateKey = o.optInt("terminateKey", Keys.NONE); c.disableOnTerminate = o.optBoolean("disableOnTerminate")
            c.lastOnly = o.optBoolean("lastOnly", true); c.targetGroup = o.optInt("targetGroup", 1)
            val arr = o.optJSONArray("ops") ?: JSONArray()
            for (i in 0 until arr.length()) c.ops.add(MacroOp.fromJson(arr.getJSONObject(i)))
            return c
        }
    }
}

/** One point of a dynamic recoil curve: strength from time [t] (ms since firing started). */
class CurvePoint(var t: Int, var v: Float, var h: Float) {
    fun toJson() = JSONObject().put("t", t).put("v", v.toDouble()).put("h", h.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = CurvePoint(o.optInt("t"), o.f("v", 0f), o.f("h", 0f))
    }
}

/** A recoil-control config (usually one per weapon). */
class Weapon(
    var name: String,
    /** Downward pull in screen-heights per second. */
    var v: Float = 0.06f,
    /** Sideways pull in screen-heights per second (negative = left). */
    var h: Float = 0f,
    /** Selecting key (e.g. the weapon slot key). Doesn't consume the key. */
    var key: Int = Keys.NONE,
    /** Conditional crazy tap: CRAZY_TAP controls marked conditional only crazy-tap with this weapon. */
    var crazy: Boolean = false,
    /** Optional curve over time; when empty the constant v/h are used. */
    var curve: MutableList<CurvePoint> = mutableListOf(),
) {
    fun toJson() = JSONObject().apply {
        put("name", name); put("v", v.toDouble()); put("h", h.toDouble()); put("key", key); put("crazy", crazy)
        put("curve", JSONArray().apply { curve.forEach { put(it.toJson()) } })
    }

    /** Strength at [ms] after firing started, linearly interpolated between curve points. */
    fun at(ms: Long): Pair<Float, Float> {
        if (curve.isEmpty()) return v to h
        val pts = curve.sortedBy { it.t }
        if (ms <= pts.first().t) return pts.first().v to pts.first().h
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (ms <= b.t) {
                val k = (ms - a.t).toFloat() / (b.t - a.t).coerceAtLeast(1)
                return (a.v + (b.v - a.v) * k) to (a.h + (b.h - a.h) * k)
            }
        }
        return pts.last().v to pts.last().h
    }

    companion object {
        fun fromJson(o: JSONObject): Weapon {
            val w = Weapon(o.optString("name", "Weapon"), o.f("v", .06f), o.f("h", 0f),
                o.optInt("key", Keys.NONE), o.optBoolean("crazy"))
            val arr = o.optJSONArray("curve") ?: JSONArray()
            for (i in 0 until arr.length()) w.curve.add(CurvePoint.fromJson(arr.getJSONObject(i)))
            return w
        }
    }
}

class Profile(
    var id: String,
    var name: String,
    var controls: MutableList<Control> = mutableListOf(),
    /** Toggles mapping on/off (grab/release keyboard & mouse). */
    var toggleKey: Int = Keys.KEY_PAUSE,
    /** Toggles between camera (view) mode and a free on-screen cursor. */
    var cursorKey: Int = Keys.KEY_GRAVE,
    /** false = the cursor key toggles cursor mode; true = cursor only while the key is held. */
    var cursorHold: Boolean = false,
    var cursorShowPos: CursorShowPos = CursorShowPos.CENTER,
    var cursorX: Float = 0.5f, var cursorY: Float = 0.5f,
    /** Force-stops all running macros. */
    var terminateKey: Int = Keys.NONE,
    var cursorSpeed: Float = 1.5f,
    /** Shows/hides the on-screen key labels while playing. */
    var hudKey: Int = Keys.KEY_SCROLLLOCK,
    /** Lets the keyboard type into the phone (mouse keeps playing) until pressed again. */
    var typingKey: Int = Keys.NONE,
    /** Shows/hides the floating button. */
    var bubbleKey: Int = Keys.NONE,

    // ---- recoil control ----
    var recoilEnabled: Boolean = false,
    var recoilKey: Int = Keys.BTN_LEFT,
    var recoilDelay: Int = 80,
    /** Multiplies every weapon's values. */
    var recoilGlobal: Float = 1f,
    var weapons: MutableList<Weapon> = defaultSlots(),
    var activeWeapon: Int = 0,
    /** Only pull while aiming (aim key held, or toggled when [aimToggle]). */
    var recoilOnlyAiming: Boolean = false,
    var aimKey: Int = Keys.BTN_RIGHT,
    var aimToggle: Boolean = false,
    /** Horizontal recoil only while held / toggled by this key; NONE = always. */
    var horizontalKey: Int = Keys.NONE,
    var horizontalToggle: Boolean = true,
    /** Stance keys and their multipliers (crouch/prone change recoil in most shooters). */
    var crouchKey: Int = Keys.KEY_C,
    var proneKey: Int = Keys.KEY_Z,
    var crouchCoef: Float = 0.8f,
    var proneCoef: Float = 0.6f,
    /** Raise/lower the active weapon's vertical value (Ctrl+wheel also works). */
    var rcUpKey: Int = Keys.NONE,
    var rcDownKey: Int = Keys.NONE,
    var rcStep: Float = 0.005f,
    var rcPanelKey: Int = Keys.NONE,

    // ---- crazy tap ----
    /** Globally enables/disables crazy tap controls while playing. */
    var crazyKey: Int = Keys.NONE,
) {
    private val weapon get() = weapons.getOrNull(activeWeapon) ?: weapons.first()

    /** Vertical/horizontal value of the active weapon (what the simple sliders edit). */
    var recoilStrength: Float
        get() = weapon.v
        set(v) { weapon.v = v }
    var recoilStrengthX: Float
        get() = weapon.h
        set(v) { weapon.h = v }

    fun activeWeaponObj(): Weapon = weapon

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("toggleKey", toggleKey); put("cursorKey", cursorKey); put("cursorHold", cursorHold)
        put("cursorShowPos", cursorShowPos.name); put("cursorX", cursorX.toDouble()); put("cursorY", cursorY.toDouble())
        put("terminateKey", terminateKey); put("cursorSpeed", cursorSpeed.toDouble())
        put("hudKey", hudKey); put("typingKey", typingKey); put("bubbleKey", bubbleKey)
        put("recoilEnabled", recoilEnabled); put("recoilKey", recoilKey); put("recoilDelay", recoilDelay)
        put("recoilGlobal", recoilGlobal.toDouble())
        put("weapons", JSONArray().apply { weapons.forEach { put(it.toJson()) } }); put("activeWeapon", activeWeapon)
        put("recoilOnlyAiming", recoilOnlyAiming); put("aimKey", aimKey); put("aimToggle", aimToggle)
        put("horizontalKey", horizontalKey); put("horizontalToggle", horizontalToggle)
        put("crouchKey", crouchKey); put("proneKey", proneKey)
        put("crouchCoef", crouchCoef.toDouble()); put("proneCoef", proneCoef.toDouble())
        put("rcUpKey", rcUpKey); put("rcDownKey", rcDownKey); put("rcStep", rcStep.toDouble()); put("rcPanelKey", rcPanelKey)
        put("crazyKey", crazyKey)
        put("controls", JSONArray().apply { controls.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Profile {
            val p = Profile(o.getString("id"), o.optString("name", "Profile"))
            p.toggleKey = o.optInt("toggleKey", Keys.KEY_PAUSE)
            p.cursorKey = o.optInt("cursorKey", Keys.KEY_GRAVE)
            p.cursorHold = o.optBoolean("cursorHold", false)
            p.cursorShowPos = o.e("cursorShowPos", CursorShowPos.CENTER)
            p.cursorX = o.f("cursorX", .5f); p.cursorY = o.f("cursorY", .5f)
            p.terminateKey = o.optInt("terminateKey", Keys.NONE)
            p.cursorSpeed = o.f("cursorSpeed", 1.5f)
            p.hudKey = o.optInt("hudKey", Keys.KEY_SCROLLLOCK)
            p.typingKey = o.optInt("typingKey", Keys.NONE)
            p.bubbleKey = o.optInt("bubbleKey", Keys.NONE)
            p.recoilEnabled = o.optBoolean("recoilEnabled", false)
            p.recoilKey = o.optInt("recoilKey", Keys.BTN_LEFT)
            p.recoilDelay = o.optInt("recoilDelay", 80)
            p.recoilGlobal = o.f("recoilGlobal", 1f)
            val ws = o.optJSONArray("weapons")
            if (ws != null && ws.length() > 0) {
                p.weapons = (0 until ws.length()).map { Weapon.fromJson(ws.getJSONObject(it)) }.toMutableList()
            } else {
                // Profiles from before weapon configs: keep their single recoil setting.
                p.weapons = mutableListOf(Weapon("Default", o.f("recoilStrength", .06f), o.f("recoilStrengthX", 0f)))
            }
            // Older profiles had a single recoil config: expand to the 12 slots switched with F1–F12,
            // keeping its values in slot 0. F12/F9 were the old mapping/HUD hotkeys: move those off.
            if (p.weapons.size == 1 && p.weapons[0].key == Keys.NONE) {
                val first = p.weapons[0]
                p.weapons = defaultSlots().also { it[0].v = first.v; it[0].h = first.h; it[0].curve = first.curve }
                if (p.toggleKey == Keys.KEY_F12) p.toggleKey = Keys.KEY_PAUSE
                if (p.hudKey == Keys.KEY_F9) p.hudKey = Keys.KEY_SCROLLLOCK
            }
            p.activeWeapon = o.optInt("activeWeapon", 0).coerceIn(0, p.weapons.size - 1)
            p.recoilOnlyAiming = o.optBoolean("recoilOnlyAiming")
            p.aimKey = o.optInt("aimKey", Keys.BTN_RIGHT); p.aimToggle = o.optBoolean("aimToggle")
            p.horizontalKey = o.optInt("horizontalKey", Keys.NONE)
            p.horizontalToggle = o.optBoolean("horizontalToggle", true)
            p.crouchKey = o.optInt("crouchKey", Keys.KEY_C); p.proneKey = o.optInt("proneKey", Keys.KEY_Z)
            p.crouchCoef = o.f("crouchCoef", .8f); p.proneCoef = o.f("proneCoef", .6f)
            p.rcUpKey = o.optInt("rcUpKey", Keys.NONE); p.rcDownKey = o.optInt("rcDownKey", Keys.NONE)
            p.rcStep = o.f("rcStep", .005f); p.rcPanelKey = o.optInt("rcPanelKey", Keys.NONE)
            p.crazyKey = o.optInt("crazyKey", Keys.NONE)
            val arr = o.optJSONArray("controls") ?: JSONArray()
            for (i in 0 until arr.length()) p.controls.add(Control.fromJson(arr.getJSONObject(i)))
            return p
        }

        /** Twelve recoil slots selected with F1–F12, named like Slot0…Slot11. */
        fun defaultSlots(): MutableList<Weapon> =
            Keys.F_KEYS.mapIndexed { i, k -> Weapon("Slot$i", 0.06f, 0f, k) }.toMutableList()

        /** A typical shooter layout to start from. */
        fun default(id: String, name: String) = Profile(id, name).apply {
            controls += Control(ControlType.JOYSTICK, 0.17f, 0.72f, radius = 0.11f, altKey = Keys.KEY_LEFTSHIFT,
                keyUp = Keys.KEY_W, keyDown = Keys.KEY_S, keyLeft = Keys.KEY_A, keyRight = Keys.KEY_D)
            controls += Control(ControlType.VIEW, 0.62f, 0.42f, radius = 0.2f)
            controls += Control(ControlType.TAP, 0.86f, 0.66f, key = Keys.BTN_LEFT, name = "Fire")
            controls += Control(ControlType.TAP, 0.93f, 0.48f, key = Keys.BTN_RIGHT, name = "Aim", tapMode = TapMode.TAP)
            controls += Control(ControlType.TAP, 0.93f, 0.78f, key = Keys.KEY_SPACE, name = "Jump")
            controls += Control(ControlType.TAP, 0.80f, 0.88f, key = Keys.KEY_C, name = "Crouch", tapMode = TapMode.TAP)
            controls += Control(ControlType.TAP, 0.72f, 0.88f, key = Keys.KEY_R, name = "Reload", tapMode = TapMode.TAP)
            controls += Control(ControlType.TAP, 0.50f, 0.90f, key = Keys.KEY_F, name = "Pick up", tapMode = TapMode.TAP)
            controls += Control(ControlType.TAP, 0.06f, 0.92f, key = Keys.KEY_TAB, name = "Bag",
                tapMode = TapMode.TAP, cursorToggle = true)
        }
    }
}
