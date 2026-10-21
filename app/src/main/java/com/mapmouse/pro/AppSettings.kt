package com.mapmouse.pro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A game in "My games": launching it applies its profile and optional screen override. */
class Game(
    var pkg: String,
    var label: String,
    var profileId: String? = null,
    var width: Int = 0,
    var height: Int = 0,
    var density: Int = 0,
) {
    fun toJson() = JSONObject().put("pkg", pkg).put("label", label).put("profileId", profileId ?: "")
        .put("width", width).put("height", height).put("density", density)

    companion object {
        fun fromJson(o: JSONObject) = Game(o.getString("pkg"), o.optString("label", o.getString("pkg")),
            o.optString("profileId").ifBlank { null }, o.optInt("width"), o.optInt("height"), o.optInt("density"))
    }
}

/** Global (not per-profile) settings, stored as one JSON blob. */
class AppSettings(
    // mouse
    var pointerStyle: Int = 0, // 0..4 built-in, 5 = custom image
    var pointerSize: Int = 22,
    var pointerColor: Int = 0xFFFFFFFF.toInt(),
    var pollHz: Int = 0,
    var smooth: Float = 0f,
    // HUD
    var showHud: Boolean = false,
    var hudColor: Int = 0xFFFFFFFF.toInt(),
    var hudAlpha: Float = 0.6f,
    var showTouches: Boolean = false,
    var showRcPanel: Boolean = true,
    /** Opacity of the recoil pill. */
    var rcAlpha: Float = 0.8f,
    var bubbleAlpha: Float = 0.9f,
    // crosshair
    var crosshair: Boolean = false,
    var crossStyle: Int = 0, // 0 cross, 1 dot, 2 circle, 3 cross+dot, 4 T
    var crossColor: Int = 0xFF00FF00.toInt(),
    var crossSize: Int = 20,
    var crossAlpha: Float = 0.9f,
    var crossOffsetX: Int = 0,
    var crossOffsetY: Int = 0,
    var crossHideAiming: Boolean = true,
    // device monitor
    var monitor: Boolean = false,
    var monFixed: Boolean = false,
    var monRefreshMs: Int = 1000,
    var monFps: Boolean = true,
    var monCpu: Boolean = true,
    var monTemp: Boolean = true,
    var monBattery: Boolean = true,
    var monNet: Boolean = false,
    var monPing: Boolean = false,
    var monTime: Boolean = true,
    var monPlayTime: Boolean = false,
    // advanced
    var touchCompat: Boolean = false,
    /** Lift the aim finger when the mouse stops, so the screen can be touched in-game. */
    var touchFriendly: Boolean = true,
    /** Pause mapping and hide overlays when you leave the game; resume when you return. */
    var autoHide: Boolean = true,
    var padSpeed: Float = 1.2f,
    var deadzone: Float = 0.15f,
    var autoSwitch: Boolean = true,
    var games: MutableList<Game> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pointerStyle", pointerStyle); put("pointerSize", pointerSize); put("pointerColor", pointerColor)
        put("pollHz", pollHz); put("smooth", smooth.toDouble())
        put("showHud", showHud); put("hudColor", hudColor); put("hudAlpha", hudAlpha.toDouble())
        put("showTouches", showTouches); put("showRcPanel", showRcPanel); put("rcAlpha", rcAlpha.toDouble()); put("bubbleAlpha", bubbleAlpha.toDouble())
        put("crosshair", crosshair); put("crossStyle", crossStyle); put("crossColor", crossColor)
        put("crossSize", crossSize); put("crossAlpha", crossAlpha.toDouble())
        put("crossOffsetX", crossOffsetX); put("crossOffsetY", crossOffsetY); put("crossHideAiming", crossHideAiming)
        put("monitor", monitor); put("monFixed", monFixed); put("monRefreshMs", monRefreshMs)
        put("monFps", monFps); put("monCpu", monCpu); put("monTemp", monTemp); put("monBattery", monBattery)
        put("monNet", monNet); put("monPing", monPing); put("monTime", monTime); put("monPlayTime", monPlayTime)
        put("touchFriendly", touchFriendly); put("autoHide", autoHide)
        put("touchCompat", touchCompat); put("padSpeed", padSpeed.toDouble()); put("deadzone", deadzone.toDouble())
        put("autoSwitch", autoSwitch)
        put("games", JSONArray().apply { games.forEach { put(it.toJson()) } })
    }

    /** The part the input server needs. */
    fun serverOptions(): JSONObject = JSONObject().put("pollHz", pollHz).put("smooth", smooth.toDouble())
        .put("touchCompat", touchCompat).put("showTouches", showTouches).put("touchFriendly", touchFriendly)
        .put("padSpeed", padSpeed.toDouble()).put("deadzone", deadzone.toDouble())

    fun save(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("app", toJson().toString()).apply()
        cached = this
    }

    companion object {
        private var cached: AppSettings? = null

        fun get(ctx: Context): AppSettings = cached ?: load(ctx).also { cached = it }

        private fun load(ctx: Context): AppSettings {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val o = runCatching { JSONObject(prefs.getString("app", "{}")!!) }.getOrDefault(JSONObject())
            val s = AppSettings()
            fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
            s.pointerStyle = o.optInt("pointerStyle", 0)
            // Older versions kept cursor size/color as separate prefs.
            s.pointerSize = o.optInt("pointerSize", prefs.getInt("cursorSize", 22))
            s.pointerColor = o.optInt("pointerColor", prefs.getInt("cursorColor", 0xFFFFFFFF.toInt()))
            s.pollHz = o.optInt("pollHz", 0); s.smooth = f("smooth", 0f)
            s.showHud = o.optBoolean("showHud", false); s.hudColor = o.optInt("hudColor", 0xFFFFFFFF.toInt())
            s.hudAlpha = f("hudAlpha", .6f); s.showTouches = o.optBoolean("showTouches")
            s.showRcPanel = o.optBoolean("showRcPanel", true); s.rcAlpha = f("rcAlpha", .8f); s.bubbleAlpha = f("bubbleAlpha", .9f)
            s.crosshair = o.optBoolean("crosshair"); s.crossStyle = o.optInt("crossStyle", 0)
            s.crossColor = o.optInt("crossColor", 0xFF00FF00.toInt()); s.crossSize = o.optInt("crossSize", 20)
            s.crossAlpha = f("crossAlpha", .9f); s.crossOffsetX = o.optInt("crossOffsetX"); s.crossOffsetY = o.optInt("crossOffsetY")
            s.crossHideAiming = o.optBoolean("crossHideAiming", true)
            s.monitor = o.optBoolean("monitor"); s.monFixed = o.optBoolean("monFixed")
            s.monRefreshMs = o.optInt("monRefreshMs", 1000)
            s.monFps = o.optBoolean("monFps", true); s.monCpu = o.optBoolean("monCpu", true)
            s.monTemp = o.optBoolean("monTemp", true); s.monBattery = o.optBoolean("monBattery", true)
            s.monNet = o.optBoolean("monNet"); s.monPing = o.optBoolean("monPing")
            s.monTime = o.optBoolean("monTime", true); s.monPlayTime = o.optBoolean("monPlayTime")
            s.touchFriendly = o.optBoolean("touchFriendly", true); s.autoHide = o.optBoolean("autoHide", true)
            s.touchCompat = o.optBoolean("touchCompat"); s.padSpeed = f("padSpeed", 1.2f); s.deadzone = f("deadzone", .15f)
            s.autoSwitch = o.optBoolean("autoSwitch", true)
            val g = o.optJSONArray("games") ?: JSONArray()
            for (i in 0 until g.length()) runCatching { s.games += Game.fromJson(g.getJSONObject(i)) }
            return s
        }
    }
}
