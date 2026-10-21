package com.mapmouse.pro.ui

import android.content.Intent
import android.graphics.Color
import com.mapmouse.pro.AppSettings
import com.mapmouse.pro.ServerConnection
import android.widget.LinearLayout
import android.widget.TextView
import com.mapmouse.pro.Store

/** Global settings pages: mouse & pointer, HUD & crosshair, device monitor, advanced. */
class SettingsActivity : PageActivity(), ServerConnection.Listener {

    private val s get() = AppSettings.get(this)
    private var lastState: ServerConnection.State? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        ServerConnection.addListener(this)
    }

    override fun onDestroy() {
        ServerConnection.removeListener(this)
        super.onDestroy()
    }

    override fun onState(state: ServerConnection.State) {
        if (state != lastState) { lastState = state; if (intent.getStringExtra(EXTRA_PAGE) == PAGE_ACTIVATION) refresh() }
    }

    override fun build() {
        when (intent.getStringExtra(EXTRA_PAGE)) {
            PAGE_ACTIVATION -> activation()
            PAGE_PERMISSIONS -> permissions()
            PAGE_HUD -> hud()
            PAGE_MONITOR -> monitor()
            PAGE_ADVANCED -> advanced()
            else -> mouse()
        }
    }

    private val colors = listOf(
        0xFFFFFFFF.toInt() to "White", 0xFF000000.toInt() to "Black", 0xFFFF3B30.toInt() to "Red",
        0xFF34C759.toInt() to "Green", 0xFF0A84FF.toInt() to "Blue", 0xFFFFD60A.toInt() to "Yellow",
        0xFFFF2DCB.toInt() to "Pink", 0xFF00E5FF.toInt() to "Cyan",
    )

    private fun colorRow(current: Int, set: (Int) -> Unit) = scroll(*colors.map { (c, n) ->
        chip(if (current == c) "[$n]" else n) { set(c); saveOptions(); refresh() }.apply { setTextColor(if (c == Color.BLACK) Color.GRAY else c) }
    }.toTypedArray())

    private fun mouse() {
        title("Mouse & pointer")
        val st = s
        val ptr = section("Pointer style")
        val styles = listOf("Arrow", "Dot", "Ring", "Cross", "Outline", "Custom image")
        ptr.addView(scroll(*styles.mapIndexed { i, n ->
            chip(if (st.pointerStyle == i) "[$n]" else n) {
                if (i == 5 && !CursorView.pointerFile(this).exists()) pickImage()
                st.pointerStyle = i; saveOptions(); refresh()
            }
        }.toTypedArray()))
        ptr.addView(chip("Choose custom image…") { pickImage() })
        note(ptr, "A PNG with a transparent background works best; its top-left corner is the click point.")
        ptr.addView(stepSlider("Size", 12f, 64f, 1f, st.pointerSize.toFloat(), { "${it.toInt()} dp" }) { st.pointerSize = it.toInt(); saveOptions() })
        ptr.addView(label("Color"))
        ptr.addView(colorRow(st.pointerColor) { st.pointerColor = it })

        val sens = section("Pointer sensitivity (active profile)")
        val p = Store.active(this)
        sens.addView(stepSlider("Pointer speed", 0.3f, 5f, 0.1f, p.cursorSpeed, { "%.1f".format(it) }) { p.cursorSpeed = it; saveSettings(p) })
        cursorRows(p) { saveSettings(p) }.take(1).forEach { sens.addView(it) }

        val rate = section("Mouse max polling rate", "Set this lower if your phone struggles (delays or stutter while aiming).")
        val rates = listOf(0 to "No limit", 125 to "125 Hz", 250 to "250 Hz", 500 to "500 Hz")
        rate.addView(scroll(*rates.map { (hz, n) ->
            chip(if (st.pollHz == hz) "[$n]" else n) { st.pollHz = hz; saveOptions(); refresh() }
        }.toTypedArray()))

        val smooth = section("Mouse smoothness", "Smooths camera movement at the cost of a little delay.")
        val levels = listOf(0f to "Off", 0.3f to "Low", 0.5f to "Medium", 0.7f to "High", 0.8f to "Max")
        smooth.addView(scroll(*levels.map { (v, n) ->
            chip(if (st.smooth == v) "[$n]" else n) { st.smooth = v; saveOptions(); refresh() }
        }.toTypedArray()))
    }

    private fun hud() {
        title("HUD & crosshair")
        val st = s
        val h = section("HUD")
        h.addView(scroll(
            toggleChip("Show key labels", { st.showHud }) { st.showHud = it; saveOptions() },
            toggleChip("Show touch paths", { st.showTouches }) { st.showTouches = it; saveOptions() },
            toggleChip("Show recoil panel", { st.showRcPanel }) { st.showRcPanel = it; saveOptions() },
        ))
        note(h, "While playing, F9 (changeable per profile) shows/hides the labels.")
        h.addView(label("Label color"))
        h.addView(colorRow(st.hudColor) { st.hudColor = it })
        h.addView(stepSlider("Label opacity", 0.1f, 1f, 0.05f, st.hudAlpha, { "${(it * 100).toInt()}%" }) { st.hudAlpha = it; saveOptions() })
        h.addView(stepSlider("Floating button opacity", 0.2f, 1f, 0.05f, st.bubbleAlpha, { "${(it * 100).toInt()}%" }) { st.bubbleAlpha = it; saveOptions() })

        val c = section("Crosshair")
        c.addView(scroll(
            toggleChip("Show crosshair", { st.crosshair }) { st.crosshair = it; saveOptions() },
            toggleChip("Hide when aiming", { st.crossHideAiming }) { st.crossHideAiming = it; saveOptions() },
        ))
        val styles = listOf("Cross", "Dot", "Circle", "Cross + dot", "T")
        c.addView(scroll(*styles.mapIndexed { i, n ->
            chip(if (st.crossStyle == i) "[$n]" else n) { st.crossStyle = i; saveOptions(); refresh() }
        }.toTypedArray()))
        c.addView(label("Color"))
        c.addView(colorRow(st.crossColor) { st.crossColor = it })
        c.addView(stepSlider("Red", 0f, 255f, 1f, Color.red(st.crossColor).toFloat(), { it.toInt().toString() }) {
            st.crossColor = Color.argb(255, it.toInt(), Color.green(st.crossColor), Color.blue(st.crossColor)); saveOptions() })
        c.addView(stepSlider("Green", 0f, 255f, 1f, Color.green(st.crossColor).toFloat(), { it.toInt().toString() }) {
            st.crossColor = Color.argb(255, Color.red(st.crossColor), it.toInt(), Color.blue(st.crossColor)); saveOptions() })
        c.addView(stepSlider("Blue", 0f, 255f, 1f, Color.blue(st.crossColor).toFloat(), { it.toInt().toString() }) {
            st.crossColor = Color.argb(255, Color.red(st.crossColor), Color.green(st.crossColor), it.toInt()); saveOptions() })
        c.addView(stepSlider("Size", 4f, 80f, 1f, st.crossSize.toFloat(), { "${it.toInt()} dp" }) { st.crossSize = it.toInt(); saveOptions() })
        c.addView(stepSlider("Opacity", 0.1f, 1f, 0.05f, st.crossAlpha, { "${(it * 100).toInt()}%" }) { st.crossAlpha = it; saveOptions() })
        c.addView(stepSlider("Horizontal offset", -200f, 200f, 1f, st.crossOffsetX.toFloat(), { "${it.toInt()} px" }) { st.crossOffsetX = it.toInt(); saveOptions() })
        c.addView(stepSlider("Vertical offset", -200f, 200f, 1f, st.crossOffsetY.toFloat(), { "${it.toInt()} px" }) { st.crossOffsetY = it.toInt(); saveOptions() })
    }

    private fun monitor() {
        title("Device monitor")
        val st = s
        val m = section("Panel")
        m.addView(scroll(
            toggleChip("Enable device monitor", { st.monitor }) { st.monitor = it; saveOptions() },
            toggleChip("Fixed panel (not draggable)", { st.monFixed }) { st.monFixed = it; st.monitor = st.monitor; saveOptions() },
        ))
        val rates = listOf(2000 to "Low", 1000 to "Medium", 500 to "High")
        m.addView(scroll(label("Refresh rate:"), *rates.map { (ms, n) ->
            chip(if (st.monRefreshMs == ms) "[$n]" else n) { st.monRefreshMs = ms; saveOptions(); refresh() }
        }.toTypedArray()))
        note(m, "Higher refresh rates cost a little performance. Toggling \"Fixed\" applies after the panel is shown again.")

        val items = section("Show")
        items.addView(scroll(
            toggleChip("FPS", { st.monFps }) { st.monFps = it; saveOptions() },
            toggleChip("CPU usage", { st.monCpu }) { st.monCpu = it; saveOptions() },
            toggleChip("CPU temperature", { st.monTemp }) { st.monTemp = it; saveOptions() },
            toggleChip("Battery", { st.monBattery }) { st.monBattery = it; saveOptions() },
        ))
        items.addView(scroll(
            toggleChip("Download/upload speed", { st.monNet }) { st.monNet = it; saveOptions() },
            toggleChip("Router latency", { st.monPing }) { st.monPing = it; saveOptions() },
            toggleChip("Clock", { st.monTime }) { st.monTime = it; saveOptions() },
            toggleChip("Play time", { st.monPlayTime }) { st.monPlayTime = it; saveOptions() },
        ))

        val g = section("Records (this session)")
        val h = MonitorView.history.toList()
        if (h.isEmpty()) note(g, "No data yet. Enable the monitor and play for a while.")
        else {
            fun line(name: String, values: List<Float>, unit: String) {
                val v = values.filter { !it.isNaN() }
                if (v.isEmpty()) return
                g.addView(label("$name   avg %.0f$unit   min %.0f$unit   max %.0f$unit".format(v.average(), v.min(), v.max()), 13f, Pal.INK))
                g.addView(SparkView(this, v))
            }
            line("FPS", h.map { it.first }, "")
            line("CPU", h.map { it.second }, "%")
            line("Temperature", h.map { it.third }, "°C")
        }
    }

    private fun advanced() {
        title("Advanced")
        val st = s
        val tf = section("Touch while mapping")
        tf.addView(toggleChip("Lift the aim finger when the mouse stops", { st.touchFriendly }) { st.touchFriendly = it; saveOptions() })
        note(tf, "Android ignores screen touches while a mapped finger is held down. With this on, the aim finger lifts " +
            "a moment after you stop moving the mouse, so you can tap the screen. Turn it off if a game needs the finger held.")
        tf.addView(toggleChip("Pause and hide when I leave the game", { st.autoHide }) { st.autoHide = it; saveOptions() })
        note(tf, "Leaving the game (home, recents, another app) pauses mapping and hides the floating button; " +
            "coming back shows it again and resumes mapping.")

        val t = section("Touch compatible mode", "Lets you touch the screen while mapping is on (your fingers are merged with the mapped touches). " +
            "Without it, touching the screen can interrupt a mapped finger in some games.")
        t.addView(toggleChip("Enable touch compatible mode", { st.touchCompat }) { st.touchCompat = it; saveOptions() })

        val g = section("Gamepad", "Left stick drives the first joystick control, right stick the view. Buttons, D-pad and triggers can be bound like keys.")
        g.addView(stepSlider("Right stick speed", 0.2f, 4f, 0.1f, st.padSpeed, { "%.1f".format(it) }) { st.padSpeed = it; saveOptions() })
        g.addView(stepSlider("Dead zone", 0f, 0.5f, 0.01f, st.deadzone, { "${(it * 100).toInt()}%" }) { st.deadzone = it; saveOptions() })

        val r = section("Reset")
        r.addView(chip("Reset all settings") {
            confirm("Reset all settings (profiles are kept)?") {
                AppSettings().also { it.games = s.games }.save(this); saveOptions(); refresh()
            }
        })
    }

    private fun activation() {
        title("Activation Assistant")
        val running = ServerConnection.shizukuRunning()
        val granted = ServerConnection.shizukuGranted()
        val st = ServerConnection.state
        fun step(text: String, done: Boolean, action: String, onClick: () -> Unit) =
            root.addView(ggRow(text, trailing = if (done) TextView(this).apply { this.text = "Done"; setTextColor(Pal.OK); textSize = 16f }
                else primaryButton(action) { onClick() }))
        step("1. Start Shizuku service", running, "Go") {
            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (i != null) startActivity(i)
            else runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))) }
        }
        step("2. Grant permission to use Shizuku", granted, "Grant") {
            if (!running) toast("Start Shizuku first")
            else if (rikka.shizuku.Shizuku.isPreV11()) toast("Please update Shizuku")
            else rikka.shizuku.Shizuku.requestPermission(1)
        }
        step("3. Click to activate", st.connected, "Activate") { ServerConnection.restart(this); toast("Activating...") }
        note(root, "    Shizuku can be started with Wireless debugging (no PC), a PC over USB, or root. " +
            "After a phone restart, start Shizuku again.")
        val error = ServerConnection.startError
        if (st.connected) {
            root.addView(ggHeader("Status"))
            root.addView(ggRow("Input server", "Running (${ServerConnection.mode}), ${st.mice} mouse, ${st.keyboards} keyboard"))
            root.addView(ggRow("Show devices", trailing = ggValue("")) { showDevices() })
            root.addView(ggRow("Server log", trailing = ggValue("")) { showServerLog() })
            root.addView(ggRow("Restart server", trailing = ggValue("")) { ServerConnection.restart(this); toast("Restarting...") })
        } else if (error != null) {
            root.addView(ggHeader("Activation failed"))
            root.addView(label(error, 13f, Pal.BAD).apply { setPadding(dp(16f), dp(8f), dp(16f), dp(8f)); setTextIsSelectable(true) })
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER
                addView(primaryButton("Retry") { ServerConnection.restart(this@SettingsActivity); toast("Activating...") })
                addView(primaryButton("Copy details") {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("error", error)); toast("Copied")
                })
            })
        }
    }

    private fun permissions() {
        title("Permissions Management")
        root.addView(ggHeader("Required Permission"))
        val overlay = android.provider.Settings.canDrawOverlays(this)
        root.addView(ggRow("Overlay Window Permission", "Only used to show the in-game controls over the game you play",
            if (overlay) granted() else primaryButton("Grant") {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            }))
        val notif = android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        root.addView(ggRow("Notification Permission", "Shows that the in-game controls are running",
            if (notif) granted() else primaryButton("Grant") {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }))
        root.addView(ggHeader("Recommended Settings"))
        val pm = getSystemService(android.os.PowerManager::class.java)
        val unrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        root.addView(ggRow("Run in Background", "Prevents the system from closing MapMouse Pro while you play",
            if (unrestricted) granted() else primaryButton("Turn Off Limits") {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }))
    }

    private fun granted() = TextView(this).apply { text = "Granted"; setTextColor(Pal.OK); textSize = 16f }

    private fun showDevices() {
        val json = ServerConnection.listDevices() ?: return toast("Server not connected")
        val arr = org.json.JSONArray(json)
        val text = if (arr.length() == 0) "No keyboard, mouse or gamepad found. Connect one by USB (OTG) or Bluetooth."
        else (0 until arr.length()).joinToString(LF) {
            val o = arr.getJSONObject(it)
            val kind = listOfNotNull("mouse".takeIf { o.getBoolean("mouse") }, "keyboard".takeIf { o.getBoolean("keyboard") },
                "gamepad".takeIf { o.optBoolean("pad") }, "touchscreen".takeIf { o.optBoolean("touch") })
            "${o.getString("name")}  (${kind.joinToString("+")})"
        }
        android.app.AlertDialog.Builder(this).setTitle("Input devices").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun showServerLog() {
        val log = ServerConnection.serverLog()?.ifBlank { "(empty)" } ?: return toast("Server not connected")
        android.app.AlertDialog.Builder(this).setTitle("Server log").setMessage(log)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("server log", log)); toast("Copied")
            }.show()
    }

    private fun pickImage() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.openInputStream(uri)!!.use { input ->
                CursorView.pointerFile(this).outputStream().use { input.copyTo(it) }
            }
        }.onSuccess { s.pointerStyle = 5; saveOptions(); refresh() }.onFailure { toast("Couldn't read that image") }
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_MOUSE = "mouse"
        const val PAGE_HUD = "hud"
        const val PAGE_MONITOR = "monitor"
        const val PAGE_ADVANCED = "advanced"
        const val PAGE_ACTIVATION = "activation"
        const val PAGE_PERMISSIONS = "permissions"
        private val LF = System.lineSeparator()
        private const val REQ_IMAGE = 3
    }
}

/** Minimal line chart for the monitor records. */
private class SparkView(ctx: android.content.Context, private val values: List<Float>) : android.view.View(ctx) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6FCF97.toInt(); strokeWidth = ctx.dp(1.5f).toFloat(); style = android.graphics.Paint.Style.STROKE
    }

    init {
        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(60f))
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (values.size < 2) return
        val lo = values.min(); val hi = values.max().coerceAtLeast(lo + 1f)
        val path = android.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = width * i / (values.size - 1f)
            val y = height - (v - lo) / (hi - lo) * height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
